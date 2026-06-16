package com.nexusai.common.event;

/**
 * 控制面审计事件（Spring 进程内事件）：登录成败等无法被审计过滤器从响应状态推断的事件，
 * 由产生方（control-api AdminAuthController）显式发布，control-domain 侧监听落库——
 * 避免 control-api → control-domain 的依赖（control-api 仅依赖 common/persistence）。
 *
 * @param eventType    事件类型（admin_login_success / admin_login_failed / admin_logout）
 * @param actor        操作者标识
 * @param description  事件描述
 * @param ip           来源 IP
 * @param errorMessage 失败原因（可空）
 * @author nexusai
 */
public record AdminAuditEvent(String eventType, String actor, String description, String ip, String errorMessage) {
}
