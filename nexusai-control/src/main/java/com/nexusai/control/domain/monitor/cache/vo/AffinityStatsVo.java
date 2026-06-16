package com.nexusai.control.domain.monitor.cache.vo;

/**
 * 会话亲和统计（缓存监控 4 统计卡）。
 * <p>字段名/类型与前端 {@code NexusAI.AffinityStats} 严格对齐（计数为 long·全局序列化为字符串）。
 *
 * @author nexusai
 */
public record AffinityStatsVo(
        long activeAffinities,
        long ttlSeconds,
        String storeMode,
        long stickyHits,
        long keySwitches,
        long providerSwitches,
        long invalidations,
        double anchorMinScoreRatio) {
}
