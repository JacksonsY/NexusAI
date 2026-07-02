package com.nexusai.relay;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.limit.ConcurrencySlot;
import com.nexusai.observe.log.ActiveRequestRegistry;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.ProviderFactory;
import com.nexusai.provider.spi.UpstreamRequest;
import com.nexusai.relay.affinity.AffinityKeyResolver;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.filter.IdempotencyFilter;
import com.nexusai.relay.filter.RelayFilter;
import com.nexusai.relay.settle.SettlementService;
import com.nexusai.relay.upstream.OutboundRequestAssembler;
import com.nexusai.relay.upstream.SkipAndRetryException;
import com.nexusai.relay.upstream.UpstreamExchange;
import com.nexusai.relay.upstream.UpstreamHealthRecorder;
import com.nexusai.relay.usage.UsageReporter;
import com.nexusai.router.RouteResult;
import com.nexusai.router.RouteService;
import com.nexusai.router.affinity.StickyAffinity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import com.nexusai.common.util.ClientFamilyDetector;
import com.nexusai.common.util.ClientIp;

/**
 * 同步透传管线编排：读体 → 闸门链（{@link RelayFilter}：鉴权→解析→模型权限→限流→USD 额度强闸门）→
 * <b>带故障转移的路由循环</b>（route 选号 → {@link OutboundRequestAssembler} 装配 → {@link UpstreamExchange} 透传；
 * 上游错误按可重试分类驱动换 Key/换渠道重试）。结算/遥测/健康记账分别归
 * {@link SettlementService}/{@link UsageReporter}/{@link UpstreamHealthRecorder}（编排器只持流程，不持实现）。
 * <p>route+adapt 为可重试单元（不在闸门链内）；freeze 基于模型倍率（渠道无关）置于链末，换渠道不重扣，终态失败全退。
 *
 * @author nexusai
 */
@Slf4j
@Service
public class RelayOrchestrator {

    private static final byte[] EMPTY = new byte[0];
    private static final int MAX_ATTEMPTS = 3;
    /**
     * 🔴 请求体聚合硬上限（64MB）：{@code DataBufferUtils.join} 无参重载会无上限把整个 body 收进内存，
     * 且聚合发生在鉴权之前——未授权客户端灌大体即可打爆堆（DoS）。此上限须覆盖最大合法 multipart（音频/图编），
     * 超限即 413。注意 {@code spring.codec.max-in-memory-size} 只约束 codec 反序列化，对裸 body join 无效。
     */
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    /** 故障转移重试退避基数（ms·按 attempt 线性·封顶 {@link #RETRY_BACKOFF_MAX_MS}·非阻塞 delaySubscription·降瞬时风暴；仅上游错误/透传异常路径用） */
    private static final long RETRY_BACKOFF_MS = 50L;
    /** 故障转移重试退避封顶（ms·线性退避不超过此值）。 */
    private static final long RETRY_BACKOFF_MAX_MS = 200L;

    private final List<RelayFilter> filters;
    private final RouteService routeService;
    private final StickyAffinity stickyAffinity;
    private final AffinityKeyResolver affinityResolver;
    private final ProviderFactory providerFactory;
    private final OutboundRequestAssembler assembler;
    private final UpstreamExchange exchange;
    private final SettlementService settlement;
    private final UsageReporter reporter;
    private final UpstreamHealthRecorder health;
    private final FailoverAuditService failoverAudit;
    private final ActiveRequestRegistry activeRegistry;
    private final ConcurrencySlot concurrencySlot;
    private final IdempotencyFilter idempotencyFilter;

    public RelayOrchestrator(List<RelayFilter> filters,
                             RouteService routeService,
                             StickyAffinity stickyAffinity,
                             AffinityKeyResolver affinityResolver,
                             ProviderFactory providerFactory,
                             OutboundRequestAssembler assembler,
                             UpstreamExchange exchange,
                             SettlementService settlement,
                             UsageReporter reporter,
                             UpstreamHealthRecorder health,
                             FailoverAuditService failoverAudit,
                             ActiveRequestRegistry activeRegistry,
                             ConcurrencySlot concurrencySlot,
                             IdempotencyFilter idempotencyFilter) {
        List<RelayFilter> ordered = new ArrayList<>(filters);
        AnnotationAwareOrderComparator.sort(ordered);
        this.filters = List.copyOf(ordered);
        this.routeService = routeService;
        this.stickyAffinity = stickyAffinity;
        this.affinityResolver = affinityResolver;
        this.providerFactory = providerFactory;
        this.assembler = assembler;
        this.exchange = exchange;
        this.settlement = settlement;
        this.reporter = reporter;
        this.health = health;
        this.failoverAudit = failoverAudit;
        this.activeRegistry = activeRegistry;
        this.concurrencySlot = concurrencySlot;
        this.idempotencyFilter = idempotencyFilter;
    }

