package com.nexusai.control.domain.monitor.cache.vo;

import java.math.BigDecimal;

/**
 * 缓存命中分析（缓存监控·TTL 分析头部概览·USD 口径）。
 * <p>字段名/顺序/类型与前端 {@code NexusAI.CacheHitAnalysis} 及原 Map 构造顺序严格对齐。
 *
 * @author nexusai
 */
public record CacheHitAnalysisVo(
        int analysisPeriodHours,
        long totalRequests,
        long requestsWithCacheHit,
        double requestCacheHitRate,
        long totalPromptTokens,
        long totalCacheReadTokens,
        double tokenCacheHitRate,
        long totalCacheCreationTokens,
        BigDecimal cacheCreationCostUsd,
        BigDecimal cacheReadCostUsd,
        BigDecimal estimatedSavingsUsd) {
}
