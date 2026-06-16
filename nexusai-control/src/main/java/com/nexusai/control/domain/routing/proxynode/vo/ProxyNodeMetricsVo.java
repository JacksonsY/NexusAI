package com.nexusai.control.domain.routing.proxynode.vo;

import java.util.List;

/**
 * 出口代理节点指标（探测窗口内逐小时桶 + 汇总）。
 * <p>对齐前端 {@code NexusAI.ProxyNodeMetrics}：逐桶计数为 Integer（输出 number），
 * 汇总计数为 long（经全局 Jackson 序列化为字符串·与原 Map 字节一致）。
 *
 * @author nexusai
 */
public record ProxyNodeMetricsVo(List<Bucket> items, Summary summary) {

    /** 单小时桶（uptimeRatio/latencyAvgMs 无成功样本时为 null）。 */
    public record Bucket(
            String bucketStart,
            Integer samples,
            Double uptimeRatio,
            Double latencyAvgMs,
            Integer latencyMaxMs,
            Integer errors) {
    }

    /** 窗口汇总（计数为 long·与原 Map 一致）。 */
    public record Summary(
            long samples,
            long okSamples,
            Double uptimeRatio,
            Double latencyAvgMs,
            int latencyMaxMs,
            long errors) {
    }
}
