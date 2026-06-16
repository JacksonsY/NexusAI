package com.nexusai.control.domain.routing.proxynode.vo;

/**
 * 出口代理节点连通性探测结果（经代理 GET probe-url·解析出口 IP）。
 * <p>对齐前端 {@code NexusAI.ProxyTestResult}：{@code exitIp} 仅成功且解析到时有值，
 * {@code error} 仅失败时有值；{@code latencyMs} 为 long（经全局 Jackson 序列化为字符串）。
 *
 * @author nexusai
 */
public record ProxyTestResultVo(
        String probeUrl,
        int timeoutSecs,
        boolean success,
        long latencyMs,
        String exitIp,
        String error) {
}
