package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 调度策略分组：把"优先级模式 × 调度策略"打包为可命名、可启停的策略，
 * 系统默认策略（{@code is_system_default=1} 且启用）驱动运行时选路；其余策略为备选草稿，可一键切换为默认。
 * <p>{@code config_json} 结构（代码式·无规则引擎）：</p>
 * <pre>{
 *   "defaultPolicy": { "priorityMode": "provider|key", "schedulingMode": "cache_affinity|load_balance|fixed_order" },
 *   "modelPolicies": [ { "model": "gpt-4o", "priorityMode": "...", "schedulingMode": "..." } ]
 * }</pre>
 * 不做 provider/key 顺序覆盖（优先级真理源在 nai_ability/nai_channel）与条件 rules（已撤规则引擎）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_routing_group")
public class NaiRoutingGroup extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;


    /** 策略名称 */
    private String name;

    /** 策略描述 */
    private String description;

    /** 是否启用（停用的系统默认策略不驱动选路·回退内置默认） */
    private Boolean enabled;

    /** 系统默认（全局唯一·运行时选路依据） */
    private Boolean isSystemDefault;

    /** 策略配置 JSON（defaultPolicy + modelPolicies） */
    private String configJson;

    /** 乐观锁版本（每次保存 +1·展示用） */
    private Integer version;
}
