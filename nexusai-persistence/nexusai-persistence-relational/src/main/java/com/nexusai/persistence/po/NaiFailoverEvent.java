package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 故障转移/重试事件审计（对抗验证 H7）：每次换渠道/换 Key 重试或重试耗尽记一行，使失败链请求结束后仍可审计。
 * <p>纯 append-only·热路径外 fire-and-forget 落库（{@code FailoverAuditService}），绝不影响主请求。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_failover_event")
public class NaiFailoverEvent extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 请求 ID（同 RelayContext.requestId·串起同一请求的多次尝试） */
    private String requestId;
    /** 第几次尝试触发转移（1 起） */
    private Integer attempt;
    /** 被排除/失败的渠道（5xx/透传异常时填） */
    private Long fromChannelId;
    /** 被排除/失败的 Key */
    private Long fromKeyId;
    /** 错误码或上游状态码（如 "500"/"UPSTREAM_TIMEOUT"/"TRANSIENT"） */
    private String errorCode;
    /** 转移原因：upstream-5xx / upstream-429 / skip-retry / transient-xxx */
    private String reason;
    /** 结果：retry（换号继续）/ exhausted（重试耗尽终态报错） */
    private String outcome;
}
