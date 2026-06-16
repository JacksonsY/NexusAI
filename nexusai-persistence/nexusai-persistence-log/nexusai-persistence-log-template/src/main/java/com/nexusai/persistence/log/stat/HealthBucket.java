package com.nexusai.persistence.log.stat;

/**
 * 健康时间线分桶聚合行（健康监控页）：
 * 把 [since, until] 均分 segments 段，SQL 端按（维度 × 段号）聚合请求量/成功量——
 * 日志百万行也只回 {@code segments × 活跃维度数} 行，Java 端按段判色（healthy/warning/unhealthy/unknown）。
 *
 * @param key        一级维度键（channel_id 串或 model 名）
 * @param subKey     二级维度键（渠道×模型查询时为 model 名，单维查询为 null）
 * @param segmentIdx 段号 [0, segments)（落在 until 边界的事件钳到最后一段）
 * @param total      该段请求总量
 * @param success    该段成功量（http_status &lt; 400）
 * @author nexusai
 */
public record HealthBucket(String key, String subKey, int segmentIdx, long total, long success) {
}
