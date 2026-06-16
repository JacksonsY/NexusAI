package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 路由索引表：(group_code,model,channel_id) 决定哪些渠道能服务某 group+model，
 * 带 priority/weight 供两级调度第一级"选渠道"。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_ability")
public class NaiAbility extends BasePO {

    private String groupCode;
    private String model;
    private Long channelId;

    private Integer priority;
    private Integer weight;
    private Boolean enabled;
    private String modality;
}
