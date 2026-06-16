package com.nexusai.control.domain.project.usage.vo;

import java.math.BigDecimal;

/**
 * 用量维度聚合行（用量分析面板·aggregation/stats）。
 * <p>字段名/类型与前端 {@code NexusAI.UsageAggItem} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record UsageAggItemVo(
        String key,
        String label,
        long requests,
        long success,
        long errorCount,
        double successRate,
        long promptTokens,
        long completionTokens,
        long cacheTokens,
        long totalTokens,
        BigDecimal costUsd,
        Long avgDurationMs,
        double cacheHitRate) {
}
