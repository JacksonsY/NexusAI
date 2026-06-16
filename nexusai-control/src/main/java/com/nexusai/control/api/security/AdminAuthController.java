package com.nexusai.control.api.security;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.nexusai.common.event.AdminAuditEvent;
import com.nexusai.common.model.R;
import com.nexusai.common.util.ClientIp;
import com.nexusai.control.api.security.vo.AdminLoginView;
import com.nexusai.control.api.security.vo.AdminUserInfoView;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 控制台账号密码登录（Sa-Token 反应式）。账号密码来自配置 {@code nexusai.admin.username/password}
 * （单一超管运维台，无独立用户体系；业务用户/钱/账在 业务面）。登录成功签发 Sa-Token 会话令牌，
 * 前端后续以 NexusAI-Admin-Token 头携带访问 /admin/**。
 *
 * <p>StpUtil 在 controller 方法体内<b>同步</b>调用（Netty 请求线程·SaReactorFilter 已设上下文），
 * 切勿移入 boundedElastic（会丢上下文）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    /** 标准代理转发头（解析真实客户端 IP 用）。 */
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final ApplicationEventPublisher eventPublisher;

    @Value("${nexusai.admin.username:admin}")
    private String adminUsername;

    @Value("${nexusai.admin.password:}")
    private String adminPassword;

    @PostMapping("/login")
    public Mono<R<AdminLoginView>> login(@RequestBody LoginReq req, ServerWebExchange exchange) {
        boolean ok = req != null
                && StringUtils.hasText(adminPassword)
                && adminUsername.equals(req.getUsername())
                && adminPassword.equals(req.getPassword());
        String ip = clientIp(exchange);
        if (!ok) {
            eventPublisher.publishEvent(new AdminAuditEvent("admin_login_failed",
                    req == null ? "" : String.valueOf(req.getUsername()), "控制台登录失败", ip, "账号或密码错误"));
            return Mono.just(R.fail("账号或密码错误"));
        }
        // @RequestBody 反应式解码切了线程 → SaReactorFilter 设的 ThreadLocal 上下文丢失；
        // 在当前处理线程手动绑定 exchange 上下文后再调 StpUtil（同步执行，结束清理）。
        SaReactorSyncHolder.setContext(exchange);
        try {
            StpUtil.login("admin:" + adminUsername);
            eventPublisher.publishEvent(new AdminAuditEvent("admin_login_success",
                    adminUsername, "控制台登录成功", ip, null));
            return Mono.just(R.ok(new AdminLoginView(StpUtil.getTokenValue(), adminUsername)));
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    @PostMapping("/logout")
    public Mono<R<Void>> logout(ServerWebExchange exchange) {
        SaReactorSyncHolder.setContext(exchange);
        try {
            StpUtil.logout();
            eventPublisher.publishEvent(new AdminAuditEvent("admin_logout",
                    adminUsername, "控制台登出", clientIp(exchange), null));
            return Mono.just(R.ok());
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    @GetMapping("/userinfo")
    public Mono<R<AdminUserInfoView>> userinfo(ServerWebExchange exchange) {
        SaReactorSyncHolder.setContext(exchange);
        try {
            return Mono.just(R.ok(new AdminUserInfoView(adminUsername, StpUtil.getLoginIdDefaultNull())));
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst(FORWARDED_FOR_HEADER);
        String remote = exchange.getRequest().getRemoteAddress() == null ? ""
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        return ClientIp.resolve(remote, xff);
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }
}
