package com.nexusai.control.domain.platform.ops.vo;

/**
 * 告警评估设置（开关 + 评估间隔秒）。
 * <p>组件名/类型与前端 {@code NexusAI.OpsAlertSettings} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record OpsAlertSettingsVo(boolean enabled, int intervalSeconds) {
}
