package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * UsageEvent outbox（用量事件可靠投递·投递确认后短保留；与 nai_request_log 同源）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_usage_log")
public class NaiUsageLog extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String requestId;
    private String projectCode;

    /** 项目内工作区（nai_workspace.id·B2C 账单按工作区聚合·B2B 为空） */
    private Long workspaceId;
    private Long channelId;
    private Long keyId;
    private String model;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer cacheTokens;
    private Integer cacheCreationTokens;
    private Integer totalTokens;

    /** 上游成本基准（USD·DECIMAL(20,8)） */
    private BigDecimal costBasis;
    /** 本次向客户扣减的 USD（DECIMAL(18,6)·= 计费售价；与 costBasis 之差为毛利） */
    private BigDecimal costUsd;

    private Integer status;
    /** 是否已投递到业务面 */
    private Boolean delivered;
}
