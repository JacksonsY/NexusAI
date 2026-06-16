package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 渠道（上游入口·一渠道挂多 Key 池；凭证在 nai_channel_key；渠道差异走扁平配置字段直编）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_channel")
public class NaiChannel extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 备注（提供商页行内编辑） */
    private String description;

    /** 主站链接（提供商页名称旁外链） */
    private String website;

    /**
     * 上游 API Key 明文（渠道表单「URL + Key 一体填写」透传字段·不落本表）：
     * 用户拍板本项目无渠道密钥概念，保存时由 admin 面加密 upsert 到 nai_channel_key 首条。
     */
    @TableField(exist = false)
    private String credential;

    /** 协议家族（ApiFormat.name()）：决定走哪个代码适配器（档③） */
    private String protocol;
    private String vendor;
    private String authType;
    private String baseUrl;

    /** 1=启用 0=停用 2=归档 */
    private Integer status;
    private Integer weight;
    private Integer priority;
    private Integer autoBan;

    /** 渠道标签（JSON 数组·如 ["官方","高速"]·供项目访问策略按标签圈渠道） */
    private String tags;

    /** 渠道差异覆盖字段（JSON·扁平配置直编：模型映射/参数覆盖/请求头覆盖等） */
    private String modelMapping;
    private String paramOverride;
    private String headerOverride;
    private String statusCodeMapping;
    private String setting;
    private String endpoints;

    /** 计费类型：monthly_quota=月卡额度 / pay_as_you_go=按量付费 / free_tier=免费套餐 */
    private String billingType;

    /** 月卡周期额度（USD·仅 billing_type=monthly_quota 时有意义） */
    private BigDecimal quotaUsd;

    /** 月卡重置周期（天） */
    private Integer quotaResetDays;

    /** 月卡当前周期开始时间（毫秒时间戳） */
    private Long quotaStartAt;

    /** 渠道过期时间（毫秒时间戳·0=永不过期） */
    private Long quotaExpiresAt;

    /** 流式首字节超时（秒·0=用全局默认）：接线到转发面 per-request responseTimeout（响应头到达） */
    private Integer firstByteTimeout;

    /** 非流式请求总超时（秒·0=不限）：接线到转发面非流式整链 timeout */
    private Integer requestTimeout;

    /** 渠道级熔断态 */
    private Long rateLimitResetAt;
    private Long overloadUntil;
    private Long tempUnschedulableUntil;
    private Integer healthScore;
    private Long successCount;
    private Long errorCount;
}
