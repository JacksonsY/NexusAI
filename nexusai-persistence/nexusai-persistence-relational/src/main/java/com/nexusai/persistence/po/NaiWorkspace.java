package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 项目内工作区（三层身份链 2026-06-12·按业务方用户隔离）：
 * B2C 中转站形态的终端工作区钱包——project=站点/分销商（容器），
 * workspace=散户工作区（<b>钱包主闸</b>），token 挂 workspace；B2B 套壳应用形态不建 workspace（token 直挂项目池）。
 * <p>{@code (projectCode, externalId)} 复合唯一（业务面用户标识·幂等键）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_workspace")
public class NaiWorkspace extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属项目（容器·→ nai_project.project_code） */
    private String projectCode;

    /** 业务面用户标识（项目内唯一·幂等键·如 业务面 workspaceId） */
    private String externalId;

    /** 展示名（可选） */
    private String displayName;

    /** 工作区 USD 钱包（DECIMAL(18,6)·主闸·DB 基线·运行时真值在 Redis micro-USD·按 user 维度充退） */
    private BigDecimal quotaUsd;

    /** 不限额用户（内部·闸门不扣减） */
    private Boolean unlimited;

    /** 工作区级每分钟请求数闸（B2C 散户级·该工作区全部 Key 共享；0/NULL=不限·RateLimitFilter 执行） */
    private Integer rpmLimit;

    /** 工作区级每分钟 token 数闸（0/NULL=不限·RateLimitFilter 软闸门执行） */
    private Integer tpmLimit;

    /** 1=正常 0=停用 */
    private Integer status;
}
