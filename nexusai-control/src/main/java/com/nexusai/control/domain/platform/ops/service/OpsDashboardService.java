package com.nexusai.control.domain.platform.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.control.domain.platform.ops.algo.TrafficMath;
import com.nexusai.control.domain.platform.ops.job.OpsAlertEvaluatorJob;
import com.nexusai.control.domain.platform.ops.vo.OpsAlertSettingsVo;
import com.nexusai.control.domain.platform.ops.vo.OpsErrorCategoryVo;
import com.nexusai.control.domain.platform.ops.vo.OpsLatencyBucketVo;
import com.nexusai.control.domain.platform.ops.vo.OpsRealtimeTrafficVo;
import com.nexusai.control.domain.platform.ops.vo.OpsThresholdsVo;
import com.nexusai.control.domain.platform.ops.vo.OpsTrendVo;
import com.nexusai.control.domain.platform.ops.vo.RateTripleVo;
import com.nexusai.control.domain.monitor.observe.service.ObserveStatService;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.observe.metrics.RelayInflightMetrics;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.ChannelPerfStat;
import com.nexusai.persistence.log.stat.ErrorCount;
import com.nexusai.persistence.log.stat.LatencyBucket;
import com.nexusai.persistence.log.stat.PerfBucket;
import com.nexusai.persistence.log.stat.PerfFilter;
import com.nexusai.persistence.mapper.NaiAlertEventMapper;
import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.po.NaiAlertEvent;
import com.nexusai.persistence.po.NaiFailoverEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.nexusai.persistence.log.stat.ErrorTrendBucket;

