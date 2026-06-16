package com.nexusai.client;

import com.nexusai.contract.key.KeySyncRequest;
import com.nexusai.contract.quota.QuotaOpRequest;

import java.math.BigDecimal;

/**
 * 网关控制面 Admin 客户端（业务面→网关·单向 HTTP·经 NexusAI-Admin-Token）。
 * <p>覆盖 业务面→网关的 KeySync 与 QuotaOperation 控制面接口；
 * UsageEvent 用量回传是网关→业务面 经 Redis Stream 回传（非本客户端职责）。
 * <p>所有金额单位为 <b>USD</b>（BigDecimal·2026-06-13 credits→USD）；方法同步阻塞（契合 业务面 servlet）；
 * 失败抛 {@link NexusAiClientException}。
 *
 * @author nexusai
 */
public interface NexusAiAdminClient {

    // ===== KeySync =====

    /**
     * 同步（Upsert）一个 Key 到网关（签发新 Key / 改额度/限流/白名单等）。
     *
     * @return 网关 nai_token 主键（后续 Quota 操作用）
     */
    Long syncKey(KeySyncRequest request);

    /** 吊销 Key（停用·网关即时清各节点缓存）。 */
    void revokeKey(Long tokenId);

    // ===== QuotaOperation（三层身份链·项目池/工作区钱包双粒度） =====

    /**
     * 额度操作（add/subtract/freeze·externalWorkspaceId 空=项目池，非空=工作区钱包）。
     *
     * @return 操作后对应粒度余额 USD
     */
    BigDecimal quotaOp(QuotaOpRequest request);

    /** 充值项目池 USD → 返回操作后余额 USD。 */
    default BigDecimal addUsd(String projectCode, BigDecimal usd, String requestId) {
        return quotaOp(QuotaOpRequest.add(projectCode, usd, requestId));
    }

    /** 扣减项目池 USD → 返回操作后余额 USD。 */
    default BigDecimal subtractUsd(String projectCode, BigDecimal usd, String requestId) {
        return quotaOp(QuotaOpRequest.subtract(projectCode, usd, requestId));
    }

    /** 预扣项目池（freeze）→ 返回操作后余额 USD。 */
    default BigDecimal freezeUsd(String projectCode, BigDecimal usd, String requestId) {
        return quotaOp(QuotaOpRequest.freeze(projectCode, usd, requestId));
    }

    /** 充值项目内工作区钱包（B2C 散户充值）→ 返回操作后钱包余额 USD。 */
    default BigDecimal addUserUsd(String projectCode, String externalWorkspaceId, BigDecimal usd, String requestId) {
        return quotaOp(QuotaOpRequest.addUser(projectCode, externalWorkspaceId, usd, requestId));
    }

    /** 扣减项目内工作区钱包 → 返回操作后钱包余额 USD。 */
    default BigDecimal subtractUserUsd(String projectCode, String externalWorkspaceId, BigDecimal usd, String requestId) {
        return quotaOp(QuotaOpRequest.subtractUser(projectCode, externalWorkspaceId, usd, requestId));
    }

    /** 查询项目池当前余额 USD（运行时真理源·Redis；不限额项目返回 null）。 */
    BigDecimal queryBalance(String projectCode);

    /** 查询项目内工作区钱包余额 USD（不限额用户返回 null）。 */
    BigDecimal queryUserBalance(String projectCode, String externalWorkspaceId);

}