    public Mono<Void> relay(ServerWebExchange webExchange, String path) {
        ServerHttpResponse response = webExchange.getResponse();
        return DataBufferUtils.join(webExchange.getRequest().getBody(), MAX_BODY_BYTES)
                .onErrorMap(DataBufferLimitException.class,
                        e -> GatewayException.of(GatewayErrorCode.PAYLOAD_TOO_LARGE, "请求体超过上限 " + MAX_BODY_BYTES + " 字节"))
                .map(RelayOrchestrator::toBytes)
                .defaultIfEmpty(EMPTY)
                .flatMap(raw -> Mono.fromCallable(() -> gate(webExchange, path, raw))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ctx -> {
                    // R2：请求 id 回吐（成功/失败/流式皆有·供 /v1/generation/{id} 查计量明细）
                    response.getHeaders().set(GatewayConstants.REQUEST_ID_HEADER, ctx.getRequestId());
                    // 响应缓存命中（C 类·非流式）：不调上游，退预扣 freeze，直接回缓存响应
                    Mono<Void> cached = exchange.tryServeFromCache(response, ctx);
                    Mono<Void> pipeline = cached != null
                            ? cached
                            : forwardWithFallback(response, ctx, new HashSet<>(), new HashSet<>(), 1);
                    // 终态兜底：移除活跃注册 + 释放入站并发槽（成功/失败/取消任一信号·完成后由落库日志行接棒展示）
                    return pipeline.doFinally(sig -> {
                        activeRegistry.remove(ctx.getRequestId());   // 内存态·即时移除
                        // 🔴 completeIdempotency(幂等 finish)/releaseInboundSlot 均为阻塞 Redisson；doFinally 在终态
                        // 信号线程触发（非流式=Netty 事件循环线程），必须 offload 出事件循环——否则带 Idempotency-Key
                        // 的请求每次在 finally 阻塞事件循环。
                        Runnable cleanup = () -> {
                            completeIdempotency(ctx, response, sig);
                            releaseInboundSlot(ctx);
                        };
                        try {
                            Schedulers.boundedElastic().schedule(cleanup);
                        } catch (RejectedExecutionException rex) {
                            // 调度器拒绝（极端过载）：兜底同步执行——短暂阻塞事件循环远胜于泄漏入站槽 600s / 幂等键悬挂至 TTL
                            cleanup.run();
                        }
                    });
                })
                .onErrorResume(ex -> exchange.writeError(response, ex));
    }

    // ===== 闸门链（同步·boundedElastic）：鉴权→解析→模型权限→限流→freeze =====
    private RelayContext gate(ServerWebExchange exchange, String path, byte[] raw) {
        RelayContext ctx = new RelayContext();
        ctx.setRequestId(UUID.randomUUID().toString());
        // trace_id：TraceIdWebFilter 已生成并放入 TRACE_ATTR，此处取入 ctx → 随请求日志落库（否则 nai_request_log.trace_id 恒 null·控制台 traceId 排障失效）
        ctx.setTraceId((String) exchange.getAttributes().get(GatewayConstants.TRACE_ATTR));
        ctx.setStartNanos(System.nanoTime());
        ctx.setPath(path);
        ctx.setClientIp(clientIp(exchange));
        ctx.setUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        ctx.setContentType(exchange.getRequest().getHeaders().getFirst("Content-Type"));
        ctx.setBody(raw);
        try {
            for (RelayFilter f : filters) {
                f.filter(ctx, exchange);
            }
        } catch (RuntimeException e) {
            idempotencyFilter.finish(ctx, false);
            releaseInboundSlot(ctx);   // 闸门链中途拒绝（freeze/权限等）→ 释放已占入站并发槽，防泄漏
            throw e;
        }
        registerActive(ctx);
        return ctx;
    }

