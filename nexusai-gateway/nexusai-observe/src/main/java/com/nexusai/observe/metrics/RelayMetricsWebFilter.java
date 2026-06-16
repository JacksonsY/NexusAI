package com.nexusai.observe.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 流量面并发观测过滤器：只拦 {@code /v1/**}（relay 入口），请求进入/终止时更新
 * {@link RelayInflightMetrics}（处理中/累计/峰值/429 被限流）。
 * <p>独立于 relay 闸门链（零侵入·doFinally 覆盖取消与异常路径）；最高优先级保证计数包住全链路。
 *
 * @author nexusai
 */
@Component
@Order(-200)
@RequiredArgsConstructor
public class RelayMetricsWebFilter implements WebFilter {

    private final RelayInflightMetrics metrics;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }
        metrics.onStart();
        return chain.filter(exchange).doFinally(signal -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            metrics.onFinish(status == null ? null : status.value());
        });
    }
}
