package com.nexusai.common.exception;

import lombok.Getter;

/**
 * 网关错误码（OpenAI 兼容错误体的 type/code 映射来源）。
 * code 对齐 HTTP 语义，便于 relay 直接出 OpenAI 风格 error。
 *
 * @author nexusai
 */
@Getter
public enum GatewayErrorCode {

    // 鉴权 / 额度
    UNAUTHORIZED(401, "invalid_api_key", "API Key 无效或已停用"),
    QUOTA_EXHAUSTED(402, "insufficient_quota", "额度不足（USD 余额预扣失败）"),
    MODEL_PRICE_UNCONFIGURED(402, "model_price_unconfigured", "模型未配置价格（拒绝计费·防白嫖）"),
    MODEL_NOT_PERMITTED(403, "model_not_permitted", "该 Key 无权访问此模型"),
    API_FORMAT_NOT_PERMITTED(403, "api_format_not_permitted", "该 Key 无权使用此协议入口"),
    IP_NOT_ALLOWED(403, "ip_not_allowed", "该 Key 不允许此来源 IP"),
    PROJECT_SCOPE_DENIED(403, "project_scope_denied", "项目令牌无权操作其他项目"),
    RATE_LIMITED(429, "rate_limit_exceeded", "触发限流"),

    // 路由 / 上游
    NO_CHANNEL_AVAILABLE(503, "no_channel_available", "无可用渠道（路由失败）"),
    NO_KEY_AVAILABLE(503, "no_key_available", "渠道内无可用 Key（渠道密钥耗尽）"),
    UPSTREAM_ERROR(502, "upstream_error", "上游返回错误"),
    UPSTREAM_TIMEOUT(504, "upstream_timeout", "上游超时"),

    // 协议 / 参数
    BAD_REQUEST(400, "invalid_request_error", "请求参数错误"),
    MODEL_NOT_FOUND(404, "model_not_found", "模型不存在"),
    GENERATION_NOT_FOUND(404, "not_found", "请求记录不存在或尚未落库"),
    MODEL_DISABLED(403, "model_disabled", "模型已停用"),

    // 内容安全（Guardrail）
    CONTENT_BLOCKED(403, "content_policy_violation", "内容被安全策略拦截"),

    // 内部
    INTERNAL_ERROR(500, "internal_error", "网关内部错误");

    private final int httpStatus;
    private final String code;
    private final String message;

    GatewayErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