    /** 释放入站 Key 并发槽（终态/失败兜底·zrem 幂等·token 空则空操作）。 */
    private void releaseInboundSlot(RelayContext ctx) {
        if (ctx.getInboundSlotToken() != null && ctx.getToken() != null) {
            concurrencySlot.releaseInbound(ctx.getToken().getId(), ctx.getInboundSlotToken());
        }
    }

    private void completeIdempotency(RelayContext ctx, ServerHttpResponse response, SignalType signalType) {
        boolean responseOk = response.getStatusCode() == null || response.getStatusCode().is2xxSuccessful();
        boolean upstreamOk = ctx.getUpstreamStatus() == null || ctx.getUpstreamStatus() < 400;
        boolean completed = signalType == SignalType.ON_COMPLETE && responseOk && upstreamOk;
        // 🔴 防双扣：流中断(CANCEL)但上游已 2xx 产出 = settle 已按部分用量计费——必须置 DONE(非 FAILED)，
        // 否则同 Idempotency-Key 重试会再 freeze+settle 二次扣费。仅"未计费失败"(上游错误/闸门前拒)才 FAILED 可重试。
        boolean billedOnAbort = ctx.isStreamAborted() && upstreamOk;
        idempotencyFilter.finish(ctx, completed || billedOnAbort);
    }

    /** 闸门通过后登记活跃请求（pending·活跃行实时可见；路由后补渠道，writeBack 首字节转 streaming）。 */
    private void registerActive(RelayContext ctx) {
        try {
            String modality = RelayFormats.modalityOf(ctx.getPath());
            var req = new ActiveRequestRegistry.ActiveRequest();
            req.setRequestId(ctx.getRequestId());
            req.setProjectCode(ctx.getToken() == null ? null : ctx.getToken().getProjectCode());
            req.setModel(ctx.getPeek() == null ? null : ctx.getPeek().model());
            req.setModality(modality);
            req.setStreamMode(ctx.getPeek() != null && ctx.getPeek().stream());
            req.setApiFormat(RelayFormats.apiFormatOf(ctx.getPath(), modality));
            req.setClientIp(ctx.getClientIp());
            req.setUserAgent(ctx.getUserAgent());
            req.setClientFamily(ClientFamilyDetector.detect(ctx.getUserAgent()));
            req.setStartedAtMillis(System.currentTimeMillis());
            activeRegistry.register(req);
        } catch (Exception e) {
            log.debug("活跃请求登记失败（不影响主链）requestId={}", ctx.getRequestId());
        }
    }

