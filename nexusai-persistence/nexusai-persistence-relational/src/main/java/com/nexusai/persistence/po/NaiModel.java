package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 模型元数据（售价/计价倍率在业务面；结构化 USD 倍率经控制面快照下发供强闸门精确预扣）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_model")
public class NaiModel extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String modelKey;
    private String modelAlias;
    private String description;
    private String modality;
    private Integer contextLength;
    private Integer maxOutputTokens;
    private String capabilities;
    private String enableGroups;

    /** 1=活跃 0=停用（停用后入站请求 MODEL_DISABLED 拒绝） */
    private Integer status;

    /** 1=允许上游元数据同步覆盖 0=本地锁定不参与同步 */
    private Integer syncOfficial;

    /** 入站别名归一正则（JSON 数组）：请求模型名命中任一规则 → 归一到本 modelKey */
    private String aliasPatterns;

    /** 上游成本基准（BigDecimal·DECIMAL(20,8)） */
    private BigDecimal costBasis;

    /** 结构化 USD 倍率：input/output 非对称 + cached + 按请求/按图（控制面快照下发·钱的技术投影非售价）。 */
    private BigDecimal inputRate;
    private BigDecimal outputRate;
    private BigDecimal cachedInputRate;
    private BigDecimal writeCachedRate;
    private BigDecimal perRequestFee;
    private BigDecimal perImageFee;
}
