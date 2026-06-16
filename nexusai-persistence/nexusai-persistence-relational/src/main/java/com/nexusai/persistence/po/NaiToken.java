package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * API Key 鉴权（元数据为控制面下发的只读快照）。<b>项目制（2026-06-12 定案）</b>：USD 额度池在
 * {@link NaiProject}（项目级真理源），本表 {@link #quotaUsd} 仅为<b>可选 Key 子限额</b>。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_token")
public class NaiToken extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** API Key 的 SHA-256 哈希（唯一；明文 sk- 永不落库） */
    private String keyHash;

    /** 运维展示名（独立签发 Key 必填；外部同步 Key 可空） */
    private String name;

    /** 1=网关控制台独立签发，0=外部业务面同步 */
    private Integer standalone;

    /** 归属项目（必填 → nai_project.project_code·额度池/访问策略/用量归集均按项目；控制面下发） */
    private String projectCode;

    /** 归属项目内工作区（可选 → nai_workspace.id·api_key 双挂；非空=扣工作区钱包·空=B2B 直扣项目池） */
    private Long workspaceId;

    private String groupCode;

    /** 模型白名单（JSON）：Model Permission */
    private String modelLimits;

    /** 允许的来源 IP（JSON/逗号分隔） */
    private String allowIps;

    /** 渠道/provider 白名单（JSON 数组·空=不限） */
    private String allowProviders;

    /** 协议入口白名单（JSON 数组 如 ["openai","anthropic"]·空=不限） */
    private String allowApiFormats;

    /** Key 子限额 USD（DECIMAL(18,6)·可选·防单 Key 泄漏打爆项目池；NULL/≤0=不设·仅受项目池约束） */
    private BigDecimal quotaUsd;

    /** 每分钟请求数上限（0=不限） */
    private Integer rpmLimit;

    /** 每分钟 token 数上限（0=不限） */
    private Integer tpmLimit;

    /** 并发上限（0=不限） */
    private Integer concurrentLimit;

    /** BYOK 客户自带上游凭证（AES-GCM 密文·非空则替代渠道池 Key 调上游） */
    private String byokCredential;

    private Long expiredTime;

    /** 到期自动删除（1=删除·0=仅停用） */
    private Integer autoDeleteOnExpiry;

    /** Key 级特性设置（JSON·含 chat_pii_redaction:{enabled,injectModelInstruction} 等） */
    private String featureSettings;

    /** 1=正常 0=停用 */
    private Integer status;
}
