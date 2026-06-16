package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * PO 审计基类（仅 createDt/updateDt 两个审计时间字段，不引入外部基类）。
 *
 * @author nexusai
 */
@Data
public abstract class BasePO implements Serializable {

    @TableField(value = "create_dt", fill = FieldFill.INSERT)
    private LocalDateTime createDt;

    @TableField(value = "update_dt", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateDt;
}
