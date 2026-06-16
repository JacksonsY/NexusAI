package com.nexusai.persistence.log;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 请求日志领域模型（存储无关）：跨 MySQL/ClickHouse driver 的统一读写载体，<b>不带任何 ORM 注解</b>。
 * 各 driver 负责与自身存储行（PO / ClickHouse 行）互转。
 *
 * @author nexusai
 */
@Data
public class RequestLogRecord {

    private Long id;
    private String requestId;
    private String traceId;
    /** 归属项目（nai_project.project_code·身份链=项目→Token→请求） */
    private String projectCode;

    /** 项目内工作区（nai_workspace.id·B2B 为空） */
    private Long workspaceId;
    private Long channelId;
    private Long keyId;

    /** 请求模型（客户端原始） */
    private String model;
    /** 上游真实模型（model_mapping 后） */
    private String targetModel;
    /** 模态：chat/embedding/image/rerank/... */
    private String modality;
    /** 是否流式 */
    private Boolean streamMode;

    /** 入站 API 格式（openai:chat / claude:messages / ...） */
    private String apiFormat;
    /** 上游端点 API 格式（渠道协议族推导·与 apiFormat 不同即发生格式转换） */
    private String endpointApiFormat;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer cacheTokens;
    private Integer cacheCreationTokens;
    private Integer totalTokens;
    /** 本次向客户扣减 USD（DECIMAL(18,6)） */
    private BigDecimal costUsd;

    /** 1=成功 0=失败 */
    private Integer status;
    /** 返回 HTTP 状态码 */
    private Integer httpStatus;
    /** 失败错误码（成功为空） */
    private String errorCode;

    /** 同渠道换 Key 重试次数 */
    private Integer retryCount;
    /** 跨渠道故障转移次数 */
    private Integer fallbackCount;
    /** usage 结算来源：parsed/estimated/image-fallback/client-abort/failed/settle-error（client-abort=已取消） */
    private String usageSource;

    /** 首字节耗时（ms·流式 TTFT） */
    private Long ttftMs;
    /** 总耗时（ms） */
    private Long durationMs;

    private String clientIp;
    /** 客户端 User-Agent（截 512） */
    private String userAgent;
    /** UA 推导的客户端家族（claude_code/codex/cursor/...） */
    private String clientFamily;
    /** 请求体 sha256（非正文·默认隐私策略） */
    private String promptHash;

    /** 落库时间（写入由 driver 兜底填充） */
    private LocalDateTime createDt;
}
