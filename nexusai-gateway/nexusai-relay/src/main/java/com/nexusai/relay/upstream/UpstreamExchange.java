package com.nexusai.relay.upstream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.runtime.ModuleToggles;
import com.nexusai.observe.log.ActiveRequestRegistry;
import com.nexusai.observe.log.RequestBodyCapture;
import com.nexusai.protocol.transform.CanonicalConverter;
import com.nexusai.protocol.transform.OpenAiSseConverter;
import com.nexusai.provider.spi.UpstreamRequest;
import com.nexusai.relay.cache.ResponseCache;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.proxy.OutboundProxyClients;
import com.nexusai.relay.settle.SettlementService;
import com.nexusai.relay.usage.Usage;
import com.nexusai.relay.usage.UsageExtractor;
import com.nexusai.router.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClientRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 上游交换（WebClient 调用 + 终态回写）：出站代理解析 → 渠道级首字节/总超时 → 透传调用；
 * 终态三分支回写（跨协议流式转码 / 原生 SSE 透传 / 非流式 JSON）+ 响应缓存读写（C 类·非流式）
 * + 流末结算触发（{@link SettlementService}）+ TTFT/正文捕获。
 * <p>上游响应的重试判定不在本类（归编排器故障转移循环）——dispatch 经回调把 {@link ClientResponse} 交还编排器。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class UpstreamExchange {

    private static final byte[] EMPTY = new byte[0];
    /** SSE 流式响应 Content-Type（跨协议转码分支显式置头）。 */
    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    /** 响应捕获上限（5MB·超限截断） */
    private static final int CAPTURE_MAX = 5 * 1024 * 1024;
    /**
     * 渠道未配首字节超时（null/&lt;=0）时的兜底秒数（R2）：杜绝 TCP 已连但上游静默（响应头永不到达）时
     * 请求永久挂起、Netty 连接被无限占用。只界定「响应头到达」阶段，流式合法长跑不受影响。
     */
    private static final int DEFAULT_FIRST_BYTE_TIMEOUT_SEC = 60;
    /**
     * 流式 drain idle 上限（秒）：客户端断连后仍续读上游至终态以保证计费正确（修复断连漏计费）；
     * 上游静默超此值则放弃 drain，避免 boundedElastic 线程被卡死上游永久占用。
     */
    private static final int STREAM_DRAIN_IDLE_TIMEOUT_SEC = 180;
    /**
     * 在途上游订阅（含断连后续读 drain）的全局并发上限：每个订阅最多占 boundedElastic 线程 + Netty 连接达
     * {@link #STREAM_DRAIN_IDLE_TIMEOUT_SEC} 秒。无界时大量中途断连冲击慢上游会堆爆线程/连接池，故用全局
     * 信号量封顶；满载则不再 drain，直接取消上游释放连接（背压泄流）。
     */
    private static final int MAX_CONCURRENT_DRAINS = 256;
    /** drain 并发闸（全实例共享·acquire 后启动上游订阅·终态 doFinally 归还）。 */
    private static final Semaphore DRAIN_PERMITS = new Semaphore(MAX_CONCURRENT_DRAINS);
    /**
     * 流式镜像 Sink 缓冲上限（chunk 数）：慢客户端（TCP 零窗但不断连）下，无界 buffer 会把整条剩余上游流堆进堆内存。
     * 上游 SSE chunk 多为 KB 级，4096 chunk ≈ 数 MB/连接封顶；溢出即判慢客户端、中止下行（drain 照旧保计费）。
     */
    private static final int MIRROR_BUFFER_CHUNKS = 4096;

    private final OutboundProxyClients outboundProxyClients;
    private final ModuleToggles moduleToggles;
    private final ObjectProvider<ResponseCache> responseCacheProvider;
    private final CanonicalConverter canonicalConverter;
    private final RequestBodyCapture bodyCapture;
    private final ActiveRequestRegistry activeRegistry;
    private final SettlementService settlement;
    private final RouteService routeService;

    public UpstreamExchange(OutboundProxyClients outboundProxyClients,
                            ModuleToggles moduleToggles,
                            ObjectProvider<ResponseCache> responseCacheProvider,
                            CanonicalConverter canonicalConverter,
                            RequestBodyCapture bodyCapture,
                            ActiveRequestRegistry activeRegistry,
                            SettlementService settlement,
                            RouteService routeService) {
        this.outboundProxyClients = outboundProxyClients;
        this.moduleToggles = moduleToggles;
        this.responseCacheProvider = responseCacheProvider;
        this.canonicalConverter = canonicalConverter;
        this.bodyCapture = bodyCapture;
        this.activeRegistry = activeRegistry;
        this.settlement = settlement;
        this.routeService = routeService;
    }

    // ===== 响应缓存（C 类·非流式） =====

    /** 缓存命中则短路（退预扣 freeze + 直接回缓存响应），未命中返回 null。 */
    public Mono<Void> tryServeFromCache(ServerHttpResponse response, RelayContext ctx) {
        ResponseCache cache = activeResponseCache();
        byte[] hit = (cache != null && !ctx.getPeek().stream())
                ? cache.get(cacheScope(ctx), ctx.getPeek().model(), ctx.getBody()) : null;
        if (hit == null) {
            return null;
        }
        settlement.refundAll(ctx);
        ctx.setTerminal(true);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(GatewayConstants.CACHE_HIT_HEADER, "hit");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(hit)));
    }

    private ResponseCache activeResponseCache() {
        return moduleToggles.enabled("response_cache", true) ? responseCacheProvider.getIfAvailable() : null;
    }

    /** 响应缓存租户边界：项目码（缺则空串）。同项目内相同 model+body 才共享缓存·绝不跨项目。 */
    private static String cacheScope(RelayContext ctx) {
        String project = ctx.getToken() != null && ctx.getToken().getProjectCode() != null ? ctx.getToken().getProjectCode() : "";
        // 🔴 path 纳入 scope：防同 body/model 的不同端点互相命中缓存（/v1/audio/speech vs /v1/chat；
        // /v1/responses vs /v1/responses/compact 等同体异语义端点）。get/put 同源，键自然含 path。
        return project + "|" + (ctx.getPath() == null ? "" : ctx.getPath());
    }

    /** 响应是否 JSON（首个非空白字节为 { 或 [）：缓存命中恒以 application/json 回放，故仅缓存 JSON 响应（TTS 等二进制不缓存）。 */
    private static boolean looksJson(byte[] body) {
        if (body == null) {
            return false;
        }
        for (byte b : body) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{' || b == '[';
        }
        return false;
    }

    // ===== 上游调用 =====

    /**
     * 透传调用（出站代理 > 直连 + 渠道级超时），响应经 {@code onResponse} 回调交还编排器判终态/重试。
     */
    public Mono<Void> dispatch(RelayContext ctx, UpstreamRequest ur,
                               Function<ClientResponse, Mono<Void>> onResponse) {
        // 出站代理：渠道 setting.proxyNodeId > 全局 system_proxy_node_id > 直连
        OutboundProxyClients.Resolved resolved = outboundProxyClients
                .resolve(ctx.getRoute() == null ? null : ctx.getRoute().channel().getSetting());
        Long proxyNodeId = resolved.proxyNodeId();
        WebClient.RequestBodySpec spec = resolved.client()
                .method(HttpMethod.valueOf(ur.method()))
                .uri(ur.url());
        ur.headers().forEach(spec::header);
        // 渠道级首字节超时：只管「响应头到达」阶段，流式长跑不受影响。渠道未配（null/<=0）时用默认兜底（R2），
        // 杜绝 TCP 已连但上游静默时请求永久挂起、连接被无限占用。
        Integer fbt = ctx.getRoute() == null ? null : ctx.getRoute().channel().getFirstByteTimeout();
        int firstByteSec = (fbt != null && fbt > 0) ? fbt : DEFAULT_FIRST_BYTE_TIMEOUT_SEC;
        spec.httpRequest(r -> ((HttpClientRequest) r.getNativeRequest())
                .responseTimeout(Duration.ofSeconds(firstByteSec)));
        // 🔴 渠道级非流式总超时不在此处包裹整条 call：那会把「向客户端回写 body」也算进超时，慢客户端/大响应会在
        // 已置终态且已计费后被截断却仍扣费。总超时改到 writeBack 内只给「读上游 body」加 timeout（非流式分支）；
        // 首字节超时仍由上面的 responseTimeout 守，流式合法长跑由 streamWithDrain 的 idle 超时兜。
        return spec.bodyValue(ur.body())
                .exchangeToMono(cr -> {
                    outboundProxyClients.record(proxyNodeId, cr.statusCode().value() < 500);
                    return onResponse.apply(cr);
                })
                .doOnError(e -> outboundProxyClients.record(proxyNodeId, false));
    }

    // ===== 终态回写（三分支） =====

    /** 终态回写：跨协议流式转码 / 原生 SSE 透传 / 非流式 JSON；流末触发结算；doFinally 释放并发槽。 */
    public Mono<Void> writeBack(ServerHttpResponse response, ClientResponse clientResponse, RelayContext ctx) {
        ctx.setTerminal(true);   // 进入终态回写：此后任何错误不得重试/重复结算
        response.setStatusCode(clientResponse.statusCode());
        clientResponse.headers().contentType().ifPresent(response.getHeaders()::setContentType);

        Mono<Void> written;
        if (ctx.getPeek().stream()
                && ctx.isCanonicalConvert()
                && ctx.getInboundFormat() == ApiFormat.OPENAI
                && ctx.getUpstreamFormat() != ApiFormat.OPENAI) {
            // 跨协议流式转码：上游(Anthropic/Gemini) SSE → 客户端 OpenAI SSE；usage 由转码状态累计后结算
            response.getHeaders().set("Content-Type", SSE_CONTENT_TYPE);
            OpenAiSseConverter.State st = new OpenAiSseConverter.State();
            ApiFormat upFmt = ctx.getUpstreamFormat();
            ByteArrayOutputStream capture = bodyCapture.isCollecting() ? new ByteArrayOutputStream() : null;
            written = streamWithDrain(response, clientResponse, ctx,
                    bytes -> {
                        appendCapture(capture, bytes);   // 捕获上游原始 SSE 字节（response_body 语义·转码前）
                        return OpenAiSseConverter.feedBytes(upFmt, bytes, st).getBytes(StandardCharsets.UTF_8);
                    },
                    () -> OpenAiSseConverter.finish(st).getBytes(StandardCharsets.UTF_8),
                    () -> {
                        storeCapture(ctx, capture);
                        settlement.settleAsync(ctx, () -> new Usage(st.promptTokens, st.completionTokens,
                                st.promptTokens + st.completionTokens, st.cachedTokens, st.cacheCreationTokens));
                    });
        } else if (ctx.getPeek().stream()) {
            UsageExtractor.SseUsageState usageState = new UsageExtractor.SseUsageState();
            ByteArrayOutputStream capture = bodyCapture.isCollecting() ? new ByteArrayOutputStream() : null;
            written = streamWithDrain(response, clientResponse, ctx,
                    bytes -> {
                        UsageExtractor.feedSseBytes(bytes, usageState);
                        appendCapture(capture, bytes);   // 全量 SSE 捕获（钳 5MB·原始字节）
                        return bytes;   // 原生 SSE 透传：原样下发上游字节
                    },
                    null,
                    () -> {
                        storeCapture(ctx, capture);
                        settlement.settleAsync(ctx, () -> UsageExtractor.finishSse(usageState));
                    });
        } else {
            // 渠道级非流式总超时：只界定「读上游 body」阶段（非流式合法响应应在此内读完），不含向客户端回写——
            // 超时触发后下方 onErrorResume 退 freeze + 上抛，慢客户端/大响应回写不会误触发。
            Integer rt = ctx.getRoute() == null ? null : ctx.getRoute().channel().getRequestTimeout();
            Mono<byte[]> upstreamBody = clientResponse.bodyToMono(byte[].class).defaultIfEmpty(EMPTY);
            if (rt != null && rt > 0) {
                upstreamBody = upstreamBody.timeout(Duration.ofSeconds(rt));
            }
            written = upstreamBody
                    // 🔴 仅捕获「上游 body 读取阶段」出错（2xx 头已到、随后中途断连/重置/超时 → bodyToMono 出错）：此时下方
                    // flatMap 尚未执行、settle 未派发 → 全额退 freeze（refundAll 经 settle-once 门闩幂等）。**必须置于
                    // publishOn/flatMap 之前**：Reactor 错误信号只从上游算子流向下游，故此处绝不会捕获 flatMap 内
                    // writeWith 的失败——避免在「成功已派发 settleAsync」后再 refundAll 抢门闩、把已计费成功请求全额退
                    // （白嫖 + 丢审计）。refundAll 含阻塞 Redisson·offload 出事件循环。
                    .onErrorResume(err -> Mono.fromRunnable(() -> settlement.refundAll(ctx))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(Mono.<byte[]>error(err)))
                    // 🔴 非流式回写含阻塞调用（attachCostHeaders 读余额 Redis GET·响应缓存写 Redis/磁盘）：WebClient 响应
                    // 续跑在 Netty 客户端事件循环线程，必须 publishOn 出事件循环——否则高并发下事件循环被同步 Redis 阻塞、
                    // 整个反应式服务停摆（流式分支已 subscribeOn boundedElastic·此分支此前遗漏）。
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(bytes -> {
                        markFirstByte(ctx);
                        if (bodyCapture.isCollecting() && bytes.length > 0) {
                            ctx.setResponseCapture(bytes.length > CAPTURE_MAX
                                    ? Arrays.copyOf(bytes, CAPTURE_MAX) : bytes);   // 上游原体（转换前）
                        }
                        Usage parsed = UsageExtractor.fromJsonBody(bytes);            // usage 取自上游原体
                        settlement.attachCostHeaders(response, ctx, parsed);          // R2：非流式回吐（头须先于 body 提交）
                        settlement.settleAsync(ctx, () -> parsed);
                        byte[] out = maybeConvertResponse(ctx, bytes);               // P4：上游格式→客户端格式
                        // 响应缓存写（C 类·成功非流式·命中下次短路省上游+USD 额度）
                        ResponseCache cache = activeResponseCache();
                        // 仅缓存 JSON 响应（命中恒以 application/json 回放·二进制如 TTS 音频不缓存·防错误 Content-Type）
                        if (cache != null && (ctx.getUpstreamStatus() == null || ctx.getUpstreamStatus() < 400) && looksJson(out)) {
                            cache.put(cacheScope(ctx), ctx.getPeek().model(), ctx.getBody(), out);
                        }
                        DataBuffer buf = response.bufferFactory().wrap(out);
                        return response.writeWith(Mono.just(buf));
                    });
        }
        // 🔴 doFinally 在 written 终态信号线程触发——response.writeWith 的完成由 Netty 事件循环线程发出，而
        // releaseSlot 是阻塞 Redisson ZSET remove，直接在此释放会阻塞事件循环。offload 到 boundedElastic。
        return written.doFinally(sig -> releaseSlotOffEventLoop(ctx));
    }

    /** 释放并发槽（offload 出事件循环·zrem 幂等）；调度器拒绝时兜底同步——短暂阻塞优于泄漏并发槽。 */
    private void releaseSlotOffEventLoop(RelayContext ctx) {
        try {
            Schedulers.boundedElastic().schedule(() -> routeService.releaseSlot(ctx.getRoute()));
        } catch (RejectedExecutionException rex) {
            routeService.releaseSlot(ctx.getRoute());
        }
    }

    /** OpenAI 风格错误体回写（已提交则原样上抛）。 */
    public Mono<Void> writeError(ServerHttpResponse response, Throwable ex) {
        GatewayErrorCode ec = (ex instanceof GatewayException ge)
                ? ge.getErrorCode() : GatewayErrorCode.INTERNAL_ERROR;
        // 🔴 仅 GatewayException（面向调用方的预期错误）回吐其 message；INTERNAL_ERROR（凭证解密/JDBC/NPE 等内部细节）
        // 一律回固定文案，明细仅落日志——防内部实现/渠道配置状态经 message 外泄，辅助攻击者探测网关结构。
        String detail = (ex instanceof GatewayException && ex.getMessage() != null) ? ex.getMessage() : ec.getMessage();
        if (ec == GatewayErrorCode.INTERNAL_ERROR) {
            log.error("relay 内部错误", ex);
        }
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        response.setStatusCode(HttpStatusCode.valueOf(ec.getHttpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ObjectNode err = JsonUtils.mapper().createObjectNode();
        ObjectNode body = err.putObject("error");
        body.put("message", detail);
        body.put("type", ec.getCode());
        body.put("code", ec.getCode());
        byte[] bytes = JsonUtils.toJson(err).getBytes(StandardCharsets.UTF_8);
        DataBuffer buf = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buf));
    }

    // ===== 私有助手 =====

    /**
     * 流式回写 + 断连 drain（资金安全）。
     * <p>把「读上游」与「写客户端」解耦：上游订阅在独立 {@code boundedElastic} 上跑、不挂在 {@code response.writeWith}
     * 取消链上，经 {@link Sinks.Many}（unicast）把转码/透传后的字节镜像给下行。客户端断连只取消下行写、
     * <b>不取消上行读</b>——继续 drain 上游至 complete/error/idle 超时后才结算，用真实满 usage 多退少补
     * （修复「客户端断连→上游被级联取消→usage 收不全→少计费」漏洞）。
     * <p>结算只触发一次（{@code drained} CAS + {@link RelayContext#tryStartSettle()} 双保险）；上游静默超
     * {@link #STREAM_DRAIN_IDLE_TIMEOUT_SEC} 经 {@code timeout} 放弃 drain 防线程占用。并发槽在 {@code writeBack}
     * 末 {@code doFinally} 随客户端连接结束即释放（drain 在槽外进行，不占槽）。
     * <p>在途上游订阅另受 {@link #DRAIN_PERMITS} 全局封顶（{@link #MAX_CONCURRENT_DRAINS}）：满载则不启动订阅、
     * 立即取消上游释放连接并按已采结算（背压泄流），防大量中途断连冲击慢上游堆爆线程/连接。
     *
     * @param transform       上游字节块 → 下发客户端字节（同时以副作用累计 usage/捕获；返回空数组则不下发）
     * @param tail            上游正常 complete 时补发的尾包（如 OpenAI {@code [DONE]}+usage；{@code null}=透传无尾包）
     * @param onDrainComplete 上游 drain 完成（complete/error/timeout）回调：storeCapture + settleAsync
     */
    private Mono<Void> streamWithDrain(ServerHttpResponse response, ClientResponse clientResponse, RelayContext ctx,
                                       Function<byte[], byte[]> transform,
                                       Supplier<byte[]> tail,
                                       Runnable onDrainComplete) {
        DataBufferFactory ff = response.bufferFactory();
        // 有界缓冲（MIRROR_BUFFER_CHUNKS）：慢客户端不再无界堆积；满则 tryEmitNext 回 FAIL_OVERFLOW（下方判慢客户端中止）
        Sinks.Many<byte[]> mirror = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<byte[]>get(MIRROR_BUFFER_CHUNKS).get());
        AtomicBoolean drained = new AtomicBoolean(false);
        Runnable settleOnce = () -> {
            if (drained.compareAndSet(false, true)) {
                onDrainComplete.run();
            }
        };
        // drain 并发闸：满载（{@link #MAX_CONCURRENT_DRAINS}）则不再启动上游订阅，立即取消上游释放连接，
        // 按已采（无）结算以归还预扣——避免大量中途断连冲击慢上游堆爆 boundedElastic 线程 + Netty 连接。
        if (!DRAIN_PERMITS.tryAcquire()) {
            log.warn("上游 drain 并发达上限 {}，放弃续读并取消上游连接 requestId={}",
                    MAX_CONCURRENT_DRAINS, ctx.getRequestId());
            ctx.setStreamAborted(true);
            // 🔴 drain-and-release：subscribe(release) 保证竞争窗口内已 emit 的 DataBuffer 必被释放（防堆外泄漏）；
            // 取消订阅仍会释放连接（原 subscribe().dispose() 会漏掉 subscribe 与 dispose 间已 emit 的 buffer）。
            clientResponse.bodyToFlux(DataBuffer.class).subscribe(DataBufferUtils::release);
            settleOnce.run();
            mirror.tryEmitComplete();
        } else {
            // 上游订阅：独立调度 + idle 超时，脱离下行取消链 → 客户端断连不波及 usage 累计；终态 doFinally 归还闸权
            clientResponse.bodyToFlux(DataBuffer.class)
                    .timeout(Duration.ofSeconds(STREAM_DRAIN_IDLE_TIMEOUT_SEC))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(sig -> DRAIN_PERMITS.release())
                    .subscribe(
                            buf -> {
                                markFirstByte(ctx);
                                byte[] out = transform.apply(toBytes(buf));
                                if (out != null && out.length > 0) {
                                    // 客户端已断时返回 FAIL_CANCELLED 静默丢弃，usage 已在 transform 累计
                                    Sinks.EmitResult emit = mirror.tryEmitNext(out);
                                    if (emit == Sinks.EmitResult.FAIL_OVERFLOW) {
                                        // 慢客户端：镜像缓冲满 → 中止下行（客户端拿到截断流），但继续 drain 上游保计费
                                        ctx.setStreamAborted(true);
                                        mirror.tryEmitComplete();
                                    }
                                }
                            },
                            err -> {
                                ctx.setStreamAborted(true);   // 上游中途断/超时：按已采结算（满 usage 计实采·零则 prompt-only 兜底）
                                settleOnce.run();
                                mirror.tryEmitComplete();
                            },
                            () -> {
                                if (tail != null) {
                                    byte[] t = tail.get();
                                    if (t != null && t.length > 0) {
                                        mirror.tryEmitNext(t);
                                    }
                                }
                                settleOnce.run();
                                mirror.tryEmitComplete();
                            });
        }
        Flux<DataBuffer> body = mirror.asFlux()
                .map(ff::wrap)
                .doOnCancel(() -> ctx.setStreamAborted(true));   // 客户端断连：仅标记，上游 drain 不受影响
        return response.writeWith(body);
    }

    private byte[] maybeConvertResponse(RelayContext ctx, byte[] bytes) {
        if (!ctx.isCanonicalConvert() || bytes.length == 0) {
            return bytes;
        }
        try {
            return canonicalConverter.convertResponse(bytes, ctx.getUpstreamFormat(), ctx.getInboundFormat());
        } catch (Exception e) {
            log.warn("Canonical 响应互转失败，原样返回 requestId={}: {}", ctx.getRequestId(), e.getMessage());
            return bytes;
        }
    }

    /** 上游响应首字节标记（三回写分支首块共用）：回填 ctx TTFT + 活跃注册表转 streaming。 */
    private void markFirstByte(RelayContext ctx) {
        if (ctx.getTtftMs() == null) {
            long ttft = (System.nanoTime() - ctx.getStartNanos()) / 1_000_000L;
            ctx.setTtftMs(ttft);
            activeRegistry.markStreaming(ctx.getRequestId(), ttft, ctx.getUpstreamStatus());
        }
    }

    private static byte[] toBytes(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        DataBufferUtils.release(buf);
        return bytes;
    }

    /** 流式捕获追加（原始字节·超限丢弃尾部·null=未启用采集零开销）。 */
    private static void appendCapture(ByteArrayOutputStream capture, byte[] chunk) {
        if (capture == null || chunk == null || capture.size() >= CAPTURE_MAX) {
            return;
        }
        int room = CAPTURE_MAX - capture.size();
        capture.write(chunk, 0, Math.min(chunk.length, room));
    }

    /** 流末把聚合的响应字节挂到上下文（settle→publishRequestLog→bodyCapture 读取）。 */
    private static void storeCapture(RelayContext ctx, ByteArrayOutputStream capture) {
        if (capture != null && capture.size() > 0) {
            ctx.setResponseCapture(capture.toByteArray());
        }
    }
}
