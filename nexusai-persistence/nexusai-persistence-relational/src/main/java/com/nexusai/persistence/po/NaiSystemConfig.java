package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统配置 KV：站点信息/全局代理/请求记录/清理策略/模块开关
 * （module.{name}.enabled）统一落此表；值为 JSON 字面量字符串。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_system_config")
public class NaiSystemConfig extends BasePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cfgKey;

    /** JSON 字面量（"abc" / 5 / true / [..]） */
    private String cfgValue;

    private String description;
}
