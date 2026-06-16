package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 项目（工作区 + 控制面集成边界）：网关的顶层组织单元。
 * <ul>
 *   <li><b>工作区</b>：一个项目归属一个接入方（业务面 控制面 / 独立运维 / 其它 SaaS 后端），其下 sk- Key、用量按项目归集；</li>
 *   <li><b>集成凭证</b>：凭 {@code (project_code, token)} 调 /admin/** 控制面端点——<b>替代单个静态 nexusai.admin.token</b>，
 *       多项目凭证隔离 + 可独立吊销/轮转/审计。</li>
 * </ul>
 * 控制台账密登录（Sa-Token 会话）自举创建项目；网关纯转发面，不做 members/roles 重 RBAC（控制台单超管够用）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_project")
public class NaiProject extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 项目稳定标识（如 my-saas / standalone-ops·唯一） */
    private String projectCode;

    private String name;

    private String description;

    /** 通信认证令牌（M2M·经 NexusAI-Admin-Token 头携带·唯一） */
    private String token;

    /** 令牌作用域：platform=平台运营(可达全部 /admin·渠道/路由/项目等) / project=仅租户自助端点(默认·见 ProjectScopes) */
    private String scope;

    // ===== 访问策略（运行时由 ProjectPolicyFilter 执行）=====
    // 约束项目下全部 sk- Key（nai_token.project_code 归属）；各项 JSON 数组·null/空 = 不限制

    /** 允许的模型白名单（JSON 数组·如 ["gpt-4o","deepseek-chat"]） */
    private String allowedModels;

    /** 允许的能力白名单（JSON 数组·chat/embedding/image/rerank） */
    private String allowedModalities;

    /** 允许的渠道 id（JSON 数组·路由候选硬过滤） */
    private String allowedChannelIds;

    /** 允许的渠道标签（JSON 数组·按 channelTagsMatchMode 与 nai_channel.tags 匹配） */
    private String allowedChannelTags;

    /** 标签匹配模式：any=包含任一 / all=包含全部 / none=不包含任一 */
    private String channelTagsMatchMode;

    // ===== 项目额度池（项目制·USD 运行时真理源基线·按项目预算）=====

    /** 项目 USD 额度池（DECIMAL(18,6)·DB 基线·运行时真值在 Redis micro-USD；控制面充值/控制台充值经额度操作调整） */
    private BigDecimal quotaUsd;

    /** 不限额（内部自用项目·闸门走哨兵不扣减） */
    private Boolean unlimited;

    /** 项目级每分钟请求数总闸（项目下全部 Key 共享·防单项目打爆上游；0/NULL=不限·RateLimitFilter 执行） */
    private Integer rpmLimit;

    /** 项目级每分钟 token 数总闸（项目下全部 Key 共享；0/NULL=不限·RateLimitFilter 软闸门执行） */
    private Integer tpmLimit;

    /** 1=active 启用 0=archived 归档/停用 */
    private Integer status;
}
