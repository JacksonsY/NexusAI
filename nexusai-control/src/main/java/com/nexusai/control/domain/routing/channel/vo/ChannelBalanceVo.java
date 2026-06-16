package com.nexusai.control.domain.routing.channel.vo;

import java.math.BigDecimal;

/**
 * 渠道余额（余额监控列·上游 OpenAI 兼容计费端点）。
 * <p>对齐前端 {@code NexusAI.ChannelBalance}：{@code ok} 必有，余额三项 + currency 成功时填、失败时为 null，
 * {@code message} 仅失败时有值。金额维持原 Map 持有的 {@link BigDecimal}（round2 后），Jackson 输出数值。
 *
 * @author nexusai
 */
public record ChannelBalanceVo(
        boolean ok,
        BigDecimal totalUsd,
        BigDecimal usedUsd,
        BigDecimal availableUsd,
        String currency,
        String message) {

    /** 成功结果（余额三项 + 币种）。 */
    public static ChannelBalanceVo ok(BigDecimal totalUsd, BigDecimal usedUsd, BigDecimal availableUsd) {
        return new ChannelBalanceVo(true, totalUsd, usedUsd, availableUsd, "USD", null);
    }

    /** 失败结果（仅错误信息）。 */
    public static ChannelBalanceVo fail(String message) {
        return new ChannelBalanceVo(false, null, null, null, null, message);
    }
}
