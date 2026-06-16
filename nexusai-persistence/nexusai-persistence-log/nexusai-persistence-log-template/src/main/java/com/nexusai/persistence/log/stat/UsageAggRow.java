package com.nexusai.persistence.log.stat;

import java.math.BigDecimal;

/**
 * 用量聚合行（使用记录页「用量分析」面板）：
 * 按维度（model/workspace_id/channel_id/api_format·白名单）分组的请求量/成功量/token 三态/USD 成本/平均耗时。
 * 成功率、错误数、缓存命中率等派生值由上层 Java 端计算。
 *
 * @param key           分组键（维度列值的字符串形态·null 组已被驱动排除）
 * @param requests      请求数
 * @param success       成功数（http_status &lt; 400）
 * @param promptTokens  输入 token 合计
 * @param completionTokens 输出 token 合计
 * @param cacheTokens   缓存 token 合计
 * @param totalTokens   总 token 合计
 * @param costUsd       扣减 USD 合计
 * @param avgDurationMs 平均耗时（ms·无样本为 null）
 * @author nexusai
 */
public record UsageAggRow(String key, long requests, long success,
                          long promptTokens, long completionTokens, long cacheTokens,
                          long totalTokens, BigDecimal costUsd, Long avgDurationMs) {
}
