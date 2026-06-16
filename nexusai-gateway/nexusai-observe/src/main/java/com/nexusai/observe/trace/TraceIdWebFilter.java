package com.nexusai.observe.trace;

import com.nexusai.common.constant.GatewayConstants;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * TraceId 过滤器：为每请求生成/透传 trace_id，回写响应头 X-Trace-Id，供日志/指标串联。
 * 最高优先级（早于鉴权），保证全链路有 trace。
 * <p>头/属性键统一引用 {@link GatewayConstants#TRACE_HEADER}/{@link GatewayConstants#TRACE_ATTR}（common 单一真理源）。
 *
 * @author nexusai
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        exchange.getAttributes().put(GatewayConstants.TRACE_ATTR, traceId);
        exchange.getResponse().getHeaders().set(GatewayConstants.TRACE_HEADER, traceId);
        return chain.filter(exchange);
    }
}
