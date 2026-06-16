package com.nexusai.persistence.log.stat;

/**
 * 错误趋势分桶行（运维监控页·全部/上游/限流三线）：
 * 把 [since, until] 均分 segments 段，按段聚合错误三口径。
 *
 * @param segmentIdx     段号 [0, segments)
 * @param errors         全部错误数（http_status ≥ 400）
 * @param upstreamErrors 上游错误数（http_status ≥ 500·自然排除 429 限流）
 * @param limitedErrors  限流错误数（http_status = 429）
 * @author nexusai
 */
public record ErrorTrendBucket(int segmentIdx, long errors, long upstreamErrors, long limitedErrors) {
}
