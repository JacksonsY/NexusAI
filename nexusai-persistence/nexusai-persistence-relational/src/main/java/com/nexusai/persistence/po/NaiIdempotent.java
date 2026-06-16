package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 幂等记录（防重复提交）。热路径去重用 Redis SETNX（{@code nai:idem:}）；本表作审计/持久兜底。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_idempotent")
public class NaiIdempotent extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String idempotentKey;
    private String requestId;
    private Integer status;
}
