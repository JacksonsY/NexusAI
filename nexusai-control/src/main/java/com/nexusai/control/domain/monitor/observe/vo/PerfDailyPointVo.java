package com.nexusai.control.domain.monitor.observe.vo;

/**
 * Dashboard 性能时序日点（model/channelPerformanceStats：逐日×维度吞吐/TTFT/请求量）。
 * <p>字段名/类型与前端 {@code NexusAI.PerfDailyPoint} 严格对齐。
 *
 * @author nexusai
 */
public record PerfDailyPointVo(
        String date,
        String key,
        double throughput,
        long ttftMs,
        long requestCount) {
}
