package com.nexusai.persistence.log.stat;

/**
 * 维度性能聚合（Dashboard「性能分析·最快榜」）：
 * 分组键 + 平均 TTFT/时延（ms）+ 请求量 + 输出 token 总量 + 吞吐（tokens/second）。
 * <p>吞吐口径：{@code Σ输出tokens × 1000 / Σ生成耗时}，流式请求的生成耗时剔除 TTFT 段
 * （{@code duration - ttft}，下钳 0），非流式取全程；仅统计成功（http_status &lt; 400）且 duration &gt; 0 的请求。
 * TTFT 均值只在有首字样本（{@code ttft_ms > 0}）的行上算，避免非流式拉低。SQL 片段见 {@link PerfSql}。
 *
 * @author nexusai
 */
public record PerfStat(String key, long avgTtftMs, long avgDurationMs, long requestCount,
                       long tokensCount, double throughput) {
}
