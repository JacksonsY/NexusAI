package com.nexusai.persistence.log;

import com.nexusai.persistence.log.stat.ChannelPerfStat;
import com.nexusai.persistence.log.stat.DailyStat;
import com.nexusai.persistence.log.stat.DimensionCount;
import com.nexusai.persistence.log.stat.DimensionStat;
import com.nexusai.persistence.log.stat.ErrorCount;
import com.nexusai.persistence.log.stat.HealthBucket;
import com.nexusai.persistence.log.stat.HealthStat;
import com.nexusai.persistence.log.stat.PercentileDay;
import com.nexusai.persistence.log.stat.PerfBucket;
import com.nexusai.persistence.log.stat.PerfDailyStat;
import com.nexusai.persistence.log.stat.PerfFilter;
import com.nexusai.persistence.log.stat.PerfStat;
import com.nexusai.persistence.log.stat.RangeTotals;
import com.nexusai.persistence.log.stat.StatusCount;

import java.time.LocalDateTime;
import java.util.List;
import com.nexusai.persistence.log.stat.UsageAggRow;
import com.nexusai.persistence.log.stat.HeatmapDay;
import com.nexusai.persistence.log.stat.IntervalRow;
import com.nexusai.persistence.log.stat.CacheHitStats;
import com.nexusai.persistence.log.stat.ModelCacheRow;
import com.nexusai.persistence.log.stat.LatencyBucket;
import com.nexusai.persistence.log.stat.ErrorTrendBucket;

/**
 * 请求日志存储 SPI（日志域 template·按存储后端可替换的抽象范式）。
 * <p>把「请求日志怎么存怎么查」抽象为存储无关接口，由 driver 实现：
 * <ul>
 *   <li>{@code log-mysql}：MyBatis-Plus + nai_request_log（store=mysql·默认）</li>
 *   <li>{@code log-clickhouse}：ClickHouse JDBC（store=clickhouse·quantile 分位、toStartOfHour 时序）</li>
 * </ul>
 * 上层（observe 写 / control-domain 查）只依赖本接口，按 {@code nexusai.log.store} 选 driver（三选一·全量落选中后端·非冷热分层）。
 *
 * @author nexusai
 */
public interface RequestLogStore {

    /** 写入一条请求日志（observe 消费者落库）。 */
    void save(RequestLogRecord record);

    /**
     * 批量写入请求日志（observe 消费者按 Redis Stream drain 的一批调用）。
     * <p><b>原子契约</b>：实现须保证整批「全落地或全不落地」（单条多值 INSERT 或事务）。消费侧是 at-least-once
     * 重投模型——批失败会回退逐条重放，若实现非原子地部分落地又整体报错，重放会产生重复行。
     * <p>默认实现逐条 {@link #save}（<b>非原子</b>），仅为向后兼容占位：量产 driver 必须 override 为原子批插
     * （ClickHouse 单 INSERT = 单 part 规避 TOO_MANY_PARTS；MySQL/PG 单往返多值 INSERT 提吞吐）。
     */
    default void saveBatch(List<RequestLogRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (RequestLogRecord record : records) {
            save(record);
        }
    }

    /**
     * 删除 cutoff 之前的日志（清理策略/手动清空·分批）。
     *
     * @return 本批删除行数；driver 不支持精确计数（ClickHouse 异步 mutation）时返回 -1
     */
    int deleteBefore(LocalDateTime cutoff, int limit);

    /** 分页查询（control-domain 请求日志列表）。 */
    RequestLogPage page(RequestLogQuery query);

    /** 单条详情（按 requestId·钻取）。无则返回 null。 */
    RequestLogRecord findByRequestId(String requestId);

    /**
     * 按维度聚合用量（使用记录页「用量分析」面板）：
     * 与 {@link #page} 共用同一组筛选条件，按 {@code column}∈{@code model|project_code|channel_id|api_format}
     * 分组聚合请求量/成功量/token 三态/USD 成本/平均耗时，按请求量降序取前 {@code limit} 组。
     * {@code column} 由上层经 {@link RequestLogQuery#AGG_COLUMNS} 白名单保证，绝不接受任意外部串。
     */
    List<UsageAggRow> aggregateBy(RequestLogQuery query, String column, int limit);

    /**
     * 活跃度热图按日聚合（365 天窗口）：自 {@code since} 起逐日
     * 请求量/token 合计/USD 成本合计，仅返回有数据的日期（零请求日上层补齐）。
     */
    List<HeatmapDay> heatmapDaily(LocalDateTime since);

    /**
     * 请求间隔时间线原始行：{@code since} 起的
     * （项目, 模型, 时间）轻量三元组，按（project_code, create_dt）升序、上限 {@code limit} 行，
     * 相邻间隔由上层 Java 端差分计算。
     */
    List<IntervalRow> recentForIntervals(LocalDateTime since, int limit);





    /** 近窗按 HTTP 状态码分组计数（性能分析页告警错误率）。 */
    List<StatusCount> statusBreakdown(LocalDateTime since);

    /** 区间 [since, until) 总量 + 成功数（Dashboard 环比计数/成功率）。 */
    RangeTotals totalsBetween(LocalDateTime since, LocalDateTime until);

    /** 近窗成功请求缓存命中聚合（缓存监控·命中分析：请求命中率/Token 命中率）。 */
    CacheHitStats cacheHitStats(LocalDateTime since);

    /** 近窗按模型聚合缓存命中 token（缓存监控·命中分析按模型费率折算读取费用/节省）。 */
    List<ModelCacheRow> cacheHitByModel(LocalDateTime since);

    /** 近窗按渠道分组的总量 + 成功数（Dashboard 渠道成功率排行）。 */
    List<DimensionCount> channelBreakdown(LocalDateTime since);

