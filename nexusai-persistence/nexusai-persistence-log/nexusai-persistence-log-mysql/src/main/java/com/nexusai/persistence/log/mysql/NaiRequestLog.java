package com.nexusai.persistence.log.mysql;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 请求日志 PO（网关<b>最大表</b>·log-mysql driver 的存储行）。
 * <p>本 PO 是 MySQL driver 私有实现细节（不再被上层直接引用），上层只认领域模型 {@code RequestLogRecord}。
 * 不继承 relational 的 BasePO（避免 log 域反向依赖关系域）；create_dt 由 MetaObjectHandler 按字段名兜底填充。
 * 默认不存 prompt 正文，只存 sha256(prompt_hash)。
 *
 * @author nexusai
 */
@Data
@TableName("nai_request_log")
public class NaiRequestLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

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
    private Boolean streamMode;

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

    @TableField(value = "create_dt", fill = FieldFill.INSERT)
    private LocalDateTime createDt;
}
