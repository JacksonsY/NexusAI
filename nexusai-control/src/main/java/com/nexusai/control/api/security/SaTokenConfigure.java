package com.nexusai.control.api.security;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.persistence.po.NaiProject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 反应式（WebFlux）鉴权配置（替代原 AdminTokenWebFilter）。
 * <ul>
 *   <li>/admin/** 需登录（账号密码登录签发的 Sa-Token 会话，令牌走 NexusAI-Admin-Token 头）；放行 /admin/login；</li>
 *   <li><b>M2M 项目令牌直通</b>：控制面请求（接入方 → 网关）用同一 NexusAI-Admin-Token 头携带
 *       {@code nai_project.token}，命中 active 项目则放行、不参与 Sa-Token 会话（{@link ProjectTokenCache} 全内存查表·非阻塞）。
 *       <b>替代原单个静态 nexusai.admin.token</b>——多项目凭证隔离 + 可独立吊销/轮转。</li>
 * </ul>
 *
 * @author nexusai
 */
@Configuration
public class SaTokenConfigure {

    /** 鉴权失败 HTTP 状态码：未登录/令牌失效 401，作用域不足 403。 */
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    @Bean
    public SaReactorFilter saReactorFilter(ProjectTokenCache projectTokenCache) {
        return new SaReactorFilter()
                .addInclude("/**")
                .setAuth(obj -> SaRouter.match("/admin/**").notMatch("/admin/login").check(() -> {
                    // M2M 项目令牌直通：命中 active 项目则按作用域放行，不要求 Sa-Token 登录
                    String t = SaHolder.getRequest().getHeader(GatewayConstants.ADMIN_TOKEN_HEADER);
                    NaiProject project = projectTokenCache.match(t);
                    if (project != null) {
                        // 平台作用域令牌 → 全 /admin 放行；项目作用域令牌 → 仅租户自助端点（消费侧）
                        if (ProjectScopes.PLATFORM.equals(project.getScope())
                                || ProjectScopes.allowsProjectToken(SaHolder.getRequest().getRequestPath())) {
                            return;
                        }
                        throw new SaTokenException(ProjectScopes.SCOPE_DENIED);
                    }
                    StpUtil.checkLogin();   // 控制台账密会话 = 平台全权
                }))
                .setError(e -> {
                    if (ProjectScopes.SCOPE_DENIED.equals(e.getMessage())) {
                        SaHolder.getResponse().setStatus(HTTP_FORBIDDEN);
                        return SaResult.error("项目令牌无权访问该端点（需平台作用域令牌或控制台登录）").setCode(HTTP_FORBIDDEN);
                    }
                    SaHolder.getResponse().setStatus(HTTP_UNAUTHORIZED);
                    return SaResult.error("未登录或令牌失效").setCode(HTTP_UNAUTHORIZED);
                });
    }
}
