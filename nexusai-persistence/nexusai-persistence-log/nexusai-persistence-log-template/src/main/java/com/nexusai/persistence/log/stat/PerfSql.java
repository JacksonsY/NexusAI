package com.nexusai.persistence.log.stat;

/**
 * 性能聚合跨方言 SQL 片段（mysql/pg/clickhouse 三 driver 共用以消除重复·与 {@link StatRows} 同范式）。
 * <p>口径定义：
 * <ul>
 *   <li><b>生成耗时</b>：流式且有 TTFT → {@code duration - ttft}（下钳 0，防脏数据负值）；否则全程 {@code duration}；</li>
 *   <li><b>吞吐</b>：{@code Σ输出tokens × 1000.0 / Σ生成耗时}（tokens/second·分母 0 → NULL → 上层记 0）；</li>
 *   <li><b>TTFT 均值</b>：只在 {@code ttft_ms > 0} 的行上平均（{@code × 1.0} 防 PG 整除截断）。</li>
 * </ul>
 * {@code stream_mode = 1} 而非布尔上下文：MySQL TINYINT / PG SMALLINT / CH UInt8 三方言通吃。
 *
 * @author nexusai
 */
public final class PerfSql {

    private PerfSql() {
    }

    /** 单行生成耗时（ms）：流式去 TTFT 段（下钳 0 防脏数据负值），非流式全程。 */
    public static final String GEN_MS_ROW =
            "(case when stream_mode = 1 and ttft_ms is not null"
                    + " then (case when ttft_ms >= duration_ms then 0 else duration_ms - ttft_ms end)"
                    + " else duration_ms end)";

    /** 生成耗时合计（ms）。 */
    public static final String GEN_MS_SUM = "sum(" + GEN_MS_ROW + ")";

    /** 吞吐（tokens/second）：Σ输出tokens × 1000 / Σ生成耗时。 */
    public static final String THROUGHPUT =
            "sum(completion_tokens) * 1000.0 / nullif(" + GEN_MS_SUM + ", 0)";

    /** TTFT 均值（仅 ttft_ms &gt; 0 样本）。 */
    public static final String AVG_TTFT =
            "sum(case when ttft_ms > 0 then ttft_ms else 0 end) * 1.0"
                    + " / nullif(sum(case when ttft_ms > 0 then 1 else 0 end), 0)";

    /** 性能聚合公共选择列（不含分组键/日期/计数——计数写法 mysql·pg {@code count(*)} 与 ch {@code count()} 有别）。 */
    public static final String PERF_COLUMNS =
            "sum(completion_tokens) as tokens_count, " + AVG_TTFT + " as avg_ttft, " + THROUGHPUT + " as throughput";

    /** 延迟直方图单行分桶（固定 6 区间·三方言通用 CASE·仅 duration &gt; 0 行有意义）。 */
    public static final String LATENCY_BUCKET_ROW =
            "(case when duration_ms < 100 then '0-100ms'"
                    + " when duration_ms < 500 then '100-500ms'"
                    + " when duration_ms < 1000 then '500ms-1s'"
                    + " when duration_ms < 3000 then '1-3s'"
                    + " when duration_ms < 10000 then '3-10s'"
                    + " else '10s+' end)";
}
