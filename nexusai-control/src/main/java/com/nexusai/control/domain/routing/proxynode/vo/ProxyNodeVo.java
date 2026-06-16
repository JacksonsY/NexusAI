package com.nexusai.control.domain.routing.proxynode.vo;

/**
 * 出口代理节点视图（列表/创建/编辑回显·不含明文密码）。
 * <p>对齐前端 {@code NexusAI.ProxyNode}：{@code id} 显式为字符串；{@code totalRequests}/{@code failedRequests}
 * 为 Long（经全局 Jackson 序列化为字符串）；时间字段已格式化为串。
 *
 * @author nexusai
 */
public record ProxyNodeVo(
        String id,
        String name,
        String proxyUrl,
        String username,
        boolean hasPassword,
        String region,
        String status,
        Long totalRequests,
        Long failedRequests,
        Double avgLatencyMs,
        String lastProbeAt,
        String lastOkAt,
        String createDt) {
}
