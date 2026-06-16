package com.nexusai.control.domain.monitor.observe.vo;

/**
 * Dashboard 每日请求时序点（去钱化·面积图·请求量/Token 双轴）。
 * <p>字段名/类型与前端 {@code NexusAI.DailyPoint} 严格对齐。
 *
 * @author nexusai
 */
public record DailyPointVo(
        String date,
        long total,
        long success,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        long totalTokens) {
}
