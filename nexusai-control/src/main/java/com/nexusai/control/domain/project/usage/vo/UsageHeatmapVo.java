package com.nexusai.control.domain.project.usage.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 活跃度热图（/usage/heatmap·365 天）。
 * <p>字段名/类型与前端 {@code NexusAI.UsageHeatmap} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record UsageHeatmapVo(
        String startDate,
        String endDate,
        long totalDays,
        long activeDays,
        long maxRequests,
        List<Day> days) {

    /** 单日格（date/requests/totalTokens/costUsd）。 */
    public record Day(String date, long requests, long totalTokens, BigDecimal costUsd) {
    }
}
