package com.nexusai.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 控制台前缀剥离：给内置控制台这套面（静态资源 + /admin + /route 等控制面接口）一个 /nexusai 前缀，
 * 等价于 servlet 的 context-path，但仅作用于带前缀的请求——外部 OpenAI 兼容数据面 /v1 不带前缀、不受影响。
 * <p>前端 VITE_BASE_URL=/nexusai、VITE_SERVICE_BASE_URL=/nexusai 时，浏览器一律请求 /nexusai/**；
 * 本 filter 在最前面把 /nexusai 去掉再交给后续路由（静态欢迎页 / + /admin/** + /route/**）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsolePrefixStripFilter implements WebFilter {

    private static final String PREFIX = "/nexusai";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            String rewritten = path.substring(PREFIX.length());
            if (rewritten.isEmpty()) {
                rewritten = "/";
            }
            ServerHttpRequest mutated = exchange.getRequest().mutate().path(rewritten).build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }
        return chain.filter(exchange);
    }
}
