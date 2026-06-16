package com.nexusai.control.domain.routing.model.vo;

/**
 * 链路预览 · 渠道密钥行（运行时健康分/熔断态）。
 * <p>对齐前端 {@code NexusAI.ModelRoutingKey}：{@code latencyEwmaMs}/{@code blockedUntil} 为 long
 * （经全局 Jackson 序列化为字符串·前端 {@code number | string} 兼容）。
 *
 * @author nexusai
 */
public record ModelRoutingKeyVo(
        Long keyId,
        String masked,
        Integer status,
        Integer priority,
        Integer weight,
        int healthScore,
        long latencyEwmaMs,
        boolean schedulable,
        long blockedUntil) {
}
