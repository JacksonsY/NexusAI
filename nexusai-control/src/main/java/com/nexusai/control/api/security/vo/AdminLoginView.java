package com.nexusai.control.api.security.vo;

/**
 * 控制台账密登录响应视图。component 名即 JSON 键，与前端 {@code fetchAdminLogin} 期望对齐：
 * {@code { token, username, role }}。
 *
 * @param token    Sa-Token 会话令牌（后续以 NexusAI-Admin-Token 头携带）
 * @param username 登录账号
 * @param role     角色：1 项目运维 / 2 超管（前端据此路由/裁菜单）
 * @author nexusai
 */
public record AdminLoginView(String token, String username, Integer role) {
}
