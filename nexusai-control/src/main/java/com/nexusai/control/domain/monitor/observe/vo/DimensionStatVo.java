package com.nexusai.control.domain.monitor.observe.vo;

/**
 * Dashboard 维度分析行（渠道/模型/上游凭证通用·去钱化）。
 * <p>字段名/类型与前端 {@code NexusAI.DimensionStat} 严格对齐。
 *
 * @author nexusai
 */
public record DimensionStatVo(
        String key,
        long total,
        long success,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        long totalTokens) {
}
