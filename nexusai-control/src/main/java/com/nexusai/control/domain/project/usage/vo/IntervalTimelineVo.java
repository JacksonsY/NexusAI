package com.nexusai.control.domain.project.usage.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 请求间隔时间线（interval-timeline·按项目分组散点）。
 * <p>字段名/类型与前端 {@code NexusAI.IntervalTimeline} 严格对齐。{@code projects} 为动态映射
 * （projectCode → projectName）保留 Map；{@code models} 为字符串列表。
 *
 * @author nexusai
 */
public record IntervalTimelineVo(
        int analysisPeriodHours,
        int totalPoints,
        List<Point> points,
        Map<String, String> projects,
        List<String> models) {

    /**
     * 散点（x=时间·y=间隔分钟·model 可空）。
     * <p>{@code model} 为可空字段，NON_NULL 保留原 Map「值为 null 时不下发该键」语义。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(String x, double y, String model, String projectCode) {
    }
}
