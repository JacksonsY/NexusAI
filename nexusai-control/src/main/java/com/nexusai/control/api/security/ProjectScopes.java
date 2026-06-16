package com.nexusai.control.api.security;

import java.util.List;

/**
 * 项目 M2M 令牌作用域（细粒度令牌·最小权限）。控制台账密会话恒为平台全权，
 * 项目令牌按 {@code nai_project.scope} 分两档：
 * <ul>
 *   <li>{@link #PLATFORM}：平台运营令牌——可达全部 {@code /admin/**}（渠道/路由/调度/IP 安全/模型目录/项目等供给侧+全局配置）。</li>
 *   <li>{@link #PROJECT}（默认）：租户自助令牌——仅可达 {@link #PROJECT_ALLOWED} 列出的消费侧自助端点
 *       （建 Key / 充值扣费 / 工作区 / 自身用量），<b>碰不到供给侧与他项目</b>。</li>
 * </ul>
 *
 * @author nexusai
 */
public final class ProjectScopes {

    public static final String PLATFORM = "platform";
    public static final String PROJECT = "project";

    /** setError 内部识别"作用域不足"的标记（区别于未登录 401）。 */
    public static final String SCOPE_DENIED = "nexusai.scope.denied";

    /** 项目作用域令牌允许的自助端点前缀（消费侧·最小集）。 */
    private static final List<String> PROJECT_ALLOWED = List.of(
            "/admin/keys", "/admin/quota", "/admin/workspaces", "/admin/usage");

    private ProjectScopes() {
    }

    /** 该路径是否允许项目作用域令牌访问（精确或以 {@code prefix/} 开头）。 */
    public static boolean allowsProjectToken(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : PROJECT_ALLOWED) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
