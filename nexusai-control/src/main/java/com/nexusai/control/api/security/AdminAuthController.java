package com.nexusai.control.api.security;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.common.event.AdminAuditEvent;
import com.nexusai.common.model.R;
import com.nexusai.common.util.ClientIp;
import com.nexusai.common.util.PasswordHasher;
import com.nexusai.control.api.security.vo.AdminLoginView;
import com.nexusai.control.api.security.vo.AdminUserInfoView;
import com.nexusai.persistence.mapper.NaiAdminUserMapper;
import com.nexusai.persistence.mapper.NaiAdminUserProjectMapper;
import com.nexusai.persistence.po.NaiAdminUser;
import com.nexusai.persistence.po.NaiAdminUserProject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 控制台账号密码登录（Sa-Token 反应式·账密真理源在 {@code nai_admin_user} 表）。借鉴 snail-ai/snail-job 的两身份模型：
 * <ul>
 *   <li>{@link NaiAdminUser#ROLE_ADMIN} 超管：全部 /admin（渠道/路由/项目/全局配置）；</li>
 *   <li>{@link NaiAdminUser#ROLE_USER} 项目运维：仅被授权项目（{@code nai_admin_user_project}）的自助端点——
 *       nexusai 的 project = snail-job 的 namespace，等同其普通用户按命名空间授权。</li>
 * </ul>
 * 口令经 {@link PasswordHasher} 加盐 PBKDF2 校验·明文永不落库。登录成功签发 Sa-Token 会话令牌（前端以 NexusAI-Admin-Token 头携带），
 * 会话载荷写入角色 + 授权项目集（供 {@link SaTokenConfigure} 路径级 scope、{@link CallerScopeWebFilter} 行级项目过滤）。
 *
 * <p>DB 调用在 boundedElastic 执行（阻塞 JDBC 不可上 Netty 事件循环）；StpUtil 须先手动绑定 exchange 上下文（@RequestBody 解码已切线程）。
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
    private final NaiAdminUserMapper adminUserMapper;
    private final NaiAdminUserProjectMapper adminUserProjectMapper;

    @PostMapping("/login")
    public Mono<R<AdminLoginView>> login(@RequestBody LoginReq req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> {
            NaiAdminUser user = req == null || req.getUsername() == null ? null
                    : adminUserMapper.selectOne(new LambdaQueryWrapper<NaiAdminUser>()
                            .eq(NaiAdminUser::getUsername, req.getUsername()));
            boolean ok = user != null
                    && user.getStatus() != null && user.getStatus() == 1
                    && PasswordHasher.matches(req.getPassword(), user.getPassword());
            if (!ok) {
                eventPublisher.publishEvent(new AdminAuditEvent("admin_login_failed",
                        req == null ? "" : String.valueOf(req.getUsername()), "控制台登录失败", ip,
                        user != null && user.getStatus() != null && user.getStatus() != 1 ? "账号已停用" : "账号或密码错误"));
                return R.<AdminLoginView>fail("账号或密码错误");
            }
            List<String> projects = NaiAdminUser.ROLE_USER == user.getRole() ? grantedProjects(user.getId()) : List.of();
            // @RequestBody 解码已切线程 → 手动绑定 exchange 上下文后再调 StpUtil（同步执行·结束清理）。
            SaReactorSyncHolder.setContext(exchange);
            try {
                StpUtil.login(AdminSessionKeys.LOGIN_PREFIX + user.getUsername());
                StpUtil.getSession().set(AdminSessionKeys.ROLE, user.getRole());
                StpUtil.getSession().set(AdminSessionKeys.PROJECTS, projects);
                eventPublisher.publishEvent(new AdminAuditEvent("admin_login_success",
                        user.getUsername(), "控制台登录成功", ip, null));
                return R.ok(new AdminLoginView(StpUtil.getTokenValue(), user.getUsername(), user.getRole()));
            } finally {
                SaReactorSyncHolder.clearContext();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/logout")
    public Mono<R<Void>> logout(ServerWebExchange exchange) {
        SaReactorSyncHolder.setContext(exchange);
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            StpUtil.logout();
            eventPublisher.publishEvent(new AdminAuditEvent("admin_logout",
                    stripPrefix(loginId), "控制台登出", clientIp(exchange), null));
            return Mono.just(R.ok());
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    @GetMapping("/userinfo")
    public Mono<R<AdminUserInfoView>> userinfo(ServerWebExchange exchange) {
        SaReactorSyncHolder.setContext(exchange);
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            int role = AdminSessionKeys.role(StpUtil.getSession());
            List<String> projects = AdminSessionKeys.projects(StpUtil.getSession());
            return Mono.just(R.ok(new AdminUserInfoView(stripPrefix(loginId), role, projects, loginId)));
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    /** 查某运维账号被授权的项目码列表。 */
    private List<String> grantedProjects(Long adminUserId) {
        return adminUserProjectMapper.selectList(new LambdaQueryWrapper<NaiAdminUserProject>()
                        .eq(NaiAdminUserProject::getAdminUserId, adminUserId))
                .stream().map(NaiAdminUserProject::getProjectCode).toList();
    }

    /** loginId "admin:alice" → "alice"（未登录返回 null）。 */
    private static String stripPrefix(Object loginId) {
        if (loginId == null) {
            return null;
        }
        String s = loginId.toString();
        return s.startsWith(AdminSessionKeys.LOGIN_PREFIX) ? s.substring(AdminSessionKeys.LOGIN_PREFIX.length()) : s;
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
