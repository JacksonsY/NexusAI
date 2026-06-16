package com.nexusai.control.api.security.vo;

/**
 * 控制台当前登录用户信息视图（替代原裸 {@code Map<String,Object>}）。
 * <p>component 名即 JSON 键，与原 Map 键 {@code username}/{@code loginId} 一致。
 * {@code loginId} 保持 {@link Object} 以与 {@code StpUtil.getLoginIdDefaultNull()} 的可空返回逐字节同构
 * （未登录时为 {@code null}·序列化为 JSON null）。
 *
 * @param username 控制台账号
 * @param loginId  Sa-Token 登录主体标识（未登录为 {@code null}）
 * @author nexusai
 */
public record AdminUserInfoView(String username, Object loginId) {
}
