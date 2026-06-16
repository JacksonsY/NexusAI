package com.nexusai.control.domain.routing.node.vo;

/**
 * 集群节点视图行（多实例集群视图·心跳新鲜度三档判态 + 运行时快照）。
 * <p>对齐前端 {@code NexusAI.Node}：{@code startedAt} 为 Long（经全局 Jackson 序列化为字符串·
 * 前端 {@code number | string} 兼容）；时间字段 {@code lastHeartbeat}/{@code registeredAt} 已格式化为串。
 *
 * @author nexusai
 */
public record NodeVo(
        String nodeId,
        String host,
        Integer port,
        String version,
        Long startedAt,
        String lastHeartbeat,
        String status,
        String role,
        Integer activeRequests,
        Double cpuPercent,
        Integer heapUsedMb,
        Integer heapMaxMb,
        String labels,
        String registeredAt) {
}