    /**
     * 近窗按维度（{@code column}∈{@code channel_id|model|key_id}）聚合请求量 + 成功数 + token 三态
     * （Dashboard 维度分析·去钱化）。{@code column} 由上层白名单保证，绝不接受任意外部串。
     */
    List<DimensionStat> statsByDimension(String column, LocalDateTime since);

    /**
     * 近窗按维度（{@code column}∈{@code channel_id|model}）聚合性能（Dashboard「最快榜」）：
     * 平均 TTFT/时延 + 请求量 + 输出 token 总量 + 吞吐（口径见 {@link com.nexusai.persistence.log.stat.PerfSql}，
     * 仅统计成功且 duration &gt; 0 的请求）。{@code column} 由上层白名单保证。
     */
    List<PerfStat> perfByDimension(String column, LocalDateTime since);

    /**
     * 近窗按天 × 维度（{@code column}∈{@code channel_id|model}）聚合性能时序
     * （Dashboard「最佳模型/渠道性能」多曲线图）：
     * 逐日吞吐 + TTFT 均值 + 请求量，口径与 {@link #perfByDimension} 同源。{@code column} 由上层白名单保证。
     */
    List<PerfDailyStat> perfDaily(String column, LocalDateTime since);

    /** 近窗按天聚合请求量 + 成功数 + token 三态（Dashboard「每日请求概览」面积图）。 */
    List<DailyStat> dailyStats(LocalDateTime since);

    /**
     * 健康监控维度聚合（健康监控页）：按 {@code column}∈{@code channel_id|model}
     * 聚合请求量/成功量/均值时延/均值 TTFT/渠道凭证数/最近事件；{@code subColumn} 非 null 时按
     * （column × subColumn）二维分组（渠道下挂模型明细·一次查询防 N+1）。列名由上层白名单保证。
     */
    List<HealthStat> healthByDimension(String column, String subColumn, LocalDateTime since);

    /**
     * 健康时间线分桶聚合（健康监控页）：把 [since, until] 均分 {@code segments} 段，
     * SQL 端按（{@code column}[× {@code subColumn}] × 段号）聚合请求量/成功量，越界事件钳到末段。
     * 列名由上层白名单保证；{@code segments} 由上层钳制（≤200）。
     */
    List<HealthBucket> healthTimeline(String column, String subColumn,
                                      LocalDateTime since, LocalDateTime until, int segments);

    /**
     * 性能时间线分桶聚合（健康详情钻取·逐桶曲线）：按 {@code column = value} 过滤
     * 单维度（{@code column}∈{@code channel_id|model}），把 [since, until] 均分 {@code segments} 段聚合
     * 量/成功/延迟和/TTFT 和+样本/成功行输出 tokens/成功行生成耗时（七计数器·派生值 Java 端算）。
     * 列名由上层白名单保证；{@code value} 经参数绑定。
     */
    List<PerfBucket> perfTimeline(String column, String value,
                                  LocalDateTime since, LocalDateTime until, int segments);

    /**
     * 渠道性能聚合（性能分析页）：按筛选条件统计每渠道
     * （{@code groupByChannel=false} 时为总体单行，key=null）的计数器 + P90/P99 分位（响应与首字）。
     * 分位方言：PG percentile_cont FILTER / CH quantileIf / MySQL PERCENT_RANK 窗口近似。
     */
    List<ChannelPerfStat> channelPerf(PerfFilter filter, LocalDateTime since, LocalDateTime until,
                                      int slowThresholdMs, boolean groupByChannel);

    /**
     * 渠道性能时间线（性能分析页·多渠道 TPS/首字趋势曲线）：按筛选条件，把 [since, until] 均分
     * {@code segments} 段，按（渠道 × 段号）聚合七计数器（{@link PerfBucket#key()}=channel_id 串）。
     */
    List<PerfBucket> channelPerfTimeline(PerfFilter filter, LocalDateTime since, LocalDateTime until, int segments);

    /**
     * 按日延迟分位时序（性能分析页）：
     * 每天一行，响应（duration&gt;0）与首字（ttft&gt;0）各取 P50/P90/P99。
     */
    List<PercentileDay> percentileDaily(LocalDateTime since, LocalDateTime until);

    /**
     * 错误分类分布（性能分析页）：仅 {@code http_status >= 400} 行，
     * 按 {@code error_code}（空则 HTTP_&lt;status&gt;）分组计数，按量降序。
     * {@code channelId} 非 null 时按渠道过滤（运维监控页筛选）。
     */
    List<ErrorCount> errorBreakdown(LocalDateTime since, LocalDateTime until, Long channelId);

    /** 最近错误明细（性能分析页实时区）：{@code http_status >= 400} 倒序最近 {@code limit} 条。 */
    List<RequestLogRecord> recentErrors(LocalDateTime since, int limit);

    /**
     * 延迟直方图（运维监控页）：区间 [since, until]
     * 内 {@code duration_ms > 0} 的请求按固定 6 区间分桶计数（{@link com.nexusai.persistence.log.stat.PerfSql#LATENCY_BUCKET_ROW}），
     * 缺桶由上层补零保证顺序。
     */
    List<LatencyBucket> latencyHistogram(LocalDateTime since, LocalDateTime until, Long channelId);

    /**
     * 错误趋势分桶（运维监控页·全部/上游/限流三线）：把 [since, until] 均分 {@code segments} 段，
     * 仅 {@code http_status >= 400} 行，按段聚合全部/上游(≥500)/限流(=429) 三计数。
     * {@code channelId} 非 null 时按渠道过滤。
     */
    List<ErrorTrendBucket> errorTrendTimeline(
            LocalDateTime since, LocalDateTime until, int segments, Long channelId);

}
