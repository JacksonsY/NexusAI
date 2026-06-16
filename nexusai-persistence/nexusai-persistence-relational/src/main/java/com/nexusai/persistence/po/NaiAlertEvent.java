package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 运维告警事件：规则触发即落一条 firing，
 * 指标恢复自动转 resolved；也可手动 manual_resolved。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_alert_event")
public class NaiAlertEvent extends BasePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;

    /** 规则名快照（规则删除后事件仍可读） */
    private String ruleName;

    /** P0/P1/P2/P3 */
    private String severity;

    /** firing / resolved / manual_resolved */
    private String status;

    private String title;

    private String description;

    /** 触发时的指标值 */
    private Double metricValue;

    /** 触发时的阈值快照 */
    private Double thresholdValue;

    private LocalDateTime firedDt;

    private LocalDateTime resolvedDt;

    /** 1=已推送通知 */
    private Integer notifiedFlag;
}
