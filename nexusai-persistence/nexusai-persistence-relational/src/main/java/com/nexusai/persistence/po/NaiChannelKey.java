package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上游渠道密钥：渠道内多 Key 凭证（AES-256-GCM 加密）+ 四层选号/打分/自适应RPM/并发槽/Key级熔断载体。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_channel_key")
public class NaiChannelKey extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long channelId;

    /** 单 Key 上游凭证（AES-256-GCM 密文；日志打印前强制脱敏） */
    private String credential;

    private Integer weight;
    private Integer priority;
    /** 1=启用 0=停用 */
    private Integer status;

    /** 自适应学习到的真实 RPM 上限 */
    private Integer rpmLearned;
    /** 并发槽上限 */
    private Integer maxConcurrency;

    /** Key 级熔断态 */
    private Long rateLimitResetAt;
    private Long overloadUntil;
    private Long tempUnschedulableUntil;
    private Integer healthScore;
    private Long successCount;
    private Long errorCount;
}
