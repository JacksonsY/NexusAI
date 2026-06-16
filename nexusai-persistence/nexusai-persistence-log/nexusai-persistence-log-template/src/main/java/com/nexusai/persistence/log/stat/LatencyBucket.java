package com.nexusai.persistence.log.stat;

/**
 * 延迟直方图分桶行（运维监控页）：
 * 按固定区间（{@link PerfSql#LATENCY_BUCKET_ROW}）分桶计数，桶标签即区间串。
 *
 * @param bucket 区间标签（如 "0-100ms"）
 * @param count  该区间请求数（仅 duration &gt; 0 行）
 * @author nexusai
 */
public record LatencyBucket(String bucket, long count) {
}
