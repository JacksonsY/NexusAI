package com.nexusai.persistence.log.pg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nexusai.persistence.log.LogColumns;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexusai.persistence.log.RequestLogPage;
import com.nexusai.persistence.log.RequestLogQuery;
import com.nexusai.persistence.log.RequestLogRecord;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.pg.mapper.NaiRequestLogPgMapper;
import com.nexusai.persistence.log.stat.DailyStat;
import com.nexusai.persistence.log.stat.DimensionCount;
import com.nexusai.persistence.log.stat.DimensionStat;
import com.nexusai.persistence.log.stat.ChannelPerfStat;
import com.nexusai.persistence.log.stat.ErrorCount;
import com.nexusai.persistence.log.stat.HealthBucket;
import com.nexusai.persistence.log.stat.HealthSql;
import com.nexusai.persistence.log.stat.HealthStat;
import com.nexusai.persistence.log.stat.PercentileDay;
import com.nexusai.persistence.log.stat.PerfBucket;
import com.nexusai.persistence.log.stat.PerfDailyStat;
import com.nexusai.persistence.log.stat.PerfFilter;
import com.nexusai.persistence.log.stat.PerfSql;
import com.nexusai.persistence.log.stat.PerfStat;
import com.nexusai.persistence.log.stat.CacheHitStats;
import com.nexusai.persistence.log.stat.ModelCacheRow;
import com.nexusai.persistence.log.stat.RangeTotals;
import com.nexusai.persistence.log.stat.StatusCount;
import com.nexusai.persistence.log.stat.StatRows;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.nexusai.persistence.log.stat.UsageAggRow;
import com.nexusai.persistence.log.stat.HeatmapDay;
import com.nexusai.persistence.log.stat.IntervalRow;
import com.nexusai.persistence.log.stat.LatencyBucket;
import com.nexusai.persistence.log.stat.ErrorTrendBucket;