/**
 * 运维监控仪表盘服务（概念映射 platform→协议/渠道、account→渠道 Key）：
 * <ul>
 *   <li><b>overview</b>：合并快照——窗口总量/成功率/错误率/延迟与 TTFT
 *       聚合（avg/P90/P99）+ 实时 QPS/TPS（current/peak/avg）+ 系统资源（JVM CPU/堆/线程/DB/Redis）
 *       + 健康分诊断 + 活跃告警计数；</li>
 *   <li><b>trend</b>：吞吐（QPS/TPS 双轴）+ 错误 + 故障转移率三曲线一次拉（分段桶·跨渠道 Java 汇总）；</li>
 *   <li><b>latencyHistogram / errorDistribution</b>：延迟直方图（固定 6 桶）与错误分类环图。</li>
 * </ul>
 * 全部读时聚合直查日志 store（与性能分析页同决策：不建预聚合表，量大切 ClickHouse driver）。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class OpsDashboardService {

    /** 直方图固定桶顺序（缺桶补零） */
    private static final List<String> HISTOGRAM_ORDER =
            List.of("0-100ms", "100-500ms", "500ms-1s", "1-3s", "3-10s", "10s+");

    /** 阈值 KV 键（运维监控页可调·healthScore 与前端着色共用） */
    public static final String CFG_SLA_MIN = "ops.sla_percent_min";
    public static final String CFG_ERROR_RATE_MAX = "ops.error_rate_percent_max";
    public static final String CFG_P99_MAX = "ops.p99_latency_ms_max";
    public static final String CFG_TTFT_P99_MAX = "ops.ttft_p99_ms_max";

    /** 阈值缺省（KV 未配置时的常用量级） */
    private static final int DEFAULT_SLA_PERCENT_MIN = 95;
    private static final int DEFAULT_ERROR_RATE_PERCENT_MAX = 5;
    private static final int DEFAULT_P99_LATENCY_MS_MAX = 30_000;
    private static final int DEFAULT_TTFT_P99_MS_MAX = 5_000;

    /** 告警评估间隔钳制（秒）与诊断起评样本量 */
    private static final int EVAL_INTERVAL_MIN_SECONDS = 10;
    private static final int EVAL_INTERVAL_MAX_SECONDS = 3600;
    private static final long DIAGNOSIS_MIN_SAMPLES = 10;

    /** 慢请求阈值（ms·channelPerf 聚合的 slow_request 判定线） */
    private static final int SLOW_REQUEST_THRESHOLD_MS = 30_000;

    /** 诊断级别与告警状态字面量 */
    private static final String LEVEL_CRITICAL = "critical";
    private static final String LEVEL_WARNING = "warning";
    private static final String STATUS_FIRING = "firing";

    /** 健康分起评满分与扣分权重（critical 重扣·warning 轻扣） */
    private static final int HEALTH_SCORE_MAX = 100;
    private static final int HEALTH_PENALTY_CRITICAL = 30;
    private static final int HEALTH_PENALTY_WARNING = 10;

    private final RequestLogStore logStore;
    private final RelayInflightMetrics inflightMetrics;
    private final ObserveStatService observeStatService;
    private final SystemConfigService config;
    private final NaiFailoverEventMapper failoverMapper;
    private final NaiAlertEventMapper alertEventMapper;
    private final SystemMetricsCollector systemMetricsCollector;

    /** 合并快照（窗口聚合 + 实时流量 + 系统资源 + 健康分）。channelId 非 null 时按渠道筛选。 */
    public Map<String, Object> overview(LocalDateTime since, LocalDateTime until, Long channelId) {
        LocalDateTime now = until;
        int rangeMinutes = (int) Math.max(Duration.between(since, until).toMinutes(), 1);
        PerfFilter filter = new PerfFilter(channelId, null, null, null);

        // 窗口聚合（总体单行：计数器 + P90/P99 双指标）
        List<ChannelPerfStat> overall = logStore.channelPerf(filter, since, now, SLOW_REQUEST_THRESHOLD_MS, false);
        ChannelPerfStat stat = overall.isEmpty() ? null : overall.get(0);
        long total = stat == null ? 0 : stat.total();
        long success = stat == null ? 0 : stat.success();
        long errors = total - success;
        double successRate = total > 0 ? (double) success / total * 100 : 100;
        double errorRate = total > 0 ? (double) errors / total * 100 : 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("rangeMinutes", rangeMinutes);
        data.put("requestCount", total);
        data.put("successCount", success);
        data.put("errorCount", errors);
        data.put("successRate", TrafficMath.round2(successRate));
        data.put("errorRate", TrafficMath.round2(errorRate));
        data.put("tokenConsumed", stat == null ? 0 : stat.tokensCount());

        Map<String, Object> duration = new LinkedHashMap<>();
        duration.put("avg", stat != null && stat.responseSamples() > 0
                ? stat.latencySumMs() / stat.responseSamples() : null);
        duration.put("p90", stat == null ? null : stat.p90DurationMs());
        duration.put("p99", stat == null ? null : stat.p99DurationMs());
        data.put("duration", duration);
        Map<String, Object> ttft = new LinkedHashMap<>();
        ttft.put("avg", stat != null && stat.ttftCount() > 0 ? stat.ttftSumMs() / stat.ttftCount() : null);
        ttft.put("p90", stat == null ? null : stat.p90TtftMs());
        ttft.put("p99", stat == null ? null : stat.p99TtftMs());
        data.put("ttft", ttft);

        // 实时流量（QPS/TPS current=最近60s · peak/avg=窗口逐分钟桶）
        data.put("traffic", traffic(filter, since, now, rangeMinutes));

        // Top 渠道钻取芯片（点击设全局渠道筛选）
        List<Map<String, Object>> topChannels = new ArrayList<>();
        for (ChannelPerfStat c : logStore.channelPerf(new PerfFilter(null, null, null, null),
                since, now, SLOW_REQUEST_THRESHOLD_MS, true).stream()
                .filter(c -> c.key() != null)
                .sorted((a, b) -> Long.compare(b.total(), a.total()))
                .limit(5).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channelId", Long.parseLong(c.key()));
            row.put("total", c.total());
            row.put("errors", c.total() - c.success());
            topChannels.add(row);
        }
        data.put("topChannels", topChannels);

        // 并发（进程视角）
        Map<String, Object> concurrency = new LinkedHashMap<>();
        concurrency.put("inFlight", inflightMetrics.inFlight());
        concurrency.put("peak", inflightMetrics.peak());
        concurrency.put("rejected", inflightMetrics.rejected());
        data.put("concurrency", concurrency);

        // 系统资源（JVM/DB/Redis/集群节点）
        data.put("system", systemMetricsCollector.snapshot(now));

        // 阈值 + 健康分诊断（既有 alerts 合成 + 阈值检查）
        OpsThresholdsVo thresholds = thresholds();
        data.put("thresholds", thresholds);
        List<Map<String, Object>> diagnosis = new ArrayList<>();
        for (Map<String, Object> a : observeStatService.alerts()) {
            if (LEVEL_CRITICAL.equals(a.get("level")) || LEVEL_WARNING.equals(a.get("level"))) {
                diagnosis.add(a);
            }
        }
        addThresholdDiagnosis(diagnosis, thresholds, successRate, errorRate,
                stat == null ? null : stat.p99DurationMs(), stat == null ? null : stat.p99TtftMs(), total);
        int score = HEALTH_SCORE_MAX;
        for (Map<String, Object> d : diagnosis) {
            score -= LEVEL_CRITICAL.equals(d.get("level")) ? HEALTH_PENALTY_CRITICAL : HEALTH_PENALTY_WARNING;
        }
        data.put("healthScore", Math.max(0, Math.min(HEALTH_SCORE_MAX, score)));
        data.put("diagnosis", diagnosis);

        // 活跃告警计数（firing）
        data.put("firingAlerts", alertEventMapper.selectCount(
                new LambdaQueryWrapper<NaiAlertEvent>()
                        .eq(NaiAlertEvent::getStatus, STATUS_FIRING)));
        return data;
    }

    /** 三趋势一次拉：吞吐（QPS/TPS）+ 错误三线（全部/上游/限流）+ 故障转移率。 */
    public OpsTrendVo trend(LocalDateTime since, LocalDateTime now, int segments, Long channelId) {
        long segSecs = Math.max(Duration.between(since, now).getSeconds() / segments, 1);
        PerfFilter filter = new PerfFilter(channelId, null, null, null);

        // 跨渠道分段汇总（七计数器 → QPS/TPS/错误）
        long[] totals = new long[segments];
        long[] successes = new long[segments];
        long[] tokens = new long[segments];
        for (PerfBucket b : logStore.channelPerfTimeline(filter, since, now, segments)) {
            int i = Math.min(b.segmentIdx(), segments - 1);
            totals[i] += b.total();
            successes[i] += b.success();
            tokens[i] += b.tokensCount();
        }

        // 错误三线（请求错误/上游错误/业务限流）
        long[] upstream = new long[segments];
        long[] limited = new long[segments];
        for (ErrorTrendBucket b
                : logStore.errorTrendTimeline(since, now, segments, channelId)) {
            int i = Math.min(b.segmentIdx(), segments - 1);
            upstream[i] += b.upstreamErrors();
            limited[i] += b.limitedErrors();
        }

        // 故障转移事件按段分桶（事件量小·Java 端分桶保持方言无关）
        long[] failovers = new long[segments];
        for (NaiFailoverEvent e : failoverMapper.selectList(
                new LambdaQueryWrapper<NaiFailoverEvent>()
                        .select(NaiFailoverEvent::getCreateDt).ge(NaiFailoverEvent::getCreateDt, since))) {
            if (e == null || e.getCreateDt() == null) {
                continue;
            }
            long offset = Duration.between(since, e.getCreateDt()).getSeconds();
            int i = (int) Math.min(Math.max(offset / segSecs, 0), segments - 1);
            failovers[i]++;
        }

        List<OpsTrendVo.Point> points = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            points.add(new OpsTrendVo.Point(
                    since.plusSeconds(segSecs * i),
                    totals[i],
                    totals[i] - successes[i],
                    upstream[i],
                    limited[i],
                    TrafficMath.round2((double) totals[i] / segSecs),
                    TrafficMath.round2((double) tokens[i] / segSecs),
                    failovers[i],
                    totals[i] > 0 ? TrafficMath.round2((double) failovers[i] / totals[i] * 100) : 0));
        }
        return new OpsTrendVo(now, segSecs, points);
    }

    /** 延迟直方图（固定 6 桶补零）。 */
    public List<OpsLatencyBucketVo> latencyHistogram(LocalDateTime since, LocalDateTime until, Long channelId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String b : HISTOGRAM_ORDER) {
            counts.put(b, 0L);
        }
        for (LatencyBucket b : logStore.latencyHistogram(since, until, channelId)) {
            counts.computeIfPresent(b.bucket(), (k, v) -> v + b.count());
        }
        List<OpsLatencyBucketVo> out = new ArrayList<>();
        counts.forEach((bucket, count) -> out.add(new OpsLatencyBucketVo(bucket, count)));
        return out;
    }

    /** 错误分类分布（error_code / HTTP_xxx 分组）。 */
    public List<OpsErrorCategoryVo> errorDistribution(LocalDateTime since, LocalDateTime until, Long channelId) {
        List<OpsErrorCategoryVo> out = new ArrayList<>();
        for (ErrorCount e : logStore.errorBreakdown(since, until, channelId)) {
            out.add(new OpsErrorCategoryVo(e.category(), e.count()));
        }
        return out;
    }

    /** 阈值配置（KV·缺省常用量级）。 */
    public OpsThresholdsVo thresholds() {
        return new OpsThresholdsVo(
                config.getInt(CFG_SLA_MIN, DEFAULT_SLA_PERCENT_MIN),
                config.getInt(CFG_ERROR_RATE_MAX, DEFAULT_ERROR_RATE_PERCENT_MAX),
                config.getInt(CFG_P99_MAX, DEFAULT_P99_LATENCY_MS_MAX),
                config.getInt(CFG_TTFT_P99_MAX, DEFAULT_TTFT_P99_MS_MAX));
    }

    public void saveThresholds(Map<String, Object> body) {
        putIfPresent(body, "slaPercentMin", CFG_SLA_MIN);
        putIfPresent(body, "errorRatePercentMax", CFG_ERROR_RATE_MAX);
        putIfPresent(body, "p99LatencyMsMax", CFG_P99_MAX);
        putIfPresent(body, "ttftP99MsMax", CFG_TTFT_P99_MAX);
    }

    /** 告警评估设置（开关 + 评估间隔）。 */
    public OpsAlertSettingsVo alertSettings() {
        boolean enabled = config.getBool(OpsAlertEvaluatorJob.CFG_EVAL_ENABLED, true);
        int intervalSeconds = clampInterval(config.getInt(OpsAlertEvaluatorJob.CFG_EVAL_INTERVAL, 60));
        return new OpsAlertSettingsVo(enabled, intervalSeconds);
    }

    public void saveAlertSettings(Map<String, Object> body) {
        if (body.get("enabled") instanceof Boolean enabled) {
            config.put(OpsAlertEvaluatorJob.CFG_EVAL_ENABLED, enabled, "告警评估开关");
        }
        if (body.get("intervalSeconds") instanceof Number n) {
            config.put(OpsAlertEvaluatorJob.CFG_EVAL_INTERVAL, clampInterval(n.intValue()), "告警评估间隔秒");
        }
    }

    private static int clampInterval(int seconds) {
        return Math.max(EVAL_INTERVAL_MIN_SECONDS, Math.min(seconds, EVAL_INTERVAL_MAX_SECONDS));
    }

    private void putIfPresent(Map<String, Object> body, String field, String cfgKey) {
        if (body.containsKey(field) && body.get(field) != null) {
            config.put(cfgKey, String.valueOf(body.get(field)), "运维监控阈值");
        }
    }

    // ===== 内部合成 =====

    /** 实时流量：current=最近 60s 单桶；peak/avg=窗口逐分钟桶最大/平均。 */
    private Map<String, Object> traffic(PerfFilter filter, LocalDateTime since, LocalDateTime now, int rangeMinutes) {
        long curTotal = 0;
        long curTokens = 0;
        for (PerfBucket b : logStore.channelPerfTimeline(filter, now.minusSeconds(60), now, 1)) {
            curTotal += b.total();
            curTokens += b.tokensCount();
        }
        int minuteSegs = Math.max(Math.min(rangeMinutes, 240), 1);
        long[] reqPerSeg = new long[minuteSegs];
        long[] tokPerSeg = new long[minuteSegs];
        for (PerfBucket b : logStore.channelPerfTimeline(filter, since, now, minuteSegs)) {
            int i = Math.min(b.segmentIdx(), minuteSegs - 1);
            reqPerSeg[i] += b.total();
            tokPerSeg[i] += b.tokensCount();
        }
        long windowSecs = rangeMinutes * 60L;
        long segSecs = Math.max(windowSecs / minuteSegs, 1);
        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("qps", rateVo(TrafficMath.rates(curTotal, reqPerSeg, segSecs, windowSecs)));
        traffic.put("tps", rateVo(TrafficMath.rates(curTokens, tokPerSeg, segSecs, windowSecs)));
        return traffic;
    }

    private static RateTripleVo rateVo(TrafficMath.Rates r) {
        return new RateTripleVo(r.current(), r.peak(), r.avg());
    }

    /**
     * 实时流量窗口（窗口可切 60s/300s/1800s/3600s）：
     * current=最近 60s 单桶速率；peak/avg=窗口内细分桶最大/全窗均值。
     */
    public OpsRealtimeTrafficVo realtimeTraffic(int windowSeconds, Long channelId) {
        int window = Math.max(60, Math.min(windowSeconds, 3600));
        LocalDateTime now = LocalDateTime.now();
        PerfFilter filter = new PerfFilter(channelId, null, null, null);
        long curTotal = 0;
        long curTokens = 0;
        for (PerfBucket b : logStore.channelPerfTimeline(filter, now.minusSeconds(60), now, 1)) {
            curTotal += b.total();
            curTokens += b.tokensCount();
        }
        int segments = window <= 60 ? 6 : Math.min(window / 60, 60);
        long segSecs = window / segments;
        long[] req = new long[segments];
        long[] tok = new long[segments];
        for (PerfBucket b : logStore.channelPerfTimeline(filter, now.minusSeconds(window), now, segments)) {
            int i = Math.min(b.segmentIdx(), segments - 1);
            req[i] += b.total();
            tok[i] += b.tokensCount();
        }
        return new OpsRealtimeTrafficVo(window,
                rateVo(TrafficMath.rates(curTotal, req, segSecs, window)),
                rateVo(TrafficMath.rates(curTokens, tok, segSecs, window)));
    }

    /** 阈值检查并入诊断列表（量太小不诊断·防空窗误报）。 */
    private static void addThresholdDiagnosis(List<Map<String, Object>> diagnosis, OpsThresholdsVo t,
                                              double successRate, double errorRate,
                                              Long p99, Long ttftP99, long total) {
        if (total < DIAGNOSIS_MIN_SAMPLES) {
            return;
        }
        int slaMin = t.slaPercentMin();
        int errMax = t.errorRatePercentMax();
        int p99Max = t.p99LatencyMsMax();
        int ttftMax = t.ttftP99MsMax();
        if (successRate < slaMin) {
            diagnosis.add(diag(LEVEL_CRITICAL, "SLA 低于阈值：" + TrafficMath.round2(successRate) + "% < " + slaMin + "%"));
        }
        if (errorRate >= errMax) {
            diagnosis.add(diag(LEVEL_WARNING, "错误率超阈值：" + TrafficMath.round2(errorRate) + "% ≥ " + errMax + "%"));
        }
        if (p99 != null && p99 >= p99Max) {
            diagnosis.add(diag(LEVEL_WARNING, "P99 延迟超阈值：" + p99 + "ms ≥ " + p99Max + "ms"));
        }
        if (ttftP99 != null && ttftP99 >= ttftMax) {
            diagnosis.add(diag(LEVEL_WARNING, "TTFT P99 超阈值：" + ttftP99 + "ms ≥ " + ttftMax + "ms"));
        }
    }

    private static Map<String, Object> diag(String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("message", message);
        return m;
    }

}