    // ===== 路由+透传（带故障转移重试） =====
    private Mono<Void> forwardWithFallback(ServerHttpResponse response, RelayContext ctx,
                                           Set<Long> exChannels, Set<Long> exKeys, int attempt) {
        return Mono.fromCallable(() -> routeAndAdapt(ctx, exChannels, exKeys))
                .subscribeOn(Schedulers.boundedElastic())
                // 🔴 上游响应回调由 reactor-netty 客户端事件循环线程投递；onUpstreamResponse/onAttemptError 内含阻塞
                // releaseSlot(Redisson ZSET remove)/refundAll(Redisson Lua + MyBatis outbox)/健康记账，必须 defer 到
                // boundedElastic 执行——否则上游 429/5xx/超时风暴（恰是这些分支最常触发时）会阻塞事件循环、拖垮该
                // 事件循环上全部并发请求的吞吐。成功非流式路径的正文阻塞此前已由 writeBack 的 publishOn 兜住。
                .flatMap(ur -> exchange.dispatch(ctx, ur,
                        cr -> Mono.defer(() -> onUpstreamResponse(response, ctx, cr, exChannels, exKeys, attempt))
                                .subscribeOn(Schedulers.boundedElastic())))
                .onErrorResume(err -> Mono.defer(() -> onAttemptError(response, ctx, err, exChannels, exKeys, attempt))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** 选号（四层·exclude 失败者）→ 项目渠道策略校验 → 装配（体改写链+凭证+Provider 适配·归 {@link OutboundRequestAssembler}）。 */
    private UpstreamRequest routeAndAdapt(RelayContext ctx, Set<Long> exChannels, Set<Long> exKeys) {
        ctx.setRoute(null);   // 清前次（route 抛错则 r==null → 终态，不误释放槽）
        String affinity = affinityResolver.resolve(ctx).key();
        RouteResult route = routeService.route(ctx.getGroup(), ctx.getPeek().model(),
                affinity, exChannels, exKeys);
        ctx.setRoute(route);
        // 选号即补写亲和条目元数据（项目/模型/键来源·监控列表）——不等 settle（终态失败也有真实 meta）
        reporter.attachAffinityMeta(ctx, false, RelayFormats.modalityOf(ctx.getPath()), null);

        // 项目访问策略·渠道约束：route 出的渠道不被项目允许 → 排除该渠道换选。
        // 借现有 failover 机制零侵入 RouteService；渠道总数小（候选耗尽则 NO_CHANNEL_AVAILABLE 终态退款）。
        if (ctx.getProjectPolicy() != null && !ctx.getProjectPolicy().channelAllowed(route.channel())) {
            long ch = route.channel().getId();
            routeService.releaseSlot(route);
            ctx.setRoute(null);
            throw new SkipAndRetryException(ch, null, GatewayErrorCode.NO_CHANNEL_AVAILABLE,
                    "所属项目的渠道策略不允许渠道 #" + ch);
        }

        Provider provider = providerFactory.resolve(route.channel().getProtocol());
        try {
            UpstreamRequest adapted = assembler.assemble(ctx, route, provider);
            activeRegistry.updateRoute(ctx.getRequestId(), route.channel().getId(), route.key().getId(),
                    route.targetModel(), RelayFormats.endpointApiFormatOf(
                            route.channel().getProtocol(), RelayFormats.modalityOf(ctx.getPath())));
            return adapted;
        } catch (SkipAndRetryException e) {
            // 装配显式改选信号（流式跨协议排渠道）：槽释放/route 清理统一在此（抛出方不碰路由生命周期）
            routeService.releaseSlot(route);
            ctx.setRoute(null);
            throw e;
        } catch (RuntimeException e) {
            // 凭证解密/适配/改写失败（非上游故障）：释放槽 + 排该 Key 同渠道换号重试，不罚健康
            Long keyId = route.key().getId();
            routeService.releaseSlot(route);
            ctx.setRoute(null);
            throw new SkipAndRetryException(null, keyId, GatewayErrorCode.INTERNAL_ERROR,
                    "凭证解密/Provider 适配失败: " + e.getMessage());
        }
    }

    private Mono<Void> onUpstreamResponse(ServerHttpResponse response, RelayContext ctx, ClientResponse cr,
                                          Set<Long> exChannels, Set<Long> exKeys, int attempt) {
        int sc = cr.statusCode().value();
        ctx.setUpstreamStatus(sc);
        // 可重试分类（429/401/403 + 5xx·排除网关超时类 504/524）：
        // 超时类重试只会向已过载/慢的上游再压一次（放大故障），且请求可能已被上游处理（重试有重复执行风险），故直接终态返回。
        boolean retryable = sc == 429 || sc == 401 || sc == 403 || (sc >= 500 && sc != 504 && sc != 524);
        RouteResult r = ctx.getRoute();
        if (retryable && attempt < MAX_ATTEMPTS) {
            health.recordFailure(ctx, sc, cr);
            routeService.releaseSlot(r);
            exKeys.add(r.key().getId());
            if (sc >= 500) {
                exChannels.add(r.channel().getId());   // 5xx：渠道故障，连渠道一并排除
                stickyAffinity.recordProviderSwitch();
                ctx.setFallbackCount(ctx.getFallbackCount() + 1);   // 跨渠道转移（has_fallback）
            } else {
                ctx.setRetryCount(ctx.getRetryCount() + 1);         // 同渠道换 Key（has_retry）
            }
            failoverAudit.record(ctx.getRequestId(), attempt, sc >= 500 ? r.channel().getId() : null,
                    r.key().getId(), String.valueOf(sc), "upstream-" + sc, "retry");
            log.debug("上游 {} attempt={} 可重试，换号重试 requestId={}", sc, attempt, ctx.getRequestId());
            // 排空上游体释放连接后重试（小退避降瞬时风暴·已 exclude 故障 Key/渠道，下一轮换号）
            return cr.bodyToMono(byte[].class).onErrorReturn(EMPTY)
                    .then(Mono.defer(() -> forwardWithFallback(response, ctx, exChannels, exKeys, attempt + 1))
                            .delaySubscription(retryBackoff(attempt)));
        }
        // 终态：成功 / 不可重试 4xx / 重试耗尽
        if (sc < 400) {
            health.recordSuccess(ctx);
        } else {
            health.recordFailure(ctx, sc, cr);
        }
        return exchange.writeBack(response, cr, ctx);
    }

    private Mono<Void> onAttemptError(ServerHttpResponse response, RelayContext ctx, Throwable err,
                                      Set<Long> exChannels, Set<Long> exKeys, int attempt) {
        // 🔴 已进入终态回写后的异常（客户端断连/写失败/流中错）：禁止重试/重复结算/重复释放槽。
        // 槽已由 writeBack.doFinally 释放、结算已由 settle-once 门闩处理 → 直接上抛，relay 顶层 writeError 自判 committed。
        if (ctx.isTerminal()) {
            return Mono.error(err);
        }
        // 换号再试信号（流式跨协议排渠道 / 凭证适配失败排 Key）：槽已释放、route 已清；不罚健康
        if (err instanceof SkipAndRetryException se) {
            if (se.excludeChannelId() != null) {
                exChannels.add(se.excludeChannelId());
                stickyAffinity.recordProviderSwitch();
                ctx.setFallbackCount(ctx.getFallbackCount() + 1);   // 排渠道改选 = 跨渠道转移
            }
            if (se.excludeKeyId() != null) {
                exKeys.add(se.excludeKeyId());
                ctx.setRetryCount(ctx.getRetryCount() + 1);         // 排 Key 换号 = 同渠道重试
            }
            if (attempt < MAX_ATTEMPTS) {
                failoverAudit.record(ctx.getRequestId(), attempt, se.excludeChannelId(), se.excludeKeyId(),
                        se.exhaustCode().name(), "skip-retry", "retry");
                return Mono.defer(() -> forwardWithFallback(response, ctx, exChannels, exKeys, attempt + 1));
            }
            failoverAudit.record(ctx.getRequestId(), attempt, se.excludeChannelId(), se.excludeKeyId(),
                    se.exhaustCode().name(), "skip-retry", "exhausted");
            settlement.refundAll(ctx);
            return exchange.writeError(response, GatewayException.of(se.exhaustCode(), se.getMessage()));
        }
        RouteResult r = ctx.getRoute();
        if (r == null) {
            // 选号阶段失败（无渠道/无 Key/并发饱和）= 终态，无槽可释放，全额退 freeze
            settlement.refundAll(ctx);
            return exchange.writeError(response, err);
        }
        // 透传阶段异常（连接/超时）：释放槽，分类重试
        routeService.releaseSlot(r);
        health.recordTransient(ctx);
        if (attempt < MAX_ATTEMPTS) {
            exKeys.add(r.key().getId());
            exChannels.add(r.channel().getId());
            stickyAffinity.recordProviderSwitch();
            ctx.setFallbackCount(ctx.getFallbackCount() + 1);   // 连渠道一并排除 = 跨渠道转移
            failoverAudit.record(ctx.getRequestId(), attempt, r.channel().getId(), r.key().getId(),
                    "TRANSIENT", "transient-" + err.getClass().getSimpleName(), "retry");
            log.debug("透传异常 attempt={} 重试 requestId={}: {}", attempt, ctx.getRequestId(), err.toString());
            return Mono.defer(() -> forwardWithFallback(response, ctx, exChannels, exKeys, attempt + 1))
                    .delaySubscription(retryBackoff(attempt));
        }
        failoverAudit.record(ctx.getRequestId(), attempt, r.channel().getId(), r.key().getId(),
                "TRANSIENT", "transient-" + err.getClass().getSimpleName(), "exhausted");
        settlement.refundAll(ctx);
        return exchange.writeError(response, err);
    }

    // ===== 辅助 =====

    /** 故障转移重试退避（线性 attempt·封顶 200ms·非阻塞 delaySubscription·仅上游错误/透传异常路径；本地改选 SkipAndRetry 不退避立即换号）。 */
    private static Duration retryBackoff(int attempt) {
        return Duration.ofMillis(Math.min(RETRY_BACKOFF_MS * attempt, RETRY_BACKOFF_MAX_MS));
    }

    private static byte[] toBytes(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        DataBufferUtils.release(buf);
        return bytes;
    }

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        String remote = exchange.getRequest().getRemoteAddress() == null ? ""
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        String ip = ClientIp.resolve(remote, xff);
        return ip.isEmpty() ? "-" : ip;
    }
}
