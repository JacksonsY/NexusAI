package com.nexusai.control.domain.routing.proxynode.vo;

/**
 * 出口代理节点删除结果。
 * <p>{@code clearedSystemProxy}=被删节点是否同时为全局默认代理（是则一并清除系统设置）。
 *
 * @author nexusai
 */
public record ProxyNodeDeleteResultVo(boolean clearedSystemProxy) {
}
