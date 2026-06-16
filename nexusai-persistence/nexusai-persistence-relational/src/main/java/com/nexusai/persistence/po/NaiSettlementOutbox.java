package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 结算补偿 outbox：QuotaLedger.settle 终态失败后持久化，后台重试，避免资金事件只停留在日志里。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_settlement_outbox")
public class NaiSettlementOutbox extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String requestId;
    private Long tokenId;
    private String projectCode;
    private Long refundMicroUsd;
    /** 0=pending, 1=done, 2=abandoned */
    private Integer status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime nextRetryAt;
}
