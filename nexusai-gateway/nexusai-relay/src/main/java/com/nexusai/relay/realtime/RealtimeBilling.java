package com.nexusai.relay.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.billing.config.ModelPriceService;
import com.nexusai.billing.event.UsageEventPublisher;
import com.nexusai.billing.meter.UsageMeter;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.contract.usage.UsageEvent;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.router.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Realtime WebSocket 计费（对齐 chat 路径强闸门·post-charge per response.done）。
 * <p>Realtime 会话长跑、用量按「每个 response」离散累加，预冻结整段额度不现实，故采用：
 * <ol>
 *   <li><b>连接准入</b>：查钱包余额&gt;0（unlimited 放行·空钱包拒）——杜绝零余额账户白嫖 realtime 音频流量；</li>
 *   <li><b>逐 response.done 后付费</b>：解析上游 {@code response.done.usage} → 计价 → {@link QuotaLedger#settle} 负额扣费
 *       + 发 {@link UsageEvent}；扣费 Redis 调用 offload 到 boundedElastic 不阻塞 Netty；</li>
 *   <li><b>耗尽即拆桥</b>：扣费后余额&le;0 关闭客户端会话（把单会话超扣窗口收敛到约一个 response 的成本）。</li>
 * </ol>
 * <p>注：realtime 音频 token 暂按文本价近似计（{@link com.nexusai.billing.config.UsdRate} 未建模音频单价）——偏保守、闭白嫖口子，精确音频价留后续。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeBilling {

    private final QuotaLedger quotaLedger;
    private final UsageMeter usageMeter;
    private final ModelPriceService prices;
    private final UsageEventPublisher usageEvents;

    /** 拒绝未建价模型（默认开·与 HTTP {@code QuotaGateFilter}/任务路径同口径·防 realtime 未建价模型免费流式白嫖）。 */
    @Value("${nexusai.billing.deny-unpriced-model:true}")
    private boolean denyUnpricedModel;

    /**
     * realtime 后付费扣账幂等去重 TTL（秒）：覆盖单帧内重试窗口。裸 {@code settle} 的 INCRBY 非幂等——
     * Redis 已应用但 ACK 丢失时重试会双扣，故经 {@code settleOnce}(SET NX + incrby 同 Lua) 去重。
     */
    private static final long REALTIME_SETTLE_DEDUP_TTL_SECONDS = 600L;

    /** 余额耗尽主动拆桥的 WebSocket 关闭码（4000-4999 应用自定义区·客户端据此辨「额度用尽」非协议错误）。 */
    private static final int CLOSE_CODE_QUOTA_EXHAUSTED = 4003;

    /**
     * 未建价闸门（与 HTTP {@code QuotaGateFilter} / {@code TaskRelayService} 同口径）：deny-unpriced 开 且
     * 模型在 nai_model 无价（{@link com.nexusai.billing.config.UsdRate#isUnknown()}）→ 拒绝准入，杜绝
     * 「未建价模型免费且无限流」的 realtime 音频白嫖口子（精确价由运营在 nai_model 补全）。
     *
     * @return true=可计费可准入；false=未建价且 deny-unpriced 开，应拒连
     */
    public boolean priced(String model) {
        return !denyUnpricedModel || !prices.priceFor(model).isUnknown();
    }

    /**
     * 连接准入：三级钱包均有余额（项目池 + 工作区钱包[挂 workspace 时] + Key 子限额[有子限额时]·unlimited 放行）。
     * 与 HTTP 强闸门同口径——任一适用层不足即拒，杜绝「工作区有钱但项目池已空 / 子限额已尽」之类的单池漏判白嫖。
     * 查询异常保守拒（防异常放行白嫖）。
     */
    public boolean hasBalance(NaiToken token) {
        try {
            if (!positive(quotaLedger.projectBalance(token.getProjectCode()))) {
                return false;
            }
            if (token.getWorkspaceId() != null
                    && !positive(quotaLedger.workspaceBalance(token.getProjectCode(), token.getWorkspaceId()))) {
                return false;
            }
            return positive(quotaLedger.tokenSublimitBalance(token));
        } catch (Exception e) {
            log.warn("realtime 余额查询失败，保守拒连 tokenId={}: {}", token.getId(), e.toString());
            return false;
        }
    }

    /**
     * 解析一帧上游→客户端文本帧；若为 response.done 含 usage → 异步计价扣费 + 发 UsageEvent + 耗尽拆桥。
     * 同步只做廉价 JSON 解析（不阻塞 Netty 事件循环）；扣费/余额查询的 Redis 调用 offload 到 boundedElastic。
     */
    public void onUpstreamText(String frame, NaiToken token, String model, RouteResult route, WebSocketSession client) {
        if (frame == null || !frame.contains("response.done")) {
            return;
        }
        JsonNode usage;
        String responseId;
        try {
            JsonNode n = JsonUtils.readTree(frame);
            if (!"response.done".equals(n.path("type").asText())) {
                return;
            }
            JsonNode resp = n.path("response");
            usage = resp.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return;
            }
            responseId = resp.path("id").asText(null);   // 幂等去重锚（response.done 唯一 id·缺失则 charge 内退 UUID）
        } catch (Exception e) {
            return;   // 非完整/非 JSON 帧
        }
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int cached = usage.path("input_token_details").path("cached_tokens").asInt(0);
        if (input <= 0 && output <= 0) {
            return;
        }
        String rid = responseId;
        Mono.fromRunnable(() -> {
            charge(token, model, route, input, output, cached, rid);
            if (exhausted(token)) {
                client.close(new CloseStatus(CLOSE_CODE_QUOTA_EXHAUSTED, "余额耗尽")).subscribe();
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> log.error("realtime 计费失败 tokenId={}", token.getId(), e));
    }

    /** 扣费有界重试次数与退避基数（对齐 HTTP 路径 SettlementService·Redis 抖动下不丢扣费）。 */
    private static final int CHARGE_MAX_ATTEMPTS = 3;
    private static final long CHARGE_RETRY_BACKOFF_MS = 50L;

    private void charge(NaiToken token, String model, RouteResult route, int input, int output, int cached,
                        String responseId) {
        long cost = usageMeter.microUsdFor(prices.priceFor(model), input, output, cached, 0, 0);
        if (cost > 0) {
            // 幂等去重 id：每个 response.done 唯一（response.id·缺失退每帧唯一 UUID），同帧重试命中标记整体 no-op 杜绝双扣
            String dedupId = "rt:" + token.getId() + ":"
                    + (responseId != null && !responseId.isBlank() ? responseId : UUID.randomUUID());
            chargeWithRetry(token, cost, dedupId);   // 负 refund = post-charge 扣费（多退少补的「少补」单向）
        } else {
            // 已建价的零价模型（DEFAULT 显式 0）或零用量：按 0 计费但仍发 UsageEvent 留审计。
            // 注：未建价（UNKNOWN）模型已在握手准入由 priced() 拒连，不会走到这里（deny-unpriced 默认开）。
            log.warn("realtime 零价/零用量，本次按 0 计费但仍记账 model={} tokenId={} input={} output={}",
                    model, token.getId(), input, output);
        }
        int total = input + output;
        try {
            usageEvents.publish(new UsageEvent(
                    "rt-" + token.getId() + "-" + System.nanoTime(),
                    token.getProjectCode(), token.getWorkspaceId(), model,
                    route == null ? null : route.channel().getId(),
                    route == null ? null : route.key().getId(),
                    input, output, cached, 0, total, UsdAmount.fromMicros(cost), "realtime"));
        } catch (Exception e) {
            log.warn("realtime UsageEvent 发布失败 tokenId={}: {}", token.getId(), e.toString());
        }
    }

    /** 扣费有界重试（Redis 抖动/异常下不丢 realtime 后付费扣费）；耗尽仍失败落 CRITICAL 标记供对账。 */
    private void chargeWithRetry(NaiToken token, long cost, String dedupId) {
        for (int attempt = 1; attempt <= CHARGE_MAX_ATTEMPTS; attempt++) {
            try {
                // 🔴 幂等扣费：裸 settle 的 INCRBY 非幂等（Redis 已应用但 ACK 丢失时重试双扣）→ settleOnce
                // 经 SET NX + incrby 同 Lua 原子，重试/重投命中去重标记整体 no-op（对齐 SettlementService.settleWithRetry）。
                quotaLedger.settleOnce(token, -cost, dedupId, REALTIME_SETTLE_DEDUP_TTL_SECONDS);
                return;
            } catch (Exception e) {
                if (attempt == CHARGE_MAX_ATTEMPTS) {
                    log.error("🔴COST_UNSETTLED(realtime) tokenId={} cost={} 扣费 {} 次仍失败，需人工对账",
                            token.getId(), cost, CHARGE_MAX_ATTEMPTS, e);
                    return;
                }
                try {
                    Thread.sleep(CHARGE_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("🔴COST_UNSETTLED(realtime) 扣费重试被中断 tokenId={} cost={}", token.getId(), cost);
                    return;
                }
            }
        }
    }

    private boolean exhausted(NaiToken token) {
        try {
            // 任一适用层耗尽即视为耗尽（与 hasBalance 同口径·逐 response 后检查，把超扣窗口收敛到约一个 response）
            return !positive(quotaLedger.projectBalance(token.getProjectCode()))
                    || (token.getWorkspaceId() != null
                        && !positive(quotaLedger.workspaceBalance(token.getProjectCode(), token.getWorkspaceId())))
                    || !positive(quotaLedger.tokenSublimitBalance(token));
        } catch (Exception e) {
            // 余额查询抖动：不在本次 response 后拆桥（避免 Redis 瞬时异常误杀付费会话）；下一 response 复检兜底
            log.debug("realtime 耗尽检查失败，本次不拆桥 tokenId={}: {}", token.getId(), e.toString());
            return false;
        }
    }

    /** 单层余额是否充足（unlimited 哨兵放行 / 正额放行）。 */
    private static boolean positive(long balance) {
        return QuotaLedger.unlimitedBalance(balance) || balance > 0;
    }
}
