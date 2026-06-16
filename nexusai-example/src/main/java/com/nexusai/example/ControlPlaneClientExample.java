package com.nexusai.example;

import com.nexusai.client.NexusAiAdminClient;
import com.nexusai.contract.key.KeySyncRequest;

import java.math.BigDecimal;

/**
 * 业务面以「控制面 Client」身份接入网关的最小示例（独立 Server + 业务面 纯控制面 Client 范式）。
 * <p><b>本类编译即验证 client-starter 是可独立消费的瘦 SDK</b>——nexusai-example 只依赖 nexusai-client-starter
 * （其内部仅挂 nexusai-contract·连 nexusai-common 都不依赖），物理上无法把网关反应堆拖进消费方进程。
 * <p>接入只需三步：①引 {@code nexusai-client-starter}；②配 {@code nexusai.client.{project-code,token,server.*}}
 * （token 即某个 {@code nai_project.token}·M2M·走环境变量绝不硬编码）；③注入 {@link NexusAiAdminClient} 调控制面接口。
 * UsageEvent 用量回传不在此 client——由网关经 Redis Stream 反向回传业务面。
 *
 * @author nexusai
 */
public class ControlPlaneClientExample {

    private final NexusAiAdminClient admin;

    public ControlPlaneClientExample(NexusAiAdminClient admin) {
        this.admin = admin;
    }

    /** KeySync：签发/同步一个 sk- Key 到网关（业务面算好 sk- 的 SHA-256·明文绝不出业务面）。 */
    public Long provisionKey(String keyHash, String projectCode) {
        KeySyncRequest req = new KeySyncRequest(
                keyHash, projectCode,
                null,                 // externalWorkspaceId：空=B2B 直挂项目池
                "default",            // groupCode
                null,                 // modelLimits：空=不限
                null,                 // allowIps：空=不限
                null,                 // quotaUsd：不设 Key 子限额
                0, 0,                 // rpm/tpm：<=0 不限
                null,                 // byokCredential
                null,                 // expiredTime：不过期
                1);                   // status：启用
        return admin.syncKey(req);
    }

    /** QuotaOperation：给项目池充值 USD，返回操作后余额。 */
    public BigDecimal recharge(String projectCode, BigDecimal usd, String bizRequestId) {
        return admin.addUsd(projectCode, usd, bizRequestId);
    }

    /** QuotaOperation：查询项目池当前余额 USD（不限额项目返回 null）。 */
    public BigDecimal balance(String projectCode) {
        return admin.queryBalance(projectCode);
    }

}
