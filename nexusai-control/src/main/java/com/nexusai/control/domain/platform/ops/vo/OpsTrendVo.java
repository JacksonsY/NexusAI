package com.nexusai.control.domain.platform.ops.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 三趋势一次拉（吞吐 QPS/TPS + 错误三线 + 故障转移率）。
 * <p>组件名/类型与前端 {@code NexusAI.OpsTrend} 严格对齐，序列化 JSON 字节级一致；
 * {@code generatedAt} 维持原 Map 持有的 {@link LocalDateTime}（Jackson 输出 ISO 串）。
 *
 * @author nexusai
 */
public record OpsTrendVo(LocalDateTime generatedAt, long segmentSeconds, List<Point> points) {

    /** 趋势单点（键序与原 LinkedHashMap 一致·与前端 {@code NexusAI.OpsTrendPoint} 对齐）。 */
    public record Point(LocalDateTime ts, long requestCount, long errorCount,
                        long upstreamErrorCount, long limitedErrorCount,
                        double qps, double tps, long failoverCount, double failoverRate) {
    }
}
