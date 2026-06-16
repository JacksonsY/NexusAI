package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 异步任务（MJ/视频）：submit→轮询→done/failed；pre-charge 冻结 USD，终态多退少补/退款。
 * 大文件结果 URL 抢转存 OSS（不双向穿透网关）留后续。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_task")
public class NaiTask extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 网关任务 ID（对外） */
    private String taskId;
    /** 上游任务 ID（轮询用） */
    private String upstreamTaskId;

    private String projectCode;
    private Long tokenId;
    private Long channelId;
    private Long keyId;

    /** midjourney / video */
    private String modality;
    private String action;
    private String model;

    /** SUBMITTED / IN_PROGRESS / SUCCESS / FAILED */
    private String status;
    private String progress;

    /** 预扣冻结的 USD（DECIMAL(18,6)·终态结算/退款依据） */
    private BigDecimal frozenUsd;
    private String resultUrl;
    private String failReason;
    private String submitBody;

    /**
     * 行认领租约到期（claim 模式 FOR UPDATE SKIP LOCKED 多实例去重用）。
     * <b>注意：该字段进入 MyBatis-Plus 默认 SELECT 全列清单，故所有部署（含 leader 默认模式）都必须存在
     * {@code poll_lease_until} 列，存量库须 ALTER 补列，否则 leader 轮询/查询/列表全部报「Unknown column」。</b>
     */
    private LocalDateTime pollLeaseUntil;
}
