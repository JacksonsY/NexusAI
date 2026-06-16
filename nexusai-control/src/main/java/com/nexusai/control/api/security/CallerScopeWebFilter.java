package com.nexusai.control.api.security;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.persistence.po.NaiProject;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 调用方作用域标记（令牌 scope 收口·数据级第二层）：对 {@code /admin/**} 请求，若携带的是<b>项目作用域</b>令牌，
 * 把其受限 projectCode 写入 exchange 属性 {@link GatewayConstants#CALLER_PROJECT_ATTR}；控制器据此校验请求体/参数里的
 * projectCode 必须与令牌归属一致（防项目 A 的令牌带 projectCode=B 跨项目操作）。
 * <p>平台作用域令牌 / 控制台 admin 会话不写属性（= 不限·全权）。{@link SaTokenConfigure} 已做路径级 scope（项目档碰不到
 * 平台端点），本过滤器补的是<b>允许端点上的跨项目越权</b>这一层。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class CallerScopeWebFilter implements WebFilter, Ordered {

    private final ProjectTokenCache tokenCache;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/admin/")) {
            String token = exchange.getRequest().getHeaders().getFirst(GatewayConstants.ADMIN_TOKEN_HEADER);
            NaiProject caller = tokenCache.match(token);
            if (caller != null && !ProjectScopes.PLATFORM.equals(caller.getScope())) {
                exchange.getAttributes().put(GatewayConstants.CALLER_PROJECT_ATTR, caller.getProjectCode());
            }
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 任意早于控制器即可；与 SaReactorFilter 相对顺序无关（Sa 拒绝则短路·属性不被读）
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }
}
