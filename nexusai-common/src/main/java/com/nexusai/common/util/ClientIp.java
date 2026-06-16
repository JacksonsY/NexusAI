package com.nexusai.common.util;

import java.util.List;

/**
 * 真实客户端 IP 解析（防 {@code X-Forwarded-For} 伪造）。纯静态工具——可信代理列表为<b>进程级部署配置</b>，
 * 启动期由 {@code TrustedProxyConfig} 经 {@link #setTrustedProxies(List)} 注入一次。
 *
 * <p><b>安全语义</b>：
 * <ul>
 *   <li><b>未配置可信代理</b>：一律只信直连对端 {@code remoteAddr}，<b>完全忽略 XFF</b>——
 *       否则任何客户端都能伪造 XFF 绕过 Key IP 白名单 / 全局 IP 黑名单。部署在 Nginx/LB 后必须配置本项。</li>
 *   <li><b>已配置且直连对端可信</b>：取 XFF 链中<b>从右往左第一个非可信</b>地址（= 真实客户端，
 *       链尾才是离网关最近的可信跳板，逐跳剥离）。</li>
 *   <li><b>已配置但直连对端不可信</b>（绕过代理直连）：忽略 XFF，用 {@code remoteAddr}。</li>
 * </ul>
 *
 * @author nexusai
 */
public final class ClientIp {

    private static volatile List<String> trustedProxies = List.of();

    private ClientIp() {
    }

    /** 启动期注入可信代理 CIDR/IP 列表（{@code TrustedProxyConfig} 调用·空=不信任任何 XFF）。 */
    public static void setTrustedProxies(List<String> proxies) {
        trustedProxies = proxies == null ? List.of()
                : proxies.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).toList();
    }

    /**
     * 解析真实客户端 IP。
     *
     * @param remoteAddr 直连对端地址（{@code exchange.getRequest().getRemoteAddress()} 的 host）
     * @param xff        {@code X-Forwarded-For} 头原值（可空）
     * @return 真实客户端 IP；无可判定时回退 remoteAddr（可能为空串）
     */
    public static String resolve(String remoteAddr, String xff) {
        String peer = remoteAddr == null ? "" : remoteAddr.trim();
        // 未配置可信代理 / 无 XFF / 直连对端不可信 → 不信任 XFF
        if (trustedProxies.isEmpty() || xff == null || xff.isBlank()
                || peer.isEmpty() || !IpMatcher.inWhitelist(peer, trustedProxies)) {
            return peer;
        }
        int end = xff.length();
        while (end > 0) {
            int comma = xff.lastIndexOf(',', end - 1);
            String hop = xff.substring(comma + 1, end).trim();
            if (IpMatcher.isIpLiteral(hop) && !IpMatcher.inWhitelist(hop, trustedProxies)) {
                return hop;
            }
            if (comma < 0) {
                break;
            }
            end = comma;
        }
        // 全部跳点都可信（异常配置）→ 回退直连对端
        return peer;
    }
}
