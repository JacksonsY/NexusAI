package com.nexusai.control.domain.governance.ipsec.filter;

import com.nexusai.control.domain.governance.ipsec.service.IpSecurityService;
import com.nexusai.control.domain.governance.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * IP 安全过滤器（最前置·IP 黑名单语义）：黑名单 IP 拒绝一切访问（403），
 * 白名单豁免。本地 5s 快照判定——不在 Netty 事件线程打 Redis。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class IpSecurityWebFilter implements WebFilter, Ordered {

    private final IpSecurityService ipSecurityService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String ip = AuditLogService.clientIp(exchange);
        String reason = ipSecurityService.blockedReason(ip);
        if (reason == null) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":{\"message\":\"IP blocked\",\"type\":\"forbidden\"}}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
