package com.nexusai.relay.settle;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.billing.meter.UsageMeter;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.limit.RateLimiterService;
import com.nexusai.relay.RelayFormats;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.usage.Usage;
import com.nexusai.relay.usage.UsageReporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * 结算协调（资金路径）：流末按真实 usage 多退少补（freeze≥settle 不变式）
 * + TPM 真实 token 累加 + 结算后遥测扇出（{@link UsageReporter}）+ R2 消耗回吐响应头。
 * <p>单次门闩（ctx.tryStartSettle）保证 retry/异常/重复信号下只结算一次；settle 自带有界重试，
 * 仍失败落 COST_UNSETTLED CRITICAL 标记供对账——绝不静默吞掉资金事件。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementService {

    /** R2 消耗回吐响应头（项目制·流式以查询端点为准） */
    /** 2xx 但 usage 丢失时的保守补全估算（防白嫖·偏保守） */
    private static final int EST_FALLBACK_COMPLETION = 512;
    /** M1 资金安全：结算有界重试次数与退避基数（settle 抖动/异常下不丢 refund） */
    private static final int SETTLE_MAX_ATTEMPTS = 3;
    private static final long SETTLE_RETRY_BACKOFF_MS = 50L;
    /**
     * 内联结算幂等去重标记 TTL（秒）：须覆盖「内联重试窗口（亚秒）+ outbox 首次重放窗口」。
     * 与 outbox 共用 {@code "settle:"+requestId} 去重 id——Redis 已应用但 ACK 丢失时，重试/重放命中标记整体 no-op，
     * 杜绝裸 INCRBY 重试导致的双扣/双退。短 TTL 自动回收（非 7 天·避免每请求一个长命 key 撑爆 Redis）。
     */
    private static final long INLINE_SETTLE_DEDUP_TTL_SECONDS = 600L;

    private final QuotaLedger quotaLedger;
    private final UsageMeter usageMeter;
    private final RateLimiterService rateLimiter;
    private final UsageReporter reporter;
    private final SettlementOutboxService outboxService;

    /** 结算口径（usage 兜底链终值 + micro-USD·doSettle 与 R2 响应头回吐共用同一纯函数）。 */
    public record Settlement(Usage effective, long usdMicro, String usageSource) {
    }

    /** 流末结算（boundedElastic·fire-and-forget）：多退少补 + 计量 + UsageEvent + 请求日志。 */
    public void settleAsync(RelayContext ctx, Supplier<Usage> usageSupplier) {
        Mono.fromRunnable(() -> doSettle(ctx, usageSupplier.get()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> log.error("结算失败 requestId={}", ctx.getRequestId(), e));
    }

    /** 结算口径纯函数（幂等无副作用）：失败不计费；2xx usage=0 走防白嫖兜底链；计量异常归零。 */
    public Settlement computeSettlement(RelayContext ctx, Usage usage) {
        boolean success = ctx.getUpstreamStatus() == null || ctx.getUpstreamStatus() < 400;
        String modality = RelayFormats.modalityOf(ctx.getPath());
        boolean hasCompletion = "chat".equals(modality);   // embedding/rerank/image 无补全
        Usage effective = usage;
        String usageSource = "parsed";
        long actualUsdAmount;
        try {
            if (!success) {
                // 非 2xx（错误体已回客户端）→ 不计费，全额退（usage 通常为 0）
                actualUsdAmount = 0;
                usageSource = "failed";
            } else {
                // 🔴 防白嫖：2xx 已吐字节但 usage=0（上游不回/被剥离/挤出尾窗）→ 绝不结算成 0
                if (usage.isZero() && "image".equals(modality)) {
                    usageSource = "image-fallback";   // image：按 perImageFee/perRequestFee 计，token 0
                } else if (usage.isZero()) {
                    int estPrompt = ctx.getBody().length / 4;
                    if (ctx.isStreamAborted()) {
                        // 流被客户端取消/中途断：用户未收到完整输出 → 不按上界估补全，仅计 prompt
                        effective = new Usage(estPrompt, 0, estPrompt, 0);
                        usageSource = "client-abort";
                    } else {
                        int estCompletion = hasCompletion ? EST_FALLBACK_COMPLETION : 0;   // 非 chat 无补全
                        effective = new Usage(estPrompt, estCompletion, estPrompt + estCompletion, 0);
                        usageSource = "estimated";
                    }
                }
                int images = "image".equals(modality) ? ctx.getImageCount() : 0;   // 张数 = gate 解析的 n（freeze 同源·n>1 不少计费）
                actualUsdAmount = usageMeter.microUsdFor(ctx.getUsdRate(),
                        effective.promptTokens(), effective.completionTokens(),
                        effective.cachedTokens(), effective.cacheCreationTokens(), images);
                if ("estimated".equals(usageSource)) {
                    // 🔴 防系统性少计费：2xx 成功但 usage 被上游剥离时，固定 512 补全会把长输出严重低估。
                    // 改按 freeze 预留额（客户声明的 max_tokens 上界）保守结算 → 至少不少收；
                    // freeze≥settle 不变式仍成立（floor 至 frozen → refund≤0），成本经 R2 响应头透明可见。
                    actualUsdAmount = Math.max(actualUsdAmount, ctx.getFrozenUsdAmount());
                }
            }
        } catch (Exception e) {
            // 计量异常绝不能让 freeze 滞留（over-charge 无对账）→ 全额退 + 标记
            log.error("计量异常，全额退 freeze requestId={}", ctx.getRequestId(), e);
            actualUsdAmount = 0;
            usageSource = "settle-error";
        }
        return new Settlement(effective, actualUsdAmount, usageSource);
    }

    private void doSettle(RelayContext ctx, Usage usage) {
        // 单次门闩：retry/异常/重复信号下 doSettle 只执行一次
        if (!ctx.tryStartSettle()) {
            return;
        }
        Settlement settlement = computeSettlement(ctx, usage);
        Usage effective = settlement.effective();
        long actualUsdAmount = settlement.usdMicro();
        String usageSource = settlement.usageSource();

        int totalTokens = effective.promptTokens() + effective.completionTokens();
        long refund = ctx.getFrozenUsdAmount() - actualUsdAmount;   // >0 退 / <0 补
        // 🔴 M1 资金安全：settle 必须在有界重试/补偿内——boundedElastic 抛异常/Redis 抖动绝不能让 freeze 永久滞留
        settleWithRetry(ctx, refund);
        // 🔴 M2/M8 防污染：TPM 限流窗口只累加「真实解析」的 token；estimated/image-fallback/
        // client-abort/failed/settle-error 的假 token 绝不计入限流窗口
        if ("parsed".equals(usageSource) && totalTokens > 0) {
            try {
                // 三层 TPM 窗口同步累加（仅真实解析 token·与 RateLimitFilter 同维度 key：token/project/workspace）
                rateLimiter.recordTokens("token:" + ctx.getToken().getId(), totalTokens);
                String pc = ctx.getToken().getProjectCode();
                if (pc != null && !pc.isBlank()) {
                    rateLimiter.recordTokens("project:" + pc, totalTokens);
                }
                Long wsId = ctx.getToken().getWorkspaceId();
                if (wsId != null) {
                    rateLimiter.recordTokens("workspace:" + wsId, totalTokens);
                }
            } catch (Exception e) {
                log.warn("TPM 窗口累加失败（不影响结算）requestId={}", ctx.getRequestId(), e);
            }
        }

        reporter.writeUsageLog(ctx, effective, totalTokens, actualUsdAmount);
        reporter.publishUsageEvent(ctx, effective, totalTokens, actualUsdAmount, usageSource);
        reporter.publishRequestLog(ctx, effective, totalTokens, actualUsdAmount, usageSource);
        boolean success = ctx.getUpstreamStatus() == null || ctx.getUpstreamStatus() < 400;
        reporter.attachAffinityMeta(ctx, success, RelayFormats.modalityOf(ctx.getPath()), effective);
    }

    /**
     * R2 消耗回吐（仅非流式——流式响应头在首包前已提交，末包内联归 P1，先以
     * {@code /v1/generation/{requestId}} 查询端点兜底）。settle 尚未执行：
     * 余额理论终值 = 项目池当前值（已扣 frozen）+ frozen − used。构建失败不影响主链。
     */
    public void attachCostHeaders(ServerHttpResponse response, RelayContext ctx, Usage parsed) {
        try {
            Settlement s = computeSettlement(ctx, parsed);
            response.getHeaders().set(GatewayConstants.COST_USD_HEADER, UsdAmount.format(s.usdMicro()));
            // Balance 口径取调用方的"钱包"：token 挂 workspace（B2C）= 工作区钱包；否则（B2B）= 项目池
            long balanceNow = ctx.getToken().getWorkspaceId() != null
                    ? quotaLedger.workspaceBalance(ctx.getToken().getProjectCode(), ctx.getToken().getWorkspaceId())
                    : quotaLedger.projectBalance(ctx.getToken().getProjectCode());
            long remaining = balanceNow + ctx.getFrozenUsdAmount() - s.usdMicro();
            response.getHeaders().set(GatewayConstants.BALANCE_USD_HEADER,
                    QuotaLedger.unlimitedBalance(remaining) ? "unlimited" : UsdAmount.format(Math.max(remaining, 0)));
        } catch (Exception e) {
            log.debug("cost 回吐头构建失败（不影响主链）requestId={}", ctx.getRequestId());
        }
    }

    /**
     * 结算有界重试（M1 资金安全·freeze≥settle 不滞留）：settle 抖动/异常下重试 {@value #SETTLE_MAX_ATTEMPTS} 次，
     * 仍失败则落 {@code COST_UNSETTLED} CRITICAL 标记（供 ops 告警/离线对账）。
     */
    private boolean settleWithRetry(RelayContext ctx, long refund) {
        Exception last = null;
        for (int attempt = 1; attempt <= SETTLE_MAX_ATTEMPTS; attempt++) {
            try {
                // 🔴 幂等结算（dedup="settle:"+requestId）：裸 settle 的 INCRBY 非幂等，Redis 已应用但 ACK 丢失时
                // 重试会二次扣/退；改 settleOnce 经 SET NX + incrby 同 Lua 原子，重试命中标记整体 no-op。
                quotaLedger.settleOnce(ctx.getToken(), refund, settleDedupId(ctx), INLINE_SETTLE_DEDUP_TTL_SECONDS);
                return true;
            } catch (Exception e) {
                last = e;
                if (attempt == SETTLE_MAX_ATTEMPTS) {
                    log.error("🔴COST_UNSETTLED requestId={} tokenId={} refund={} 结算 {} 次仍失败，需人工对账（freeze 未释放）",
                            ctx.getRequestId(), ctx.getToken() == null ? null : ctx.getToken().getId(),
                            refund, SETTLE_MAX_ATTEMPTS, e);
                    outboxService.record(ctx, refund, e);
                    return false;
                }
                try {
                    Thread.sleep(SETTLE_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    outboxService.record(ctx, refund, ie);
                    return false;
                }
            }
        }
        outboxService.record(ctx, refund, last);
        return false;
    }

    /**
     * 终态失败全额退 freeze（选号失败/重试耗尽/缓存命中短路/上游 body 中途断）。
     * <p>🔴 与 {@link #doSettle} 共用 {@code tryStartSettle} 单次门闩：settle-once 不变式由门闩本身保证（非仅靠调用
     * 时序），任一路径先到先结算、另一路径自动 no-op——杜绝「doSettle 多退少补」与「refundAll 全额退」对同一 ctx 双退。
     */
    public void refundAll(RelayContext ctx) {
        if (!ctx.tryStartSettle()) {
            return;   // 已结算 → 不重复退
        }
        try {
            if (ctx.getToken() != null && ctx.getFrozenUsdAmount() > 0) {
                // 幂等退款（与 doSettle/outbox 共用 "settle:"+requestId）：单发退款若 ACK 丢失被 outbox 重放也不双退。
                quotaLedger.settleOnce(ctx.getToken(), ctx.getFrozenUsdAmount(),
                        settleDedupId(ctx), INLINE_SETTLE_DEDUP_TTL_SECONDS);
            }
        } catch (Exception e) {
            log.error("失败退款异常 requestId={}", ctx.getRequestId(), e);
            outboxService.record(ctx, ctx.getFrozenUsdAmount(), e);
        }
    }

    /** 结算幂等去重 id（内联结算/退款/outbox 重放三路共用·恰好一次的真理源）。 */
    static String settleDedupId(RelayContext ctx) {
        return "settle:" + ctx.getRequestId();
    }
}
