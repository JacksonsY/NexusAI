package com.nexusai.control.domain.platform.ops.vo;

/**
 * 运维监控阈值配置（运维监控页可调·healthScore 与前端着色共用）。
 * <p>组件名/类型与前端 {@code NexusAI.OpsThresholds} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record OpsThresholdsVo(int slaPercentMin, int errorRatePercentMax,
                              int p99LatencyMsMax, int ttftP99MsMax) {
}
