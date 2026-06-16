package com.nexusai.persistence.log.stat;

/**
 * 按模型的缓存命中 token 聚合行（缓存监控·命中分析按模型费率折算费用/节省）。
 *
 * @author nexusai
 */
public record ModelCacheRow(String model, long cacheTokens, long cacheCreationTokens) {
}
