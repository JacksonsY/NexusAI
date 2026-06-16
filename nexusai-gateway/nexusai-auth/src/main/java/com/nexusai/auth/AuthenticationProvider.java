package com.nexusai.auth;

import com.nexusai.persistence.po.NaiToken;

/**
 * 流量面认证 SPI：把"如何从 Authorization 头解析出
 * Principal"抽象成可插拔实现。新增认证方式（JWT/HMAC/OIDC…）只加实现类，不改 {@code ApiKeyAuthFilter} 管线。
 *
 * @author nexusai
 */
public interface AuthenticationProvider {

    /** 是否能处理该 Authorization 头（如 sk- / JWT）。命中则由本 provider 认证。 */
    boolean supports(String authHeader);

    /** 认证并解析出流量面 Principal（{@code nai_token}）；失败抛 {@code GatewayException(UNAUTHORIZED)}。 */
    NaiToken authenticate(String authHeader);

    /** 尝试顺序（小先判·JWT 等特定形态应早于 sk- 兜底）。 */
    default int order() {
        return 100;
    }
}
