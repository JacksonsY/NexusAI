package com.nexusai.persistence.log;

/**
 * 请求日志入库字段列宽钳制（nai_request_log 关系型 DDL 列宽的单一真理源）。
 * <p>上游生产侧仅截断 user_agent，其余变长列（model/target_model/error_code 等）不设限——若客户端传入超长
 * 值，MySQL/PG 落库会触发「Data too long」而整行失败，进而在消费侧沦为反复重投的毒消息。故在<b>入库前</b>
 * 统一按 DDL 列宽钳制。ClickHouse 侧为无长度上限的 String，钳到同宽亦无副作用（保持三后端数据一致）。
 *
 * @author nexusai
 */
public final class LogFieldLimits {

    private static final int REQUEST_ID = 64;
    private static final int TRACE_ID = 64;
    private static final int PROJECT_CODE = 64;
    private static final int MODEL = 128;
    private static final int TARGET_MODEL = 128;
    private static final int MODALITY = 32;
    private static final int API_FORMAT = 32;
    private static final int ENDPOINT_API_FORMAT = 32;
    private static final int ERROR_CODE = 64;
    private static final int USAGE_SOURCE = 16;
    private static final int CLIENT_IP = 64;
    private static final int USER_AGENT = 512;
    private static final int CLIENT_FAMILY = 32;
    private static final int PROMPT_HASH = 64;

    private LogFieldLimits() {
    }

    /** 按 DDL 列宽就地钳制各变长字段（超长截断·不足原样），入库前调用防「Data too long」毒消息。 */
    public static void clamp(RequestLogRecord r) {
        if (r == null) {
            return;
        }
        r.setRequestId(cut(r.getRequestId(), REQUEST_ID));
        r.setTraceId(cut(r.getTraceId(), TRACE_ID));
        r.setProjectCode(cut(r.getProjectCode(), PROJECT_CODE));
        r.setModel(cut(r.getModel(), MODEL));
        r.setTargetModel(cut(r.getTargetModel(), TARGET_MODEL));
        r.setModality(cut(r.getModality(), MODALITY));
        r.setApiFormat(cut(r.getApiFormat(), API_FORMAT));
        r.setEndpointApiFormat(cut(r.getEndpointApiFormat(), ENDPOINT_API_FORMAT));
        r.setErrorCode(cut(r.getErrorCode(), ERROR_CODE));
        r.setUsageSource(cut(r.getUsageSource(), USAGE_SOURCE));
        r.setClientIp(cut(r.getClientIp(), CLIENT_IP));
        r.setUserAgent(cut(r.getUserAgent(), USER_AGENT));
        r.setClientFamily(cut(r.getClientFamily(), CLIENT_FAMILY));
        r.setPromptHash(cut(r.getPromptHash(), PROMPT_HASH));
    }

    private static String cut(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max);
    }
}
