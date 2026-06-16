package com.nexusai.control.domain.monitor.cache.vo;

/**
 * 会话亲和条目（缓存监控·亲和性列表）。
 * <p>字段名/顺序/类型与前端 {@code NexusAI.AffinityItem} 及原 Map 构造顺序严格对齐。
 *
 * @author nexusai
 */
public record AffinityItemVo(
        String cacheKey,
        String affinityKey,
        long channelId,
        String channelName,
        long keyId,
        String projectCode,
        String projectName,
        String model,
        String apiFormat,
        String clientFamily,
        String keySource,
        long cachedTokens,
        long createdAt,
        long expireAt,
        long requestCount) {
}
