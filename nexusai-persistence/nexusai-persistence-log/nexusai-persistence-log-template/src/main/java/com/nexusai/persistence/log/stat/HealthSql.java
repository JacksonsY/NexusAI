package com.nexusai.persistence.log.stat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 健康监控聚合跨方言 SQL 片段（mysql/pg/clickhouse 三 driver 共用·与 {@link PerfSql} 同范式）。
 * <p>健康监控口径定义：
 * <ul>
 *   <li><b>成功</b>：{@code http_status < 400}（全站统一口径，含 null 自动排除）；</li>
 *   <li><b>TTFT 均值</b>：复用 {@link PerfSql#AVG_TTFT}（仅 {@code ttft_ms > 0} 样本）；</li>
 *   <li><b>时延均值</b>：全部请求（含失败——失败请求的耗时同样反映链路状况）。</li>
 * </ul>
 * 时间线分桶的「秒差」表达式三方言各异（timestampdiff / extract epoch / dateDiff），由各 driver 自带。
 *
 * @author nexusai
 */
public final class HealthSql {

    private HealthSql() {
    }

    /** 成功量（http_status &lt; 400）。 */
    public static final String SUCCESS_SUM = "sum(case when http_status < 400 then 1 else 0 end)";

    /** 成功行输出 token 合计（TPS 分子·失败行不计·仅成功有产出行计吞吐）。 */
    public static final String OK_TOKENS_SUM =
            "sum(case when http_status < 400 then completion_tokens else 0 end)";

    /** 成功行生成耗时合计（ms·TPS 分母·复用 {@link PerfSql#GEN_MS_ROW} 流式去 TTFT 口径）。 */
    public static final String OK_GEN_MS_SUM =
            "sum(case when http_status < 400 then " + PerfSql.GEN_MS_ROW + " else 0 end)";

    /** 维度聚合公共选择列（不含分组键/计数——计数写法 mysql·pg {@code count(*)} 与 ch {@code count()} 有别）。 */
    public static final String HEALTH_COLUMNS = SUCCESS_SUM + " as success"
            + ", avg(duration_ms) as avg_dur, " + PerfSql.AVG_TTFT + " as avg_ttft"
            + ", " + OK_TOKENS_SUM + " as tokens_count, " + OK_GEN_MS_SUM + " as gen_ms"
            + ", count(distinct channel_id) as channels, count(distinct key_id) as key_cnt"
            + ", max(create_dt) as last_at";

    /** 时间线分桶公共选择列（不含分组键/段号/计数）。 */
    public static final String BUCKET_COLUMNS = SUCCESS_SUM + " as success";

    /** 性能时间线分桶公共选择列（详情钻取·七计数器：量/成功/延迟和/TTFT和+样本/输出tokens/生成耗时）。 */
    public static final String PERF_BUCKET_COLUMNS = SUCCESS_SUM + " as success"
            + ", sum(duration_ms) as lat_sum"
            + ", sum(case when ttft_ms > 0 then ttft_ms else 0 end) as ttft_sum"
            + ", sum(case when ttft_ms > 0 then 1 else 0 end) as ttft_cnt"
            + ", " + OK_TOKENS_SUM + " as tokens_count, " + OK_GEN_MS_SUM + " as gen_ms";

    /**
     * 渠道性能聚合公共选择列（性能分析页·计数器部分，分位列由各 driver 方言补）：
     * 成功/吞吐计数器 + TTFT/响应样本和与样本数 + 慢请求数（{@code %d} 占位填慢阈值 ms）。
     */
    public static final String CHANNEL_PERF_COLUMNS_FMT = SUCCESS_SUM + " as success"
            + ", " + OK_TOKENS_SUM + " as tokens_count, " + OK_GEN_MS_SUM + " as gen_ms"
            + ", sum(case when ttft_ms > 0 then ttft_ms else 0 end) as ttft_sum"
            + ", sum(case when ttft_ms > 0 then 1 else 0 end) as ttft_cnt"
            + ", sum(case when duration_ms > 0 then duration_ms else 0 end) as lat_sum"
            + ", sum(case when duration_ms > 0 then 1 else 0 end) as resp_cnt"
            + ", sum(case when duration_ms >= %d then 1 else 0 end) as slow_cnt";

    /** 错误分类键（error_code 非空取之·否则 HTTP_&lt;status&gt;·concat 三方言通吃）。 */
    public static final String ERROR_CATEGORY =
            "coalesce(nullif(error_code, ''), concat('HTTP_', http_status))";

    /** 时间字面量格式（{@code yyyy-MM-dd HH:mm:ss}·三方言通吃）。 */
    private static final DateTimeFormatter LITERAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 时间字面量（{@code yyyy-MM-dd HH:mm:ss}·三方言通吃），供分桶「秒差」表达式嵌入。
     * 仅接受服务端生成的时间（绝不接受外部串），不存在注入面。
     */
    public static String literal(LocalDateTime time) {
        return time.format(LITERAL_FORMAT);
    }
}
