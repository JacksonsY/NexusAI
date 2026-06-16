package com.nexusai.control.domain.platform.ops.vo;

/**
 * 告警规则测试（dry-run）结果：按规则口径现算指标值与是否违反。
 * <p>组件名/类型与前端 {@code NexusAI.AlertRuleTestResult} 严格对齐，序列化 JSON 字节级一致；
 * {@code value} 无样本时为 {@code null}（不评估）。
 *
 * @author nexusai
 */
public record AlertRuleTestResultVo(String metricType, int windowMinutes, Double value, boolean breached) {
}
