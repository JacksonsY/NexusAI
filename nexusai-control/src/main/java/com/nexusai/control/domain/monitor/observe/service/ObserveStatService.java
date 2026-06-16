package com.nexusai.control.domain.monitor.observe.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexusai.control.domain.monitor.observe.vo.ChannelSuccessRateVo;
import com.nexusai.control.domain.monitor.observe.vo.DailyPointVo;
import com.nexusai.control.domain.monitor.observe.vo.DashboardStatsVo;
import com.nexusai.control.domain.monitor.observe.vo.DimensionStatVo;
import com.nexusai.control.domain.monitor.observe.vo.PerfDailyPointVo;
import com.nexusai.control.domain.monitor.observe.vo.PerfStatVo;
import com.nexusai.control.domain.monitor.observe.vo.RequestDetailVo;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.DailyStat;
import com.nexusai.persistence.log.stat.DimensionCount;
import com.nexusai.persistence.log.stat.DimensionStat;
import com.nexusai.persistence.log.stat.PerfDailyStat;
import com.nexusai.persistence.log.stat.PerfStat;
import com.nexusai.persistence.log.stat.RangeTotals;
import com.nexusai.persistence.log.stat.StatusCount;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.mapper.NaiUsageLogMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiFailoverEvent;
import com.nexusai.persistence.po.NaiUsageLog;
import com.nexusai.router.health.ChannelHealthManager;
import com.nexusai.router.health.RoutingHealthStat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测域服务：请求日志统计聚合（成功率/分位/状态码分布）、时序、贯穿钻取、存量、告警阈值计算。
 * <p>纯阻塞业务逻辑（控制器经 boundedElastic 包裹调用）；从 {@code ObserveAdminController} 下沉——控制器只编排不算账。
 * 请求日志一律经 {@link RequestLogStore} SPI 读（不耦合存储 driver）。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class ObserveStatService {

    /** 渠道成功率排行展示条数（Dashboard·Top N）。 */
    private static final int CHANNEL_RANK_LIMIT = 8;
    /** 告警判定：近 1 小时错误率阈值（>30% 触发）。 */
    private static final double ERROR_RATE_ALERT_THRESHOLD = 0.3;
    /** 告警判定：近 1 小时最小样本量（不足不评估错误率）。 */
    private static final int ERROR_RATE_MIN_SAMPLES = 20;
    /** 累计统计起点（足够早的固定原点）。 */
    private static final LocalDateTime STATS_ORIGIN = LocalDateTime.of(2000, 1, 1, 0, 0);
    /** 故障转移事件查询的最大返回条数。 */
    private static final int FAILOVER_MAX_LIMIT = 500;

    private final RequestLogStore requestLogStore;
    private final NaiUsageLogMapper usageLogMapper;
    private final NaiChannelMapper channelMapper;
    private final ChannelHealthManager channelHealth;
    private final NaiFailoverEventMapper failoverMapper;

    /**
     * Dashboard 运维总览（去钱化）：一次拉齐
     * <ul>
     *   <li>累计请求总量 + 全程成功率；</li>
     *   <li>请求环比计数（今日/近7天/前7天/本月）—— 走 {@link RequestLogStore} 含失败口径；</li>
     *   <li>近7天成功率 vs 前7天成功率（环比）；</li>
     *   <li>Token 用量（今日/近7天/本月/累计 的 input/output/cached）—— 走 {@code nai_usage_log}（仅成功调用产 token）；</li>
     *   <li>渠道成功率排行（近7天·Top 8）。</li>
     * </ul>
     * 钱（成本/计费）一律不出现——计费在 业务面，网关只做运维计量。
     */
    public DashboardStatsVo dashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime weekStart = today.minusDays(6).atStartOfDay();        // 近 7 天（含今天）
        LocalDateTime prevWeekStart = today.minusDays(13).atStartOfDay();   // 前 7 天
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        RangeTotals all = requestLogStore.totalsBetween(STATS_ORIGIN, now);
        RangeTotals todayT = requestLogStore.totalsBetween(todayStart, now);
        RangeTotals weekT = requestLogStore.totalsBetween(weekStart, now);
        RangeTotals prevWeekT = requestLogStore.totalsBetween(prevWeekStart, weekStart);
        RangeTotals monthT = requestLogStore.totalsBetween(monthStart, now);

        DashboardStatsVo.RequestStats requestStats = new DashboardStatsVo.RequestStats(
                todayT.total(), weekT.total(), prevWeekT.total(), monthT.total());

        DashboardStatsVo.SuccessStats successStats = new DashboardStatsVo.SuccessStats(
                rate(weekT), rate(prevWeekT), weekT.success(), weekT.total() - weekT.success());

        Map<String, Long> tokenStats = new LinkedHashMap<>();
        tokenStats.putAll(tokenSum("today", todayStart, now));
        tokenStats.putAll(tokenSum("week", weekStart, now));
        tokenStats.putAll(tokenSum("month", monthStart, now));
        tokenStats.putAll(tokenSum("all", STATS_ORIGIN, now));

        Map<Long, String> channelNames = new HashMap<>();
        for (NaiChannel c : channelMapper.selectList(null)) {
            channelNames.put(c.getId(), c.getName());
        }
        List<DimensionCount> breakdown = new ArrayList<>(requestLogStore.channelBreakdown(weekStart));
        breakdown.sort(Comparator.comparingLong(DimensionCount::total).reversed());
        List<ChannelSuccessRateVo> channelRank = new ArrayList<>();
        for (DimensionCount dc : breakdown) {
            if (channelRank.size() >= CHANNEL_RANK_LIMIT) {
                break;
            }
            long t = dc.total();
            double successRate = t == 0 ? 100.0 : Math.round((double) dc.success() / t * 1000) / 10.0;
            channelRank.add(new ChannelSuccessRateVo(dc.key(),
                    channelNames.getOrDefault(dc.key(), "#" + dc.key()),
                    dc.success(), t - dc.success(), successRate));
        }

        return new DashboardStatsVo(all.total(), rate(all), requestStats, successStats, tokenStats, channelRank);
    }

    /** 成功率（四位小数·空窗记 1.0 不惩罚）。 */
    private double rate(RangeTotals t) {
        return t.total() == 0 ? 1.0 : Math.round((double) t.success() / t.total() * 10000) / 10000.0;
    }

    /** 区间 [since, until) 的 token 三态汇总（仅成功调用产 token），键加周期前缀（如 todayInput/weekOutput）。 */
    private Map<String, Long> tokenSum(String period, LocalDateTime since, LocalDateTime until) {
        List<Map<String, Object>> rows = usageLogMapper.selectMaps(new QueryWrapper<NaiUsageLog>()
                .select("sum(prompt_tokens) as input, sum(completion_tokens) as output, sum(cache_tokens) as cached")
                .ge("create_dt", since).lt("create_dt", until));
        // 空区间时 sum() 三列全 null，MyBatis 把整行映射为 null 元素 → 不可用 findFirst（Optional.of(null) 抛 NPE）
        Map<String, Object> r = rows.isEmpty() ? null : rows.get(0);
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(period + "Input", r == null ? 0L : nzLong(r.get("input")));
        m.put(period + "Output", r == null ? 0L : nzLong(r.get("output")));
        m.put(period + "Cached", r == null ? 0L : nzLong(r.get("cached")));
        return m;
    }

    private static long nzLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }


    /** 维度分析白名单：dim → 日志表列名（防 SQL 注入·绝不接受任意外部串拼进 SQL）。 */
    private static final Map<String, String> DIM_COLUMNS = Map.of(
            "channel", "channel_id", "model", "model", "key", "key_id");

    /** 时间窗 → 起点（累计/本月/近7天/今日）。 */
    private static LocalDateTime windowStart(String window) {
        return switch (window == null ? "allTime" : window) {
            case "day" -> LocalDate.now().atStartOfDay();
            case "week" -> LocalDateTime.now().minusDays(7);
            case "month" -> LocalDateTime.now().minusDays(30);
            default -> LocalDateTime.of(2000, 1, 1, 0, 0);
        };
    }

    /** Dashboard 维度分析（去钱化·按维度统计请求量/token）：按请求量取 Top limit（含 token 三态）。 */
    public List<DimensionStatVo> dimensionStats(String dim, String window, int limit) {
        String column = DIM_COLUMNS.get(dim);
        if (column == null) {
            return List.of();
        }
        List<DimensionStat> rows =
                requestLogStore.statsByDimension(column, windowStart(window));
        rows.sort(Comparator.comparingLong(DimensionStat::total).reversed());
        List<DimensionStatVo> out = new ArrayList<>();
        for (DimensionStat r : rows.subList(0, Math.min(limit, rows.size()))) {
            out.add(new DimensionStatVo(r.key(), r.total(), r.success(),
                    r.inputTokens(), r.outputTokens(), r.cachedTokens(),
                    r.inputTokens() + r.outputTokens() + r.cachedTokens()));
        }
        return out;
    }

    /** Dashboard 性能最快榜：窗口聚合吞吐降序 + 置信度（样本量相对中位数）取 Top limit。 */
    public List<PerfStatVo> performance(String dim, String window, int limit) {
        String column = "channel".equals(dim) ? "channel_id" : "model";
        List<PerfStat> rows =
                requestLogStore.perfByDimension(column, windowStart(window));
        double median = rows.isEmpty() ? 0
                : rows.stream().mapToLong(PerfStat::requestCount)
                        .sorted().toArray()[rows.size() / 2];
        rows.sort(Comparator.comparingDouble(PerfStat::throughput).reversed());
        List<PerfStatVo> out = new ArrayList<>();
        for (PerfStat r : rows.subList(0, Math.min(limit, rows.size()))) {
            out.add(new PerfStatVo(r.key(), Math.round(r.throughput() * 10) / 10.0,
                    r.tokensCount(), r.avgTtftMs(), r.avgDurationMs(), r.requestCount(),
                    confidence(r.requestCount(), median)));
        }
        return out;
    }

    /** Dashboard 性能时序：近 days 天逐日 × 维度吞吐/TTFT/请求量。 */
    public List<PerfDailyPointVo> performanceDaily(String dim, int days) {
        String column = "channel".equals(dim) ? "channel_id" : "model";
        LocalDateTime since = LocalDate.now().minusDays(Math.max(days, 1) - 1).atStartOfDay();
        List<PerfDailyPointVo> out = new ArrayList<>();
        for (PerfDailyStat r : requestLogStore.perfDaily(column, since)) {
            out.add(new PerfDailyPointVo(r.date(), r.key(),
                    Math.round(r.throughput() * 10) / 10.0, r.avgTtftMs(), r.requestCount()));
        }
        return out;
    }

    /** Dashboard「每日请求概览」面积图：近 days 天按天聚合请求量 + token 三态。 */
    public List<DailyPointVo> dailySeries(int days) {
        LocalDateTime since = LocalDate.now().minusDays(Math.max(days, 1) - 1).atStartOfDay();
        List<DailyPointVo> out = new ArrayList<>();
        for (DailyStat r : requestLogStore.dailyStats(since)) {
            out.add(new DailyPointVo(r.date(), r.total(), r.success(),
                    r.inputTokens(), r.outputTokens(), r.cachedTokens(),
                    r.inputTokens() + r.outputTokens() + r.cachedTokens()));
        }
        return out;
    }

    /**
     * 告警计算（实时）：渠道全灭/部分熔断 + 近 1 小时错误率（http_status&gt;=400）&gt;30%。
     * <p>返回 {@code List<Map>} 而非 VO：消费方含跨分区 {@code platform.ops.OpsDashboardService}
     * （把告警 Map 直接并入 OpsOverview.diagnosis 列表并回读 {@code level}）——契约跨分区，统一改造时一并升 VO。
     */
    public List<Map<String, Object>> alerts() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<NaiChannel> channels = channelMapper.selectList(null);
        long down = 0;
        for (NaiChannel c : channels) {
            RoutingHealthStat st = channelHealth.peek(c.getId());
            if (st != null && !st.schedulable(now)) {
                down++;
            }
        }
        if (!channels.isEmpty() && down == channels.size()) {
            alerts.add(alert("CRITICAL", "所有渠道当前熔断不可调度"));
        } else if (down > 0) {
            alerts.add(alert("WARNING", down + " 个渠道当前熔断"));
        }
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long total = 0;
        long errors = 0;
        for (StatusCount sc : requestLogStore.statusBreakdown(since)) {
            total += sc.count();
            if (sc.status() != null && sc.status() >= 400) {
                errors += sc.count();
            }
        }
        if (total >= ERROR_RATE_MIN_SAMPLES && (double) errors / total > ERROR_RATE_ALERT_THRESHOLD) {
            alerts.add(alert("WARNING",
                    "近 1 小时错误率 " + Math.round((double) errors / total * 100) + "% (>30%)"));
        }
        if (alerts.isEmpty()) {
            alerts.add(alert("OK", "无告警"));
        }
        return alerts;
    }

    private static Map<String, Object> alert(String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("message", message);
        return m;
    }

    /** 置信度（样本量相对中位数）。 */
    private static String confidence(long requestCount, double median) {
        if (requestCount >= Math.max(median, 10)) {
            return "high";
        }
        if (requestCount >= Math.max(median / 2, 5)) {
            return "medium";
        }
        return "low";
    }

    /** 单次请求贯穿钻取：请求日志 + 同 requestId 用量明细。 */
    public RequestDetailVo requestDetail(String requestId) {
        return new RequestDetailVo(
                requestLogStore.findByRequestId(requestId),
                usageLogMapper.selectList(Wrappers.lambdaQuery(NaiUsageLog.class)
                        .eq(NaiUsageLog::getRequestId, requestId)));
    }

    /**
     * 故障转移/重试事件审计（对抗验证 H7）：传 requestId 则按尝试序串起单请求的失败链；
     * 否则返回近 hours 小时倒序最近 limit 条（limit 夹在 1..{@value #FAILOVER_MAX_LIMIT}）。
     */
    public List<NaiFailoverEvent> failovers(String requestId, int hours, int limit) {
        var q = Wrappers.lambdaQuery(NaiFailoverEvent.class);
        if (requestId != null && !requestId.isBlank()) {
            q.eq(NaiFailoverEvent::getRequestId, requestId).orderByAsc(NaiFailoverEvent::getAttempt);
        } else {
            q.ge(NaiFailoverEvent::getCreateDt, LocalDateTime.now().minusHours(hours))
                    .orderByDesc(NaiFailoverEvent::getCreateDt)
                    .last("limit " + Math.min(Math.max(limit, 1), FAILOVER_MAX_LIMIT));
        }
        return failoverMapper.selectList(q);
    }
}
