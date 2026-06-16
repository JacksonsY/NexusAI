package com.nexusai.persistence.log.stat;

/**
 * 渠道性能聚合行（性能分析页）：按渠道（或总体）统计
 * 量/成功/吞吐计数器/TTFT 与响应样本和/慢请求数 + P90/P99 分位（响应与首字双指标）。
 * <p>分位三方言：PG {@code percentile_cont FILTER} / CH {@code quantileIf} 原生，MySQL
 * {@code PERCENT_RANK()} 窗口近似（对齐既有 {@code selectDurationPercentiles} 口径）；
 * 样本不足时分位可为 null（样本数过少不给值，由 driver 自然产生）。
 *
 * @param key             渠道 id 串（总体行为 null）
 * @param total           请求总量
 * @param success         成功量（http_status &lt; 400）
 * @param tokensCount     成功行输出 token 合计（TPS 分子）
 * @param genMsSum        成功行生成耗时合计 ms（TPS 分母）
 * @param ttftSumMs       TTFT 合计 ms（仅 ttft&gt;0 行）
 * @param ttftCount       TTFT 样本数（=首字样本覆盖分子）
 * @param latencySumMs    响应耗时合计 ms（仅 duration&gt;0 行）
 * @param responseSamples 响应耗时样本数（=响应样本覆盖分子）
 * @param slowCount       慢请求数（duration ≥ 阈值）
 * @param p90DurationMs   P90 响应 ms
 * @param p99DurationMs   P99 响应 ms
 * @param p90TtftMs       P90 首字 ms
 * @param p99TtftMs       P99 首字 ms
 * @author nexusai
 */
public record ChannelPerfStat(String key, long total, long success,
                              long tokensCount, long genMsSum,
                              long ttftSumMs, long ttftCount,
                              long latencySumMs, long responseSamples, long slowCount,
                              Long p90DurationMs, Long p99DurationMs,
                              Long p90TtftMs, Long p99TtftMs) {
}
