package com.nexusai.control.domain.monitor.observe.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nexusai.observe.metrics.RelayInflightMetrics;
import com.nexusai.persistence.log.RequestLogRecord;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.ChannelPerfStat;
import com.nexusai.persistence.log.stat.DailyStat;
import com.nexusai.persistence.log.stat.ErrorCount;
import com.nexusai.persistence.log.stat.PercentileDay;
import com.nexusai.persistence.log.stat.PerfBucket;
import com.nexusai.persistence.log.stat.PerfFilter;
import com.nexusai.persistence.log.stat.RangeTotals;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.mapper.NaiNodeMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiFailoverEvent;
import com.nexusai.persistence.po.NaiNode;
import com.nexusai.control.domain.platform.runtime.service.RuntimeViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 性能分析服务（性能分析页·数据面）：
 * <ul>
 *   <li><b>live</b>：实时运行状态（10s 轮询）——健康分合成/错误计数/并发指标/并发槽/集群节点/
 *       故障转移分组/最近错误/熔断现状与建议（隧道区映射为本系统真实等价物集群节点）；</li>
 *   <li><b>providers</b>：渠道性能聚合（上游服务性能）——总体 summary + 每渠道行（含 P90/P99 双指标分位、
 *       慢请求、样本覆盖）+ 渠道×时间桶曲线（TPS/首字趋势）；</li>
 *   <li><b>percentiles / errors</b>：按日延迟分位时序、错误分类分布 + 按日错误趋势。</li>
 * </ul>
 * 全部读时聚合直查日志 store（与健康监控同决策：不建预聚合表，量大切 ClickHouse driver）。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class PerfAnalysisService {

    private final RequestLogStore logStore;
    private final RelayInflightMetrics inflightMetrics;
    private final ObserveStatService observeStatService;
    private final RuntimeViewService runtimeViewService;
    private final NaiChannelMapper channelMapper;
    private final NaiFailoverEventMapper failoverMapper;
    private final NaiNodeMapper nodeMapper;

    /** 渠道性能时间线段数（趋势双图·与健康钻取同密度）。 */
    private static final int TIMELINE_SEGMENTS = 48;
    /** 节点离线阈值（与 NodeAdminController 同口径）。 */
    private static final long NODE_OFFLINE_SECONDS = 90;
    /** 健康分满分。 */
    private static final int MAX_HEALTH_SCORE = 100;
    /** 单条 critical 告警扣分。 */
    private static final int CRITICAL_PENALTY = 30;
    /** 单条 warning 告警扣分。 */
    private static final int WARNING_PENALTY = 10;
    /** 健康分阈值：≥ 该值视为 healthy。 */
    private static final int HEALTHY_THRESHOLD = 90;
    /** 健康分阈值：≥ 该值视为 degraded（否则 critical）。 */
    private static final int DEGRADED_THRESHOLD = 60;
    /** 最近错误展示条数。 */
    private static final int RECENT_ERRORS_LIMIT = 12;
    /** live/最近错误回看窗口（小时）。 */
    private static final int LIVE_LOOKBACK_HOURS = 24;

    /** 实时运行状态（聚合系统健康/并发保护/集群节点/降级切换/最近错误/熔断与建议）。 */
    public Map<String, Object> live() {
        LocalDateTime now = LocalDateTime.now();
        RangeTotals hour = logStore.totalsBetween(now.minusHours(1), now);
        RangeTotals day = logStore.totalsBetween(now.minusHours(LIVE_LOOKBACK_HOURS), now);
        List<Map<String, Object>> alerts = observeStatService.alerts();

        // 健康分合成：100 - critical×30 - warning×10（钳 0-100·可解释优先）
        int score = MAX_HEALTH_SCORE;
        for (Map<String, Object> alert : alerts) {
            score -= "critical".equals(alert.get("level")) ? CRITICAL_PENALTY : WARNING_PENALTY;
        }
        score = Math.max(0, Math.min(MAX_HEALTH_SCORE, score));
        String status = score >= HEALTHY_THRESHOLD ? "healthy" : score >= DEGRADED_THRESHOLD ? "degraded" : "critical";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("healthScore", score);
        data.put("status", status);
        data.put("errorsLastHour", hour.total() - hour.success());
        data.put("errorsLast24h", day.total() - day.success());

        // 当前节点并发（进程视角·本地区）
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("inFlight", inflightMetrics.inFlight());
        local.put("peak", inflightMetrics.peak());
        local.put("total", inflightMetrics.total());
        local.put("rejected", inflightMetrics.rejected());
        data.put("local", local);

        // 并发槽水位（渠道密钥 ZSET 槽·全 Key 汇总）
        long occupied = 0;
        long schedulableKeys = 0;
        List<Map<String, Object>> keySnapshots = runtimeViewService.keySnapshots(null);
        for (Map<String, Object> key : keySnapshots) {
            occupied += asLong(key.get("occupied"));
            if (Boolean.TRUE.equals(key.get("schedulable"))) {
                schedulableKeys++;
            }
        }
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("occupied", occupied);
        slots.put("schedulableKeys", schedulableKeys);
        slots.put("totalKeys", keySnapshots.size());
        data.put("slots", slots);

        // 集群节点（本系统真实等价物）
        List<NaiNode> nodes = nodeMapper.selectList(null);
        long online = nodes.stream().filter(n -> n.getUpdateDt() != null
                && Duration.between(n.getUpdateDt(), now).getSeconds() <= NODE_OFFLINE_SECONDS).count();
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("onlineNodes", online);
        cluster.put("totalNodes", nodes.size());
        data.put("cluster", cluster);

        // 降级切换统计（故障转移事件按原因分组·近 24h）
        List<Map<String, Object>> fallbacks = new ArrayList<>();
        long fallbackTotal = 0;
        for (Map<String, Object> r : failoverMapper.selectMaps(new QueryWrapper<NaiFailoverEvent>()
                .select("reason as k, count(*) as cnt")
                .ge("create_dt", now.minusHours(LIVE_LOOKBACK_HOURS)).groupBy("reason").orderByDesc("cnt"))) {
            if (r == null) {
                continue;
            }
            long cnt = asLong(r.get("cnt"));
            fallbackTotal += cnt;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", String.valueOf(r.get("k")));
            row.put("total", cnt);
            fallbacks.add(row);
        }
        data.put("fallbackTotal", fallbackTotal);
        data.put("fallbacks", fallbacks);

        // 最近错误（24h 内 http_status>=400 倒序·渠道名补全）
        Map<Long, String> channelNames = channelNames();
        List<Map<String, Object>> recentErrors = new ArrayList<>();
        for (RequestLogRecord r : logStore.recentErrors(now.minusHours(LIVE_LOOKBACK_HOURS), RECENT_ERRORS_LIMIT)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestId", r.getRequestId());
            row.put("errorCode", r.getErrorCode());
            row.put("httpStatus", r.getHttpStatus());
            row.put("model", r.getModel());
            row.put("channelName", r.getChannelId() == null ? null
                    : channelNames.getOrDefault(r.getChannelId(), "渠道 " + r.getChannelId()));
            row.put("createDt", r.getCreateDt());
            recentErrors.add(row);
        }
        data.put("recentErrors", recentErrors);

        // 熔断现状（运行时不可调度的渠道/Key）+ 运维建议（alerts 复用）
        List<Map<String, Object>> circuits = new ArrayList<>();
        for (Map<String, Object> ch : runtimeViewService.channelSnapshots()) {
            if (Boolean.FALSE.equals(ch.get("schedulable"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", "channel");
                row.put("name", ch.get("name"));
                row.put("healthScore", ch.get("healthScore"));
                circuits.add(row);
            }
        }
        for (Map<String, Object> key : keySnapshots) {
            if (Boolean.FALSE.equals(key.get("schedulable"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", "key");
                row.put("name", "Key #" + key.get("keyId"));
                row.put("channelName", channelNames.get(asNullableLong(key.get("channelId"))));
                row.put("healthScore", key.get("healthScore"));
                circuits.add(row);
            }
        }
        data.put("circuits", circuits);
        data.put("alerts", alerts);
        return data;
    }

    /** 渠道性能聚合（上游服务性能）：summary 总体行 + 每渠道行 + 渠道×时间桶曲线。 */
    public Map<String, Object> providers(PerfFilter filter, int lookbackHours, int slowThresholdMs, int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(lookbackHours);

        List<ChannelPerfStat> overall = logStore.channelPerf(filter, since, now, slowThresholdMs, false);
        List<ChannelPerfStat> grouped = logStore.channelPerf(filter, since, now, slowThresholdMs, true);
        grouped.sort((a, b) -> Long.compare(b.total(), a.total()));
        if (grouped.size() > limit) {
            grouped = grouped.subList(0, limit);
        }
        Map<Long, String> channelNames = channelNames();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("summary", perfRow(overall.isEmpty() ? null : overall.get(0), null, channelNames));
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ChannelPerfStat stat : grouped) {
            providers.add(perfRow(stat, stat.key(), channelNames));
        }
        data.put("providers", providers);

        // 渠道×时间桶曲线（TPS/首字趋势·只保留入榜渠道）
        Set<String> topKeys = new HashSet<>();
        for (ChannelPerfStat stat : grouped) {
            topKeys.add(stat.key());
        }
        long rangeSecs = Math.max(Duration.between(since, now).getSeconds(), 1);
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (PerfBucket bucket : logStore.channelPerfTimeline(filter, since, now, TIMELINE_SEGMENTS)) {
            if (bucket.key() == null || !topKeys.contains(bucket.key()) || bucket.total() == 0) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("ts", since.plusSeconds(rangeSecs * bucket.segmentIdx() / TIMELINE_SEGMENTS));
            point.put("channelId", bucket.key());
            point.put("channelName", channelNames.getOrDefault(asNullableLong(bucket.key()), "渠道 " + bucket.key()));
            point.put("requestCount", bucket.total());
            point.put("successRate", (double) bucket.success() / bucket.total());
            point.put("avgOutputTps", bucket.genMsSum() > 0 ? bucket.tokensCount() * 1000.0 / bucket.genMsSum() : null);
            point.put("avgFirstByteMs", bucket.ttftCount() > 0 ? bucket.ttftSum() / bucket.ttftCount() : null);
            timeline.add(point);
        }
        timeline.sort((a, b) -> String.valueOf(a.get("ts")).compareTo(String.valueOf(b.get("ts"))));
        data.put("timeline", timeline);
        return data;
    }

    /** 按日延迟分位时序（响应 + 首字 双指标组）。 */
    public List<PercentileDay> percentiles(int days) {
        LocalDateTime now = LocalDateTime.now();
        return logStore.percentileDaily(now.minusDays(days), now);
    }

    /** 错误分布 + 按日错误趋势（趋势复用 dailyStats：errors = total - success）。 */
    public Map<String, Object> errors(int days) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(days);
        List<Map<String, Object>> distribution = new ArrayList<>();
        for (ErrorCount e : logStore.errorBreakdown(since, now, null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", e.category());
            row.put("count", e.count());
            distribution.add(row);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (DailyStat d : logStore.dailyStats(since)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.date());
            row.put("total", d.total() - d.success());
            trend.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("distribution", distribution);
        data.put("trend", trend);
        return data;
    }

    // ===== 合成工具 =====

    /** 渠道性能行（summary 与每渠道行共形·派生值在此算齐）。 */
    private static Map<String, Object> perfRow(ChannelPerfStat stat, String key, Map<Long, String> channelNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (key != null) {
            row.put("channelId", key);
            row.put("channelName", channelNames.getOrDefault(asNullableLong(key), "渠道 " + key));
        }
        long total = stat == null ? 0 : stat.total();
        long success = stat == null ? 0 : stat.success();
        row.put("requestCount", total);
        row.put("successCount", success);
        row.put("errorCount", total - success);
        row.put("successRate", total > 0 ? (double) success / total * 100 : null);
        row.put("avgOutputTps", stat != null && stat.genMsSum() > 0
                ? stat.tokensCount() * 1000.0 / stat.genMsSum() : null);
        row.put("avgFirstByteMs", stat != null && stat.ttftCount() > 0 ? stat.ttftSumMs() / stat.ttftCount() : null);
        row.put("avgResponseMs", stat != null && stat.responseSamples() > 0
                ? stat.latencySumMs() / stat.responseSamples() : null);
        row.put("p90ResponseMs", stat == null ? null : stat.p90DurationMs());
        row.put("p99ResponseMs", stat == null ? null : stat.p99DurationMs());
        row.put("p90FirstByteMs", stat == null ? null : stat.p90TtftMs());
        row.put("p99FirstByteMs", stat == null ? null : stat.p99TtftMs());
        row.put("slowRequestCount", stat == null ? 0 : stat.slowCount());
        row.put("responseSamples", stat == null ? 0 : stat.responseSamples());
        row.put("firstByteSamples", stat == null ? 0 : stat.ttftCount());
        return row;
    }

    private Map<Long, String> channelNames() {
        Map<Long, String> names = new HashMap<>();
        for (NaiChannel ch : channelMapper.selectList(null)) {
            names.put(ch.getId(), ch.getName());
        }
        return names;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Long asNullableLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
