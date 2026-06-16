package com.nexusai.persistence.log.clickhouse;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 请求日志 PO（nai_request_log·ClickHouse driver 私有·MyBatis-Plus）。
 * <p><b>无 @TableId</b>：ClickHouse MergeTree 是只追加分析表，{@code id} 列 DEFAULT 0 无业务意义，
 * 不参与 MyBatis-Plus 主键/雪花生成——insert/selectList/selectPage 均不需要主键。
 * {@code stream_mode} 为 CH UInt8(0/1)，故用 Integer。create_dt 由 store 显式设置（不依赖 MetaObjectHandler·独立工厂）。
 *
 * @author nexusai
 */
@Data
@TableName("nai_request_log")
public class NaiRequestLog {

    private String requestId;
    private String traceId;
    private String projectCode;

    /** 项目内工作区（nai_workspace.id·B2B 为空） */
    private Long workspaceId;
    private Long channelId;
    private Long keyId;

    private String model;
    private String targetModel;
    private String modality;
    /** CH UInt8 0/1 */
    private Integer streamMode;

    /** 入站 API 格式（openai:chat / claude:messages / ...） */
    private String apiFormat;
    /** 上游端点 API 格式（渠道协议族推导） */
    private String endpointApiFormat;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer cacheTokens;
    private Integer cacheCreationTokens;
    private Integer totalTokens;
    private BigDecimal costUsd;

    private Integer status;
    private Integer httpStatus;
    private String errorCode;

    /** 同渠道换 Key 重试次数 */
    private Integer retryCount;
    /** 跨渠道故障转移次数 */
    private Integer fallbackCount;
    /** usage 结算来源（parsed/estimated/image-fallback/client-abort/failed/settle-error） */
    private String usageSource;

    private Long ttftMs;
    private Long durationMs;

    private String clientIp;

    /** 客户端 User-Agent（截 512） */
    private String userAgent;
    /** UA 推导的客户端家族 */
    private String clientFamily;
    private String promptHash;

    private LocalDateTime createDt;
}
