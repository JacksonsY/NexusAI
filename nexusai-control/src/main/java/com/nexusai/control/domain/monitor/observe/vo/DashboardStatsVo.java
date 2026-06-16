package com.nexusai.control.domain.monitor.observe.vo;

import java.util.List;
import java.util.Map;

/**
 * Dashboard 运维总览聚合（去钱化）。
 * <p>字段名/类型与前端 {@code NexusAI.DashboardStats} 严格对齐。{@code tokenStats} 键为
 * {@code ${period}${Input|Output|Cached}}（period=today/week/month/all）的动态映射，保留 Map。
 *
 * @author nexusai
 */
public record DashboardStatsVo(
        long totalRequests,
        double successRateAllTime,
        RequestStats requestStats,
        SuccessStats successStats,
        Map<String, Long> tokenStats,
        List<ChannelSuccessRateVo> channelSuccessRates) {

    /** 请求环比计数（今日/近 7 天/前 7 天/本月）。 */
    public record RequestStats(
            long requestsToday,
            long requestsThisWeek,
            long requestsLastWeek,
            long requestsThisMonth) {
    }

    /** 成功率环比（近 7 天 vs 前 7 天 + 近 7 天成败计数）。 */
    public record SuccessStats(
            double rateThisWeek,
            double rateLastWeek,
            long successThisWeek,
            long failedThisWeek) {
    }
}
