package com.nexusai.persistence.log.stat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 聚合行（{@code selectMaps} 结果 Map）→ stat record 的转换工具，三 driver（mysql/pg/clickhouse）共用以消除重复。
 * <p>统一约定别名全小写 snake_case（规避 PG 折叠未加引号标识符为小写导致 camelCase 取 null）：
 * {@code k}（维度键）、{@code d}（日期）、{@code total}/{@code success}、{@code input_tokens}/{@code output_tokens}/{@code cached_tokens}、
 * {@code avg_ttft}/{@code avg_dur}/{@code cnt}/{@code tokens_count}/{@code throughput}。
 *
 * @author nexusai
 */
public final class StatRows {

    private StatRows() {
    }

    /** 维度聚合行 → {@link DimensionStat}（请求量 + 成功 + token 三态）。 */
    public static DimensionStat dimension(Map<String, Object> r) {
        return new DimensionStat(str(r.get("k")),
                nz(toLong(r.get("total"))), nz(toLong(r.get("success"))),
                nz(toLong(r.get("input_tokens"))), nz(toLong(r.get("output_tokens"))), nz(toLong(r.get("cached_tokens"))));
    }

    /** 性能聚合行 → {@link PerfStat}（平均 TTFT/时延 + 请求量 + 输出 token + 吞吐）。 */
    public static PerfStat perf(Map<String, Object> r) {
        return new PerfStat(str(r.get("k")),
                nz(toLong(r.get("avg_ttft"))), nz(toLong(r.get("avg_dur"))), nz(toLong(r.get("cnt"))),
                nz(toLong(r.get("tokens_count"))), nzD(toDouble(r.get("throughput"))));
    }

    /** 日粒度性能聚合行 → {@link PerfDailyStat}（日期 + 键 + 输出 token + TTFT 均值 + 请求量 + 吞吐）。 */
    public static PerfDailyStat perfDaily(Map<String, Object> r) {
        return new PerfDailyStat(str(r.get("d")), str(r.get("k")),
                nz(toLong(r.get("tokens_count"))), nz(toLong(r.get("avg_ttft"))),
                nz(toLong(r.get("cnt"))), nzD(toDouble(r.get("throughput"))));
    }

    /** 日时序聚合行 → {@link DailyStat}（日期 + 请求量/成功 + token 三态）。 */
    public static DailyStat daily(Map<String, Object> r) {
        return new DailyStat(str(r.get("d")),
                nz(toLong(r.get("total"))), nz(toLong(r.get("success"))),
                nz(toLong(r.get("input_tokens"))), nz(toLong(r.get("output_tokens"))), nz(toLong(r.get("cached_tokens"))));
    }

    /** 用量聚合行 → {@link UsageAggRow}（维度键 + 请求量/成功 + token 三态/总量 + USD 成本 + 平均耗时）。 */
    public static UsageAggRow usageAgg(Map<String, Object> r) {
        return new UsageAggRow(str(r.get("k")),
                nz(toLong(r.get("total"))), nz(toLong(r.get("success"))),
                nz(toLong(r.get("prompt_tokens"))), nz(toLong(r.get("completion_tokens"))),
                nz(toLong(r.get("cache_tokens"))), nz(toLong(r.get("total_tokens"))),
                nzB(toBig(r.get("cost_usd"))), toLong(r.get("avg_dur")));
    }

    /** 热图日聚合行 → {@link HeatmapDay}（日期 + 请求量 + token 合计 + USD 成本合计）。 */
    public static HeatmapDay heatmapDay(Map<String, Object> r) {
        return new HeatmapDay(str(r.get("d")),
                nz(toLong(r.get("total"))), nz(toLong(r.get("tokens"))), nzB(toBig(r.get("cost_usd"))));
    }

