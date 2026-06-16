package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 模型链路预览（详情抽屉「链路控制」Tab·按协议家族分组的两级调度真实顺序）。
 * <p>对齐前端 {@code NexusAI.ModelRouting}：{@code now} 为 long（经全局 Jackson 序列化为字符串·
 * 前端 {@code number | string} 兼容）。
 *
 * @author nexusai
 */
public record ModelRoutingVo(
        String schedulingMode,
        String priorityMode,
        long now,
        List<ModelRoutingGroupVo> groups) {
}
