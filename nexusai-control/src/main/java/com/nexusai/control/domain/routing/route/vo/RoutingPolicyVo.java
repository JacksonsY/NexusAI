package com.nexusai.control.domain.routing.route.vo;

/**
 * 当前生效的调度配置（系统默认策略 defaultPolicy·优先级管理对话框/模型路由 Tab 在用）。
 * <p>对齐前端 {@code NexusAI.RoutingPolicy}：{@code schedulingMode} + {@code priorityMode} 均为枚举串。
 * 字段顺序与原 Map 一致（schedulingMode 先于 priorityMode）。
 *
 * @author nexusai
 */
public record RoutingPolicyVo(String schedulingMode, String priorityMode) {
}
