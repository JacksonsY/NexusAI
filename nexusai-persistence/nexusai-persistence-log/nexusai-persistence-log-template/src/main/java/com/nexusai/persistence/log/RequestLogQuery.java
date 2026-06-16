package com.nexusai.persistence.log;

import java.util.Map;

import java.time.LocalDateTime;

/**
 * 请求日志分页/聚合查询条件（存储无关·使用记录页多维筛选）。
 * <p>时间维度统一为 [begin, end] 闭区间（null=不限），由上层把「近 N 小时」等预设换算成绝对时间。
 *
 * @param projectCode  项目过滤（空=不限·身份链=项目→Token→请求）
 * @param channelId    渠道过滤（null=不限）
 * @param model        模型精确过滤（空=不限）
 * @param requestId    请求 ID 前缀匹配（空=不限·likeRight 走索引）
 * @param modality     模态过滤 chat/embedding/image/...（空=不限）
 * @param streamMode   流式过滤：true=流式 false=标准（null=不限）
 * @param success      结果过滤：true=成功(http&lt;400) false=失败(http&gt;=400)（null=不限）
 * @param status       HTTP 状态码精确过滤（null=不限）
 * @param apiFormat    入站 API 格式过滤（openai:chat/claude:messages/...）
 * @param clientFamily 客户端家族过滤（claude_code/codex/...）
 * @param statusFilter 扩展状态过滤（{@code cancelled}=usage_source client-abort、
 *                     {@code hasRetry}=retry_count&gt;0、{@code hasFallback}=fallback_count&gt;0；空=不限）
 * @param search       通用搜索（空=不限·request_id/trace_id/model/target_model/error_code 任一模糊命中）
 * @param hideUnknown  隐藏 unknown 请求（true=排除 model 为空的行）
 * @param begin        起始时间（含·null=不限）
 * @param end          截止时间（含·null=不限）
 * @param orderBy      排序字段（前端列 key·经 {@link #SORT_COLUMNS} 白名单映射·null/未知=默认按落库序）
 * @param orderDesc    是否降序（默认 true）
 * @param pageNum      页码（1 起）
 * @param pageSize     每页条数
 * @author nexusai
 */
public record RequestLogQuery(String projectCode, Long channelId, String model,
                              String requestId, String modality, Boolean streamMode, Boolean success,
                              Integer status, String apiFormat, String clientFamily, String statusFilter,
                              String search, Boolean hideUnknown,
                              LocalDateTime begin, LocalDateTime end,
                              String orderBy, boolean orderDesc,
                              long pageNum, long pageSize) {

    /** 可排序列白名单（前端列 key → 物理列·绝不把外部串拼进 ORDER BY）。 */
    public static final Map<String, String> SORT_COLUMNS = Map.of(
            "createDt", "create_dt",
            "durationMs", "duration_ms",
            "ttftMs", "ttft_ms",
            "totalTokens", "total_tokens",
            "costUsd", "cost_usd");

    /** 可聚合维度白名单（聚合端点 groupBy → 物理列·绝不把外部串拼进 GROUP BY）。 */
    public static final Map<String, String> AGG_COLUMNS = Map.of(
            "model", "model",
            "project", "project_code",
            "channel", "channel_id",
            "apiFormat", "api_format");

    /** 扩展状态过滤合法值（驱动据此生成谓词·白名单外忽略）。 */
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_HAS_RETRY = "hasRetry";
    public static final String STATUS_HAS_FALLBACK = "hasFallback";

    /** 解析排序物理列（白名单外/未指定 → null=驱动默认排序）。 */
    public String sortColumn() {
        return orderBy == null ? null : SORT_COLUMNS.get(orderBy);
    }

    /** 条件是否含某字符串字段（driver 构建 Wrapper 时判空用）。 */
    public static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