    /** 健康监控聚合行 → {@link HealthStat}（量/成功 + 均值时延/TTFT + 吞吐计数器 + 渠道/凭证数 + 最近事件）。 */
    public static HealthStat health(Map<String, Object> r) {
        return new HealthStat(str(r.get("k")), str(r.get("sub_k")),
                nz(toLong(r.get("total"))), nz(toLong(r.get("success"))),
                toLong(r.get("avg_dur")), toLong(r.get("avg_ttft")),
                nz(toLong(r.get("tokens_count"))), nz(toLong(r.get("gen_ms"))),
                nz(toLong(r.get("channels"))), nz(toLong(r.get("key_cnt"))),
                toDateTime(r.get("last_at")));
    }

    /** 健康时间线分桶行 → {@link HealthBucket}（维度 × 段号 × 量/成功）。 */
    public static HealthBucket healthBucket(Map<String, Object> r) {
        Long seg = toLong(r.get("seg"));
        return new HealthBucket(str(r.get("k")), str(r.get("sub_k")),
                seg == null ? 0 : seg.intValue(), nz(toLong(r.get("total"))), nz(toLong(r.get("success"))));
    }

    /** 性能时间线分桶行 → {@link PerfBucket}（可选分组键 k × 段号 × 七计数器·钻取曲线/多渠道曲线共用）。 */
    public static PerfBucket perfBucket(Map<String, Object> r) {
        Long seg = toLong(r.get("seg"));
        return new PerfBucket(str(r.get("k")), seg == null ? 0 : seg.intValue(),
                nz(toLong(r.get("total"))), nz(toLong(r.get("success"))),
                nz(toLong(r.get("lat_sum"))), nz(toLong(r.get("ttft_sum"))), nz(toLong(r.get("ttft_cnt"))),
                nz(toLong(r.get("tokens_count"))), nz(toLong(r.get("gen_ms"))));
    }

    /** 渠道性能聚合行 → {@link ChannelPerfStat}（计数器部分·分位由 driver 另查后 merge）。 */
    public static ChannelPerfStat channelPerf(Map<String, Object> r) {
        return new ChannelPerfStat(str(r.get("k")),
                nz(toLong(r.get("total"))), nz(toLong(r.get("success"))),
                nz(toLong(r.get("tokens_count"))), nz(toLong(r.get("gen_ms"))),
                nz(toLong(r.get("ttft_sum"))), nz(toLong(r.get("ttft_cnt"))),
                nz(toLong(r.get("lat_sum"))), nz(toLong(r.get("resp_cnt"))), nz(toLong(r.get("slow_cnt"))),
                toLong(r.get("p90_dur")), toLong(r.get("p99_dur")),
                toLong(r.get("p90_ttft")), toLong(r.get("p99_ttft")));
    }

    /** 按日分位行 → {@link PercentileDay}。 */
    public static PercentileDay percentileDay(Map<String, Object> r) {
        return new PercentileDay(str(r.get("d")),
                toLong(r.get("p50_dur")), toLong(r.get("p90_dur")), toLong(r.get("p99_dur")),
                toLong(r.get("p50_ttft")), toLong(r.get("p90_ttft")), toLong(r.get("p99_ttft")));
    }

    /** 错误分类计数行 → {@link ErrorCount}。 */
    public static ErrorCount errorCount(Map<String, Object> r) {
        return new ErrorCount(str(r.get("k")), nz(toLong(r.get("cnt"))));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** max(create_dt) 跨驱动取值：mysql 回 LocalDateTime、pg 回 Timestamp、ch 可能回字符串。 */
    private static LocalDateTime toDateTime(Object o) {
        if (o instanceof LocalDateTime t) {
            return t;
        }
        if (o instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    /** SUM(DECIMAL) 跨驱动取值：mysql/pg/ch 多回 BigDecimal·其余 Number 兜底（USD 成本聚合用）。 */
    private static BigDecimal toBig(Object o) {
        if (o instanceof BigDecimal b) {
            return b;
        }
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    private static BigDecimal nzB(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static double nzD(Double v) {
        return v == null ? 0D : v;
    }
}
