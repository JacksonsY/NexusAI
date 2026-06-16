package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 运维告警规则：
 * 指标 + 比较符 + 阈值 + 评估窗口/持续时长/冷却期，由 OpsAlertEvaluatorJob 周期评估。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_alert_rule")
public class NaiAlertRule extends BasePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 1=启用 0=停用 */
    private Integer enabledFlag;

    /** 指标类型：error_rate/success_rate/qps/p99_latency_ms/ttft_p99_ms/inflight_concurrency/
     *  schedulable_key_ratio/circuit_open_count/node_offline_count/failover_count/
     *  cpu_usage_percent/memory_usage_percent */
    private String metricType;

    /** 比较符：> >= < <= == != */
    private String operator;

    private Double threshold;

    /** 评估窗口（分钟·指标按该窗口聚合） */
    private Integer windowMinutes;

    /** 持续时长（分钟·连续违反达到才触发） */
    private Integer sustainedMinutes;

    /** 冷却期（分钟·两次触发的最小间隔，0=不限） */
    private Integer cooldownMinutes;

    /** 级别：P0/P1/P2/P3 */
    private String severity;

    /** 1=触发时走通知服务推送 */
    private Integer notifyFlag;

    private LocalDateTime lastTriggeredDt;
}
