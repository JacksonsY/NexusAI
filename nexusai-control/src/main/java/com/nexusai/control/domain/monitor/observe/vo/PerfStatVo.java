package com.nexusai.control.domain.monitor.observe.vo;

/**
 * Dashboard 性能最快榜行（fastest：吞吐 tokens/s + 置信度）。
 * <p>字段名/类型与前端 {@code NexusAI.PerfStat} 严格对齐。
 *
 * @author nexusai
 */
public record PerfStatVo(
        String key,
        double throughput,
        long tokensCount,
        long avgTtftMs,
        long avgDurationMs,
        long requestCount,
        String confidence) {
}
