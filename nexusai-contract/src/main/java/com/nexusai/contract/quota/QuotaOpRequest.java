package com.nexusai.contract.quota;

import java.math.BigDecimal;

/**
 * QuotaOperation 请求体（三层身份链·两种操作粒度·2026-06-13 credits→USD）。
 * <p>网关与业务面共享的单一真理源：server 反应堆（relay）与 client SDK 共用本记录，禁止再各自复制。
 * {@code externalWorkspaceId} 空 = 操作<b>项目额度池</b>（B2B/分销商进货）；非空 = 操作该项目内工作区的
 * <b>钱包</b>（B2C 散户充值/扣减·用户须已存在——经 Key 同步开 Key 或 /admin/workspaces 创建）。
 * {@code add/subtract} 以网关运行时余额为基准调整 DB 基线并失效令重播种；{@code freeze} 仅项目池。
 *
 * @param projectCode         项目稳定标识（nai_project.project_code）
 * @param externalWorkspaceId 业务面用户标识（可空）
 * @param op                  操作：{@code add}（充值）/ {@code subtract}（扣减）/ {@code freeze}（预扣·仅项目池）
 * @param usd                 变动 USD 金额（正数·BigDecimal 精确十进制·禁浮点近似）
 * @param requestId           业务请求 ID（幂等去重·可空）
 * @author nexusai
 */
public record QuotaOpRequest(String projectCode, String externalWorkspaceId, String op, BigDecimal usd, String requestId) {

    // ===== 项目池粒度（B2B 应用额度 / 分销商进货） =====

    public static QuotaOpRequest add(String projectCode, BigDecimal usd, String requestId) {
        return new QuotaOpRequest(projectCode, null, "add", usd, requestId);
    }

    public static QuotaOpRequest subtract(String projectCode, BigDecimal usd, String requestId) {
        return new QuotaOpRequest(projectCode, null, "subtract", usd, requestId);
    }

    public static QuotaOpRequest freeze(String projectCode, BigDecimal usd, String requestId) {
        return new QuotaOpRequest(projectCode, null, "freeze", usd, requestId);
    }

    // ===== 工作区钱包粒度（B2C 散户充值/扣减） =====

    public static QuotaOpRequest addUser(String projectCode, String externalWorkspaceId, BigDecimal usd, String requestId) {
        return new QuotaOpRequest(projectCode, externalWorkspaceId, "add", usd, requestId);
    }

    public static QuotaOpRequest subtractUser(String projectCode, String externalWorkspaceId, BigDecimal usd, String requestId) {
        return new QuotaOpRequest(projectCode, externalWorkspaceId, "subtract", usd, requestId);
    }
}
