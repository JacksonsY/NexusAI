package com.nexusai.control.api.security.vo;

import java.util.List;

/**
 * 控制台当前登录用户信息视图。component 名即 JSON 键，前端 {@code fetchAdminUserInfo} 据此渲染身份/菜单：
 * <ul>
 *   <li>{@code username}：登录账号；</li>
 *   <li>{@code role}：1 项目运维 / 2 超管（前端按角色裁菜单·静态超管路由）；</li>
 *   <li>{@code projects}：项目运维被授权的项目码集（超管为空=全项目）；</li>
 *   <li>{@code loginId}：Sa-Token 登录主体（未登录为 {@code null}·序列化为 JSON null）。</li>
 * </ul>
 *
 * @author nexusai
 */
public record AdminUserInfoView(String username, Integer role, List<String> projects, Object loginId) {
}
