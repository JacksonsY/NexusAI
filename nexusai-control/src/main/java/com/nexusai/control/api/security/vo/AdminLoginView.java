package com.nexusai.control.api.security.vo;

/**
 * 控制台账密登录响应视图（替代原裸 {@code Map<String,Object>}）。
 * <p>component 名即 JSON 键，与前端 {@code fetchAdminLogin} 期望的 {@code { token, username }} 逐字段对齐。
 *
 * @param token    Sa-Token 会话令牌（后续以 NexusAI-Admin-Token 头携带）
 * @param username 登录账号
 * @author nexusai
 */
public record AdminLoginView(String token, String username) {
}
