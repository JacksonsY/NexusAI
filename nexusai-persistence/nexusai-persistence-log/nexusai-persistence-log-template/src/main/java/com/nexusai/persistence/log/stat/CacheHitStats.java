package com.nexusai.persistence.log.stat;

/**
 * 缓存命中聚合（缓存监控·命中分析）：窗口内总请求/含缓存命中请求/prompt token 总量/缓存命中 token 总量。
 *
 * @author nexusai
 */
public record CacheHitStats(long totalRequests, long requestsWithHit, long promptTokens, long cacheTokens,
                            long cacheCreationTokens) {

    public static final CacheHitStats EMPTY = new CacheHitStats(0L, 0L, 0L, 0L, 0L);
}
