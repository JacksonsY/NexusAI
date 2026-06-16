package com.nexusai.persistence.log.stat;

/**
 * 按日延迟分位行（性能分析页·分位时序）：
 * 每天一行，响应耗时与首字（TTFT）各取 P50/P90/P99。
 * <p>响应分位只取 {@code duration_ms > 0} 行；TTFT 分位只取 {@code ttft_ms > 0} 行（非流式无首字）。
 * 空日不产生行；样本不足时分位可为 null。
 *
 * @author nexusai
 */
public record PercentileDay(String date,
                            Long p50DurationMs, Long p90DurationMs, Long p99DurationMs,
                            Long p50TtftMs, Long p90TtftMs, Long p99TtftMs) {
}
