package com.nexusai.relay.settle;

import com.nexusai.billing.config.UsdRate;
import com.nexusai.billing.meter.UsageMeter;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.settle.SettlementService.Settlement;
import com.nexusai.relay.usage.Usage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 结算口径纯函数 {@link SettlementService#computeSettlement} 单测（资金路径·此前零覆盖·评审「工程隐患」）：
 * 覆盖 parsed/failed/estimated/client-abort/image-fallback 五条兜底分支与 micro-USD 结构化计价正确性，
 * 锁死「2xx 永不结零防白嫖」「非 2xx 不计费」「断连只计 prompt 不补扣 completion」三条资金铁律。
 */
class SettlementServiceTest {

    // $1/M input（1 token=1 micro-USD）、$2/M output、$0.04/张 image（token 费率 $/M·图按绝对 USD）
    private static final UsdRate RATE = UsdRate.of(
            new BigDecimal("1"), new BigDecimal("2"),
            BigDecimal.ZERO, null, BigDecimal.ZERO, new BigDecimal("0.04"));

    // computeSettlement 仅依赖 usageMeter，其余依赖传 null（纯函数·无 Redis）
    private final SettlementService settlement = new SettlementService(null, new UsageMeter(), null, null, null);

    private RelayContext ctx(String path, Integer status, boolean aborted, int bodyLen) {
        RelayContext ctx = new RelayContext();
        ctx.setUsdRate(RATE);
        ctx.setPath(path);
        ctx.setUpstreamStatus(status);
        ctx.setStreamAborted(aborted);
        ctx.setBody(new byte[bodyLen]);
        return ctx;
    }

    @Test
    void parsedSuccessComputesStructuredCost() {
        Settlement s = settlement.computeSettlement(
                ctx("/v1/chat/completions", 200, false, 0),
                new Usage(1000, 500, 1500, 0, 0));
        assertThat(s.usageSource()).isEqualTo("parsed");
        // input 1000×1micro + output 500×2micro = 2000 micro-USD
        assertThat(s.usdMicro()).isEqualTo(2000L);
    }

    @Test
    void failedUpstreamChargesNothing() {
        Settlement s = settlement.computeSettlement(
                ctx("/v1/chat/completions", 500, false, 4000),
                Usage.ZERO);
        assertThat(s.usageSource()).isEqualTo("failed");
        assertThat(s.usdMicro()).isZero();   // 非 2xx 全额退·不计费
    }

    @Test
    void zeroUsageNonAbortFallsBackToEstimated() {
        // 2xx 但 usage 丢失（上游不回/被剥离）→ 防白嫖兜底：body 400B → estPrompt=100·estCompletion=512
        Settlement s = settlement.computeSettlement(
                ctx("/v1/chat/completions", 200, false, 400),
                Usage.ZERO);
        assertThat(s.usageSource()).isEqualTo("estimated");
        assertThat(s.effective().promptTokens()).isEqualTo(100);
        assertThat(s.effective().completionTokens()).isEqualTo(512);
        assertThat(s.usdMicro()).isEqualTo(1124L);   // 100×1 + 512×2
    }

    @Test
    void clientAbortChargesPromptOnly() {
        // 流被客户端取消：用户未收到完整输出 → 仅计 prompt·不按上界补扣 completion
        Settlement s = settlement.computeSettlement(
                ctx("/v1/chat/completions", 200, true, 400),
                Usage.ZERO);
        assertThat(s.usageSource()).isEqualTo("client-abort");
        assertThat(s.effective().completionTokens()).isZero();
        assertThat(s.usdMicro()).isEqualTo(100L);   // 仅 prompt 100×1
    }

    @Test
    void imageZeroUsageChargesPerImageFee() {
        // image 模态 usage=0 → 按 perImageFee 计（token 0·不走 estimated 文本补全）
        Settlement s = settlement.computeSettlement(
                ctx("/v1/images/generations", 200, false, 50),
                Usage.ZERO);
        assertThat(s.usageSource()).isEqualTo("image-fallback");
        assertThat(s.usdMicro()).isEqualTo(40000L);   // 0.04 USD × 1 张
    }

    // ===== refundAll settle-once 门闩（评审 fix 2/12·锁死「恰好一次」资金不变式） =====

    private static RelayContext refundCtx() {
        RelayContext ctx = new RelayContext();
        NaiToken token = new NaiToken();
        token.setId(1L);
        token.setProjectCode("p");
        ctx.setToken(token);
        ctx.setFrozenUsdAmount(123L);
        return ctx;
    }

    @Test
    void refundAllRefundsExactlyOnceAcrossRepeatCalls() {
        QuotaLedger ledger = mock(QuotaLedger.class);
        SettlementService svc = new SettlementService(ledger, new UsageMeter(), null, null, null);
        RelayContext ctx = refundCtx();

        svc.refundAll(ctx);
        svc.refundAll(ctx);   // 第二次必须被 settle-once 门闩拦截·绝不重复全额退（防双退）

        // 退款经幂等 settleOnce（dedup="settle:"+requestId·防 ACK 丢失被 outbox 重放双退），恰好一次
        verify(ledger, times(1)).settleOnce(eq(ctx.getToken()), eq(123L), any(), anyLong());
    }

    @Test
    void refundAllNoOpsOnceSettleAlreadyStarted() {
        // 模拟「成功路径 doSettle 已抢占门闩」（已按真实 usage 多退少补）后，写客户端出错触发 refundAll：
        // 必须 no-op——否则把已计费的成功请求全额退 = 白嫖（评审 fix 2 HIGH 回归点）。
        QuotaLedger ledger = mock(QuotaLedger.class);
        SettlementService svc = new SettlementService(ledger, new UsageMeter(), null, null, null);
        RelayContext ctx = refundCtx();

        ctx.tryStartSettle();   // doSettle 已占门闩
        svc.refundAll(ctx);

        verify(ledger, never()).settleOnce(any(), anyLong(), any(), anyLong());
    }
}
