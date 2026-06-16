package com.nexusai.relay.filter;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.auth.AuthenticationProvider;
import com.nexusai.relay.context.RelayContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.Comparator;
import java.util.List;

/**
 * ① 鉴权：取 Authorization 头 → 按 {@link AuthenticationProvider} SPI 顺序找首个 supports 的实现认证
 * （sk- API Key 兜底 / JWT 等可插拔）→ 解析出 {@code nai_token} 填 token/group。
 * <p>认证方式可插拔：新增 JWT/HMAC/OIDC 只加 provider 实现，本 filter 不变。
 *
 * @author nexusai
 */
@Component
public class ApiKeyAuthFilter implements RelayFilter {

    private final List<AuthenticationProvider> providers;

    public ApiKeyAuthFilter(List<AuthenticationProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(AuthenticationProvider::order))
                .toList();
    }

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        String auth = resolveCredential(exchange);
        AuthenticationProvider provider = providers.stream()
                .filter(p -> p.supports(auth))
                .findFirst()
                .orElseThrow(() -> GatewayException.of(GatewayErrorCode.UNAUTHORIZED,
                        "缺少 Authorization: Bearer sk-..."));
        NaiToken token = provider.authenticate(auth);
        ctx.setToken(token);
        ctx.setGroup(token.getGroupCode() == null ? GatewayConstants.DEFAULT_GROUP : token.getGroupCode());
    }

    /**
     * 取凭证：优先标准 {@code Authorization} 头；缺失时回退 Gemini 风格的 {@code x-goog-api-key} 头 / {@code ?key=}
     * 查询参数（归一为 {@code Bearer <key>} 交由 sk- provider 认证）——使 Gemini SDK 客户端可直连本网关。
     */
    private static String resolveCredential(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER);
        if (auth != null && !auth.isBlank()) {
            return auth;
        }
        String goog = exchange.getRequest().getHeaders().getFirst("x-goog-api-key");
        if (goog != null && !goog.isBlank()) {
            return GatewayConstants.BEARER_PREFIX + goog;
        }
        String keyQuery = exchange.getRequest().getQueryParams().getFirst("key");
        if (keyQuery != null && !keyQuery.isBlank()) {
            return GatewayConstants.BEARER_PREFIX + keyQuery;
        }
        return auth;
    }

    @Override
    public int getOrder() {
        return ORDER_AUTH;
    }
}
