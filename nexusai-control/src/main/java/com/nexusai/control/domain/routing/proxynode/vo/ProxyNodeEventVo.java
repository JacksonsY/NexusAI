package com.nexusai.control.domain.routing.proxynode.vo;

/**
 * 出口代理节点连接事件（connected/disconnected/error）。
 * <p>对齐前端 {@code NexusAI.ProxyNodeEvent}：{@code id} 显式为字符串；{@code createDt} 已格式化为串。
 *
 * @author nexusai
 */
public record ProxyNodeEventVo(
        String id,
        String eventType,
        String detail,
        String createDt) {
}
