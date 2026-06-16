package com.nexusai.relay.filter;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.limit.ConcurrencySlot;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.context.RelayContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

/**
 * 入站 Key 并发槽闸门（{@code nai_token.concurrent_limit}·按 sk- 维度限在途请求数）。
 * <p>RPM/TPM 限的是「单位时间请求/token 数」，挡不住慢请求长尾——少量长跑流式请求即可长期占用大量上游连接而不触发 RPM。
 * 本闸按 sk- 维度硬限<b>在途请求数</b>：占用 {@link ConcurrencySlot} 入站槽（Redis 服务端时钟·跨实例一致），槽满即拒。
 * <p>排限流（40）之后、freeze（70）之前：被本闸拒的请求尚未 freeze，无需退款；占用的槽由编排器在请求终态
 * （{@code RelayOrchestrator}：闸门链中途异常 / pipeline doFinally）统一释放，绝不泄漏。0=不限并发。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class InboundConcurrencyFilter implements RelayFilter {

    private final ConcurrencySlot concurrencySlot;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        NaiToken token = ctx.getToken();
        if (token == null) {
            return;
        }
        int max = token.getConcurrentLimit() == null ? 0 : token.getConcurrentLimit();
        if (max <= 0) {
            return;   // 0=不限并发
        }
        String slotToken = UUID.randomUUID().toString();
        if (!concurrencySlot.tryOccupyInbound(token.getId(), max, slotToken)) {
            throw GatewayException.of(GatewayErrorCode.RATE_LIMITED,
                    "API Key 并发请求数已达上限（concurrent_limit=" + max + "）");
        }
        ctx.setInboundSlotToken(slotToken);   // 终态由编排器释放
    }

    @Override
    public int getOrder() {
        return ORDER_RATE_LIMIT + 5;   // 限流(40) 之后、freeze(70) 之前
    }
}
