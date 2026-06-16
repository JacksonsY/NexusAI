package com.nexusai.control.api.security;

import cn.dev33.satoken.session.SaSession;
import com.nexusai.persistence.po.NaiAdminUser;

import java.util.List;

/**
 * 控制台账号 Sa-Token 会话自定义键（登录时写入·鉴权读取）。单运维台双身份模型的会话载荷：
 * 角色 + （项目运维身份的）被授权项目集，供 {@link SaTokenConfigure} 做路径级 scope、{@link CallerScopeWebFilter} 写行级项目过滤属性。
 *
 * @author nexusai
 */
public final class AdminSessionKeys {

    /** 会话角色（Integer·{@link NaiAdminUser#ROLE_USER}/{@code ROLE_ADMIN}）。 */
    public static final String ROLE = "nexusai.role";

    /** 会话被授权项目码（{@code List<String>}·仅项目运维身份有值；超管为空=全项目）。 */
    public static final String PROJECTS = "nexusai.projects";

    /** 登录主体前缀（loginId = admin:<username>·与 M2M 项目令牌区分）。 */
    public static final String LOGIN_PREFIX = "admin:";

    private AdminSessionKeys() {
    }

    /** 读会话角色（缺省按超管·向后兼容历史会话）。 */
    public static int role(SaSession session) {
        Object v = session.get(ROLE);
        return v instanceof Number n ? n.intValue() : NaiAdminUser.ROLE_ADMIN;
    }

    /** 读会话被授权项目码（缺省空列表=不限/超管）。 */
    @SuppressWarnings("unchecked")
    public static List<String> projects(SaSession session) {
        Object v = session.get(PROJECTS);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }
}