/**
 * PostgreSQL 存储 driver（store=pg）：MyBatis-Plus 实现 {@link RequestLogStore}。
 * <p>{@code nexusai.log.store=pg} 时激活（与 log-mysql 互斥·部署在 PG 时用它）。
 * 通用增删查走 MyBatis-Plus Wrapper（跨方言可移植）；分位/分桶走 PG 方言 SQL（见 {@link NaiRequestLogMapper}）。
 * PO 是 driver 私有实现细节，对外只暴露 {@link RequestLogRecord}。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nexusai.log", name = "store", havingValue = "pg")
public class PgRequestLogStore implements RequestLogStore {

    private final NaiRequestLogPgMapper mapper;

    @Override
    public void save(RequestLogRecord record) {
        NaiRequestLog po = new NaiRequestLog();
        // driver 始终掌握主键生成权，不信任领域模型携带的 id（防绕过 ASSIGN_ID 雪花/撞键）
        BeanUtils.copyProperties(record, po, "id");
        mapper.insert(po);
    }

    @Override
    public int deleteBefore(LocalDateTime cutoff, int limit) {
        return mapper.deleteBefore(cutoff, Math.max(1, limit));
    }

    @Override
    public RequestLogPage page(RequestLogQuery query) {
        QueryWrapper<NaiRequestLog> w = buildFilter(query);
        String sortCol = query.sortColumn();
        if (sortCol != null) {
            w.orderBy(true, !query.orderDesc(), sortCol);
        }
        w.orderByDesc("id");
        Page<NaiRequestLog> page = mapper.selectPage(Page.of(query.pageNum(), query.pageSize()), w);
        List<RequestLogRecord> records = page.getRecords().stream().map(PgRequestLogStore::toRecord).toList();
        return new RequestLogPage(records, page.getTotal(), page.getCurrent(), page.getSize());
    }



    /** 多维筛选条件 → Wrapper（字符串列名·值经参数绑定·与 page/rangeStats 共用保证口径一致）。 */
    private static QueryWrapper<NaiRequestLog> buildFilter(RequestLogQuery q) {
        QueryWrapper<NaiRequestLog> w = new QueryWrapper<>();
        w.eq(RequestLogQuery.hasText(q.projectCode()), "project_code", q.projectCode())
                .eq(q.channelId() != null, "channel_id", q.channelId())
                .eq(RequestLogQuery.hasText(q.model()), "model", q.model())
                .likeRight(RequestLogQuery.hasText(q.requestId()), "request_id", q.requestId())
                .eq(RequestLogQuery.hasText(q.modality()), "modality", q.modality())
                .eq(q.streamMode() != null, "stream_mode", Boolean.TRUE.equals(q.streamMode()) ? 1 : 0)
                .lt(Boolean.TRUE.equals(q.success()), "http_status", 400)
                .ge(Boolean.FALSE.equals(q.success()), "http_status", 400)
                .eq(q.status() != null, "http_status", q.status())
                .eq(RequestLogQuery.hasText(q.apiFormat()), "api_format", q.apiFormat())
                .eq(RequestLogQuery.hasText(q.clientFamily()), "client_family", q.clientFamily())
                .eq(RequestLogQuery.STATUS_CANCELLED.equals(q.statusFilter()), "usage_source", "client-abort")
                .gt(RequestLogQuery.STATUS_HAS_RETRY.equals(q.statusFilter()), "retry_count", 0)
                .gt(RequestLogQuery.STATUS_HAS_FALLBACK.equals(q.statusFilter()), "fallback_count", 0)
                .ge(q.begin() != null, "create_dt", q.begin())
                .le(q.end() != null, "create_dt", q.end());
        if (RequestLogQuery.hasText(q.search())) {
            String kw = q.search().trim();
            w.and(x -> x.like("request_id", kw).or().like("trace_id", kw).or().like("model", kw)
                    .or().like("target_model", kw).or().like("error_code", kw));
        }
        if (Boolean.TRUE.equals(q.hideUnknown())) {
            w.isNotNull("model").ne("model", "");
        }
        return w;
    }


    @Override
    public RequestLogRecord findByRequestId(String requestId) {
        NaiRequestLog po = mapper.selectOne(Wrappers.lambdaQuery(NaiRequestLog.class)
                .eq(NaiRequestLog::getRequestId, requestId).last("limit 1"));
        return po == null ? null : toRecord(po);
    }

    @Override
    public List<UsageAggRow> aggregateBy(RequestLogQuery query, String column, int limit) {
        LogColumns.require(column);
        QueryWrapper<NaiRequestLog> w = buildFilter(query);
        w.select(column + " as k, count(*) as total, sum(case when http_status < 400 then 1 else 0 end) as success"
                        + ", sum(prompt_tokens) as prompt_tokens, sum(completion_tokens) as completion_tokens"
                        + ", sum(cache_tokens) as cache_tokens, sum(total_tokens) as total_tokens"
                        + ", sum(cost_usd) as cost_usd, avg(duration_ms) as avg_dur")
                .isNotNull(column).groupBy(column).orderByDesc("total").last("limit " + Math.max(1, limit));
        List<UsageAggRow> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectMaps(w)) {
            if (r != null) {
                out.add(StatRows.usageAgg(r));
            }
        }
        return out;
    }

    @Override
    public List<HeatmapDay> heatmapDaily(LocalDateTime since) {
        List<HeatmapDay> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("to_char(create_dt, 'YYYY-MM-DD') as d, count(*) as total, sum(total_tokens) as tokens, sum(cost_usd) as cost_usd")
                .ge("create_dt", since).groupBy("to_char(create_dt, 'YYYY-MM-DD')").orderByAsc("d"))) {
            if (r != null) {
                out.add(StatRows.heatmapDay(r));
            }
        }
        return out;
    }

    @Override
    public List<IntervalRow> recentForIntervals(LocalDateTime since, int limit) {
        return mapper.selectList(new QueryWrapper<NaiRequestLog>()
                        .select("project_code", "model", "create_dt")
                        .ge("create_dt", since).isNotNull("project_code")
                        .orderByAsc("project_code").orderByAsc("create_dt")
                        .last("limit " + Math.max(1, limit)))
                .stream()
                .map(po -> new IntervalRow(po.getProjectCode(), po.getModel(), po.getCreateDt()))
                .toList();
    }






    @Override
    public List<StatusCount> statusBreakdown(LocalDateTime since) {
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("http_status as status, count(*) as cnt")
                .ge("create_dt", since)
                .groupBy("http_status"));
        List<StatusCount> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r == null) {
                continue;
            }
            Long status = toLong(r.get("status"));
            out.add(new StatusCount(status == null ? null : status.intValue(), nz(toLong(r.get("cnt")))));
        }
        return out;
    }

    @Override
    public RangeTotals totalsBetween(LocalDateTime since, LocalDateTime until) {
        Map<String, Object> r = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("count(*) as total, sum(case when http_status < 400 then 1 else 0 end) as success")
                .ge("create_dt", since).lt("create_dt", until)).stream().findFirst().orElse(null);
        if (r == null) {
            return RangeTotals.EMPTY;
        }
        return new RangeTotals(nz(toLong(r.get("total"))), nz(toLong(r.get("success"))));
    }

    @Override
    public CacheHitStats cacheHitStats(LocalDateTime since) {
        Map<String, Object> r = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("count(*) as total, sum(case when cache_tokens > 0 then 1 else 0 end) as hit, "
                        + "sum(prompt_tokens) as pt, sum(cache_tokens) as ct, sum(cache_creation_tokens) as cct")
                .ge("create_dt", since).lt("http_status", 400)).stream().findFirst().orElse(null);
        if (r == null) {
            return CacheHitStats.EMPTY;
        }
        return new CacheHitStats(nz(toLong(r.get("total"))), nz(toLong(r.get("hit"))),
                nz(toLong(r.get("pt"))), nz(toLong(r.get("ct"))), nz(toLong(r.get("cct"))));
    }

    @Override
    public List<ModelCacheRow> cacheHitByModel(LocalDateTime since) {
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("model, sum(cache_tokens) as ct, sum(cache_creation_tokens) as cct")
                .ge("create_dt", since).lt("http_status", 400)
                .and(q -> q.gt("cache_tokens", 0).or().gt("cache_creation_tokens", 0))
                .isNotNull("model").groupBy("model"));
        List<ModelCacheRow> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(new ModelCacheRow(String.valueOf(r.get("model")),
                    nz(toLong(r.get("ct"))), nz(toLong(r.get("cct")))));
        }
        return out;
    }

    @Override
    public List<DimensionCount> channelBreakdown(LocalDateTime since) {
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("channel_id, count(*) as total, sum(case when http_status < 400 then 1 else 0 end) as success")
                .ge("create_dt", since).isNotNull("channel_id").groupBy("channel_id"));
        List<DimensionCount> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r == null) {
                continue;
            }
            out.add(new DimensionCount(toLong(r.get("channel_id")), nz(toLong(r.get("total"))), nz(toLong(r.get("success")))));
        }
        return out;
    }

    @Override
    public List<DimensionStat> statsByDimension(String column, LocalDateTime since) {
        LogColumns.require(column);
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select(column + " as k, count(*) as total"
                        + ", sum(case when http_status < 400 then 1 else 0 end) as success"
                        + ", sum(prompt_tokens) as input_tokens, sum(completion_tokens) as output_tokens, sum(cache_tokens) as cached_tokens")
                .ge("create_dt", since).isNotNull(column).groupBy(column));
        List<DimensionStat> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.dimension(r));
            }
        }
        return out;
    }

    @Override
    public List<PerfStat> perfByDimension(String column, LocalDateTime since) {
        LogColumns.require(column);
        // 最快榜口径：仅成功（http_status<400 自动排 null）且 duration>0；吞吐/TTFT 片段见 PerfSql
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select(column + " as k, count(*) as cnt, avg(duration_ms) as avg_dur, " + PerfSql.PERF_COLUMNS)
                .ge("create_dt", since).isNotNull(column).lt("http_status", 400).gt("duration_ms", 0)
                .groupBy(column));
        List<PerfStat> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.perf(r));
            }
        }
        return out;
    }

    @Override
    public List<PerfDailyStat> perfDaily(String column, LocalDateTime since) {
        LogColumns.require(column);
        // PG 不支持 GROUP BY 别名 → 日期截断用完整表达式分组
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("to_char(create_dt, 'YYYY-MM-DD') as d, " + column + " as k, count(*) as cnt, " + PerfSql.PERF_COLUMNS)
                .ge("create_dt", since).isNotNull(column).lt("http_status", 400).gt("duration_ms", 0)
                .groupBy("to_char(create_dt, 'YYYY-MM-DD'), " + column).orderByAsc("d"));
        List<PerfDailyStat> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.perfDaily(r));
            }
        }
        return out;
    }

    @Override
    public List<HealthStat> healthByDimension(String column, String subColumn, LocalDateTime since) {
        LogColumns.require(column);
        LogColumns.requireNullable(subColumn);
        String keyCols = subColumn == null ? column + " as k" : column + " as k, " + subColumn + " as sub_k";
        String groupCols = subColumn == null ? column : column + ", " + subColumn;
        QueryWrapper<NaiRequestLog> w = new QueryWrapper<NaiRequestLog>()
                .select(keyCols + ", count(*) as total, " + HealthSql.HEALTH_COLUMNS)
                .ge("create_dt", since).isNotNull(column);
        if (subColumn != null) {
            w.isNotNull(subColumn);
        }
        List<HealthStat> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectMaps(w.groupBy(groupCols))) {
            if (r != null) {
                out.add(StatRows.health(r));
            }
        }
        return out;
    }

    @Override
    public List<HealthBucket> healthTimeline(String column, String subColumn,
                                             LocalDateTime since, LocalDateTime until, int segments) {
        LogColumns.require(column);
        LogColumns.requireNullable(subColumn);
        long rangeSecs = Math.max(Duration.between(since, until).getSeconds(), 1);
        // since 为服务端生成的时间（非外部串）；分桶表达式按完整表达式分组（同 dailyStats 纪律）
        String seg = "least(floor(extract(epoch from (create_dt - timestamp '" + HealthSql.literal(since) + "')) * "
                + segments + " / " + rangeSecs + "), " + (segments - 1) + ")";
        String keyCols = subColumn == null ? column + " as k" : column + " as k, " + subColumn + " as sub_k";
        String groupCols = (subColumn == null ? column : column + ", " + subColumn) + ", " + seg;
        QueryWrapper<NaiRequestLog> w = new QueryWrapper<NaiRequestLog>()
                .select(keyCols + ", " + seg + " as seg, count(*) as total, " + HealthSql.BUCKET_COLUMNS)
                .ge("create_dt", since).le("create_dt", until).isNotNull(column);
        if (subColumn != null) {
            w.isNotNull(subColumn);
        }
        List<HealthBucket> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectMaps(w.groupBy(groupCols))) {
            if (r != null) {
                out.add(StatRows.healthBucket(r));
            }
        }
        return out;
    }

    @Override
    public List<PerfBucket> perfTimeline(String column, String value,
                                         LocalDateTime since, LocalDateTime until, int segments) {
        LogColumns.require(column);
        long rangeSecs = Math.max(Duration.between(since, until).getSeconds(), 1);
        String seg = "least(floor(extract(epoch from (create_dt - timestamp '" + HealthSql.literal(since) + "')) * "
                + segments + " / " + rangeSecs + "), " + (segments - 1) + ")";
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select(seg + " as seg, count(*) as total, " + HealthSql.PERF_BUCKET_COLUMNS)
                .ge("create_dt", since).le("create_dt", until).eq(column, value)
                .groupBy(seg));
        List<PerfBucket> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.perfBucket(r));
            }
        }
        return out;
    }

    /** PG 分位列（percentile_cont + FILTER 原生·round 取整便于 toLong）。 */
    private static final String PG_PCT_COLUMNS =
            "round(percentile_cont(0.9) within group (order by duration_ms) filter (where duration_ms > 0)) as p90_dur"
                    + ", round(percentile_cont(0.99) within group (order by duration_ms) filter (where duration_ms > 0)) as p99_dur"
                    + ", round(percentile_cont(0.9) within group (order by ttft_ms) filter (where ttft_ms > 0)) as p90_ttft"
                    + ", round(percentile_cont(0.99) within group (order by ttft_ms) filter (where ttft_ms > 0)) as p99_ttft";

    @Override
    public List<ChannelPerfStat> channelPerf(PerfFilter filter, LocalDateTime since, LocalDateTime until,
                                             int slowThresholdMs, boolean groupByChannel) {
        String keyCol = groupByChannel ? "channel_id" : "'_all'";
        QueryWrapper<NaiRequestLog> w = new QueryWrapper<NaiRequestLog>()
                .select(keyCol + " as k, count(*) as total, "
                        + HealthSql.CHANNEL_PERF_COLUMNS_FMT.formatted(slowThresholdMs) + ", " + PG_PCT_COLUMNS)
                .ge("create_dt", since).le("create_dt", until);
        applyFilter(w, filter);
        if (groupByChannel) {
            w.isNotNull("channel_id").groupBy("channel_id");
        }
        List<ChannelPerfStat> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectMaps(w)) {
            if (r != null) {
                out.add(StatRows.channelPerf(r));
            }
        }
        return out;
    }

    @Override
    public List<PerfBucket> channelPerfTimeline(PerfFilter filter, LocalDateTime since, LocalDateTime until,
                                                int segments) {
        long rangeSecs = Math.max(Duration.between(since, until).getSeconds(), 1);
        String seg = "least(floor(extract(epoch from (create_dt - timestamp '" + HealthSql.literal(since) + "')) * "
                + segments + " / " + rangeSecs + "), " + (segments - 1) + ")";
        QueryWrapper<NaiRequestLog> w = new QueryWrapper<NaiRequestLog>()
                .select("channel_id as k, " + seg + " as seg, count(*) as total, " + HealthSql.PERF_BUCKET_COLUMNS)
                .ge("create_dt", since).le("create_dt", until).isNotNull("channel_id");
        applyFilter(w, filter);
        List<PerfBucket> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectMaps(w.groupBy("channel_id, " + seg))) {
            if (r != null) {
                out.add(StatRows.perfBucket(r));
            }
        }
        return out;
    }

    @Override
    public List<PercentileDay> percentileDaily(LocalDateTime since, LocalDateTime until) {
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("to_char(create_dt, 'YYYY-MM-DD') as d"
                        + ", round(percentile_cont(0.5) within group (order by duration_ms) filter (where duration_ms > 0)) as p50_dur"
                        + ", round(percentile_cont(0.9) within group (order by duration_ms) filter (where duration_ms > 0)) as p90_dur"
                        + ", round(percentile_cont(0.99) within group (order by duration_ms) filter (where duration_ms > 0)) as p99_dur"
                        + ", round(percentile_cont(0.5) within group (order by ttft_ms) filter (where ttft_ms > 0)) as p50_ttft"
                        + ", round(percentile_cont(0.9) within group (order by ttft_ms) filter (where ttft_ms > 0)) as p90_ttft"
                        + ", round(percentile_cont(0.99) within group (order by ttft_ms) filter (where ttft_ms > 0)) as p99_ttft")
                .ge("create_dt", since).le("create_dt", until)
                .groupBy("to_char(create_dt, 'YYYY-MM-DD')").orderByAsc("d"));
        List<PercentileDay> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.percentileDay(r));
            }
        }
        return out;
    }

    @Override
    public List<ErrorCount> errorBreakdown(LocalDateTime since, LocalDateTime until, Long channelId) {
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select(HealthSql.ERROR_CATEGORY + " as k, count(*) as cnt")
                .ge("create_dt", since).le("create_dt", until).ge("http_status", 400)
                .eq(channelId != null, "channel_id", channelId)
                .groupBy(HealthSql.ERROR_CATEGORY).orderByDesc("cnt"));
        List<ErrorCount> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.errorCount(r));
            }
        }
        return out;
    }

    @Override
    public List<RequestLogRecord> recentErrors(LocalDateTime since, int limit) {
        return mapper.selectList(Wrappers.lambdaQuery(NaiRequestLog.class)
                        .ge(NaiRequestLog::getCreateDt, since).ge(NaiRequestLog::getHttpStatus, 400)
                        .orderByDesc(NaiRequestLog::getId).last("limit " + Math.max(1, limit)))
                .stream().map(PgRequestLogStore::toRecord).toList();
    }

    /** 性能筛选条件 → Wrapper（值经参数绑定）。 */
    private static void applyFilter(QueryWrapper<NaiRequestLog> w, PerfFilter f) {
        if (f == null) {
            return;
        }
        w.eq(f.channelId() != null, "channel_id", f.channelId())
                .eq(f.hasModel(), "model", f.model())
                .eq(f.hasModality(), "modality", f.modality())
                .eq(f.stream() != null, "stream_mode", Boolean.TRUE.equals(f.stream()) ? 1 : 0);
    }

    @Override
    public List<DailyStat> dailyStats(LocalDateTime since) {
        // PG 不支持 GROUP BY 别名 → 日期截断用完整表达式分组；ORDER BY 可用别名 d
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select("to_char(create_dt, 'YYYY-MM-DD') as d, count(*) as total"
                        + ", sum(case when http_status < 400 then 1 else 0 end) as success"
                        + ", sum(prompt_tokens) as input_tokens, sum(completion_tokens) as output_tokens, sum(cache_tokens) as cached_tokens")
                .ge("create_dt", since).groupBy("to_char(create_dt, 'YYYY-MM-DD')").orderByAsc("d"));
        List<DailyStat> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(StatRows.daily(r));
            }
        }
        return out;
    }

    @Override
    public List<LatencyBucket> latencyHistogram(LocalDateTime since, LocalDateTime until, Long channelId) {
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select(PerfSql.LATENCY_BUCKET_ROW + " as k, count(*) as cnt")
                .ge("create_dt", since).le("create_dt", until).gt("duration_ms", 0)
                .eq(channelId != null, "channel_id", channelId)
                .groupBy(PerfSql.LATENCY_BUCKET_ROW));
        List<LatencyBucket> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(new LatencyBucket(
                        String.valueOf(r.get("k")), nz(toLong(r.get("cnt")))));
            }
        }
        return out;
    }

    @Override
    public List<ErrorTrendBucket> errorTrendTimeline(
            LocalDateTime since, LocalDateTime until, int segments, Long channelId) {
        long rangeSecs = Math.max(Duration.between(since, until).getSeconds(), 1);
        String seg = "least(floor(extract(epoch from (create_dt - timestamp '" + HealthSql.literal(since) + "')) * " + segments + " / " + rangeSecs + "), " + (segments - 1) + ")";
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<NaiRequestLog>()
                .select(seg + " as seg, count(*) as errors, "
                        + "sum(case when http_status >= 500 then 1 else 0 end) as upstream_errors, "
                        + "sum(case when http_status = 429 then 1 else 0 end) as limited_errors")
                .ge("create_dt", since).le("create_dt", until).ge("http_status", 400)
                .eq(channelId != null, "channel_id", channelId)
                .groupBy("seg"));
        List<ErrorTrendBucket> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null) {
                out.add(new ErrorTrendBucket(
                        (int) nz(toLong(r.get("seg"))), nz(toLong(r.get("errors"))),
                        nz(toLong(r.get("upstream_errors"))), nz(toLong(r.get("limited_errors")))));
            }
        }
        return out;
    }

    private static RequestLogRecord toRecord(NaiRequestLog po) {
        RequestLogRecord r = new RequestLogRecord();
        BeanUtils.copyProperties(po, r);
        return r;
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
