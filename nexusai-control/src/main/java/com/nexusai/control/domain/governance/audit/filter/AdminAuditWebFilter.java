package com.nexusai.control.domain.governance.audit.filter;

import com.nexusai.control.domain.governance.audit.service.AuditLogService;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.persistence.po.NaiAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 控制面审计过滤器：记录 /admin/** 全部写操作（POST/PUT/DELETE/PATCH）。
 * 登录成败不在此记录（响应 200 内含业务 R.fail 无法从状态码判定）——由
 * {@link com.nexusai.common.event.AdminAuditEvent} 事件显式发布、{@link AdminAuditEventListener} 落库。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class AdminAuditWebFilter implements WebFilter, Ordered {

    private static final Set<HttpMethod> WRITE = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH);

    private final AuditLogService auditLogService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        if (!path.startsWith("/admin/") || !WRITE.contains(method)
                || path.equals("/admin/login") || path.equals("/admin/logout")) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(GatewayConstants.ADMIN_TOKEN_HEADER);
        String ip = AuditLogService.clientIp(exchange);
        return chain.filter(exchange).doFinally(signal -> {
            Integer status = exchange.getResponse().getStatusCode() == null
                    ? null : exchange.getResponse().getStatusCode().value();
            // 401/403 等鉴权失败不计入审计（未通过身份校验·避免刷库）
            if (status != null && (status == 401 || status == 403)) {
                return;
            }
            NaiAuditLog entry = new NaiAuditLog();
            entry.setActor(auditLogService.resolveActor(token));
            entry.setEventType(AuditLogService.deriveEventType(path, method.name()));
            entry.setDescription(method.name() + " " + path);
            entry.setPath(path);
            entry.setMethod(method.name());
            entry.setIp(ip);
            entry.setStatusCode(status);
            auditLogService.record(entry);
        });
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
