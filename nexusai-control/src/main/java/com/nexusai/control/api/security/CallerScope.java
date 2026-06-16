package com.nexusai.control.api.security;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import org.springframework.web.server.ServerWebExchange;

import java.util.Set;

/**
 * 控制面调用方的项目作用域收口（数据级行权限单一真理源）。三态：
 * <ul>
 *   <li><b>单项目</b>：项目作用域 M2M 令牌（{@code CallerScopeWebFilter} 写 {@link GatewayConstants#CALLER_PROJECT_ATTR}）——只能操作自己那一个项目；</li>
 *   <li><b>多项目</b>：控制台「项目运维」人类账号（{@link com.nexusai.persistence.po.NaiAdminUser#ROLE_USER}·写 {@link GatewayConstants#CALLER_PROJECTS_ATTR}）——
 *       只能操作被授权的项目集合（borrow snail-job 普通用户按 namespace 授权）；</li>
 *   <li><b>不限</b>：平台令牌 / 超管会话——两属性皆空，全项目可见。</li>
 * </ul>
 * 替代原先散落在 4 个自助控制器里各自重复的 {@code assertProjectScope/scopeProject/requirePlatform} 私有方法（消重 + 统一多项目语义）。
 *
 * @author nexusai
 */
public final class CallerScope {

    private CallerScope() {
    }

    /** 单项目受限码（项目作用域 M2M 令牌）；无则 null。 */
    private static String single(ServerWebExchange exchange) {
        Object v = exchange.getAttribute(GatewayConstants.CALLER_PROJECT_ATTR);
        return v == null ? null : v.toString();
    }

    /** 多项目授权集合（项目运维人类会话）；无则 null。 */
    @SuppressWarnings("unchecked")
    private static Set<String> multi(ServerWebExchange exchange) {
        Object v = exchange.getAttribute(GatewayConstants.CALLER_PROJECTS_ATTR);
        return v == null ? null : (Set<String>) v;
    }

    /** 是否受项目作用域约束（单项目或多项目）。 */
    public static boolean restricted(ServerWebExchange exchange) {
        return single(exchange) != null || multi(exchange) != null;
    }

    /**
     * 可行级过滤端点的有效项目：
     * <ul>
     *   <li>单项目令牌 → 强制其项目（忽略入参防越权枚举）；</li>
     *   <li>多项目运维 → 入参必须落在授权集合内；入参为空且仅授权 1 个项目则取该项目，否则要求显式指定；</li>
     *   <li>不限 → 沿用入参（可空=全部项目）。</li>
     * </ul>
     */
    public static String scopeProject(ServerWebExchange exchange, String requested) {
        String s = single(exchange);
        if (s != null) {
            return s;
        }
        Set<String> m = multi(exchange);
        if (m != null) {
            if (requested == null || requested.isBlank()) {
                if (m.size() == 1) {
                    return m.iterator().next();
                }
                throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                        "项目运维账号需指定一个被授权的项目（projectCode）");
            }
            if (!m.contains(requested)) {
                throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                        "项目运维账号无权访问项目: " + requested);
            }
            return requested;
        }
        return requested;
    }

    /** 定向操作越权校验：受限调用方的目标 projectCode 必须在其作用域内，否则 403。 */
    public static void assertProjectScope(ServerWebExchange exchange, String targetProjectCode) {
        String s = single(exchange);
        if (s != null && !s.equals(targetProjectCode)) {
            throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                    "项目令牌(" + s + ")无权操作其他项目: " + targetProjectCode);
        }
        Set<String> m = multi(exchange);
        if (m != null && !m.contains(targetProjectCode)) {
            throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                    "项目运维账号无权操作项目: " + targetProjectCode);
        }
    }

    /** 不可行级过滤的跨项目总览端点：任何受限调用方（单/多项目）一律 403，仅平台令牌/超管可访问。 */
    public static void requirePlatform(ServerWebExchange exchange) {
        if (restricted(exchange)) {
            throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                    "该跨项目总览端点仅平台作用域令牌或超管控制台可访问");
        }
    }
}
