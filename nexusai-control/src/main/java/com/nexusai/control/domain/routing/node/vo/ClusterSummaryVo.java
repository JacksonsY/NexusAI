package com.nexusai.control.domain.routing.node.vo;

/**
 * 集群汇总（在线数 / leader / 在途请求 / 熔断态存储模式）。
 * <p>对齐前端 {@code NexusAI.ClusterSummary}：{@code totalActiveRequests} 为 long
 * （经全局 Jackson 序列化为字符串·前端 {@code number | string} 兼容）。
 *
 * @author nexusai
 */
public record ClusterSummaryVo(
        int total,
        int online,
        String leader,
        long totalActiveRequests,
        String circuitStateStore) {
}
