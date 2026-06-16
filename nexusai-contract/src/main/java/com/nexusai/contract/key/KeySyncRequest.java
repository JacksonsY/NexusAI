package com.nexusai.contract.key;

import java.math.BigDecimal;

/**
 * KeySync 请求体（业务面 签发/改 sk- Key → 同步网关 nai_token 只读快照）。
 * <p>网关与业务面共享的单一真理源：server 反应堆（relay）与 client SDK 共用本记录，禁止再各自复制。
 * {@code keyHash} 为 sk- 明文的 SHA-256（网关只存 hash·绝不传明文）。
 * <p>项目制：额度池在项目（经 QuotaOperation 按 projectCode 维护），本请求不再承载额度。
 *
 * @param keyHash             sk- 明文的 SHA-256（业务面计算·网关按此匹配/Upsert）
 * @param projectCode         归属项目（必填·nai_project.project_code·额度池/策略/用量归集按项目）
 * @param externalWorkspaceId 业务面用户标识（可空·非空=Key 挂项目内工作区扣其钱包[B2C]·不存在自动建；空=B2B 直挂项目池）
 * @param groupCode           分组/套餐码（路由选渠道用）
 * @param modelLimits         允许模型白名单 JSON 数组（空=不限）
 * @param allowIps            允许来源 IP（空=不限）
 * @param quotaUsd            Key 子限额 USD（可选·防单 Key 泄漏打爆项目池·NULL/≤0=不设仅受项目池）
 * @param rpmLimit            每分钟请求数上限（&lt;=0 不限）
 * @param tpmLimit            每分钟 token 数上限（&lt;=0 不限）
 * @param byokCredential      BYOK 上游凭证密文（AES·非空则替代渠道池 Key 调上游）
 * @param expiredTime         过期时间戳（毫秒·null 不过期）
 * @param status              状态（1 启用 / 0 停用·null 默认启用）
 * @author nexusai
 */
public record KeySyncRequest(
        String keyHash,
        String projectCode,
        String externalWorkspaceId,
        String groupCode,
        String modelLimits,
        String allowIps,
        BigDecimal quotaUsd,
        Integer rpmLimit,
        Integer tpmLimit,
        String byokCredential,
        Long expiredTime,
        Integer status) {
}
