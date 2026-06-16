package com.nexusai.contract.quota;

/**
 * 额度余额视图（QuotaOperation 查询响应·网关 server 与 client SDK 共享的单一真理源）。
 * <p>消灭此前 control 端 {@code QuotaOperationController.balance} 返回裸 {@code Map<String,Object>}、
 * client 端 {@code unwrapBalance} 各按字符串键解包的双份漂移（键名漂移即静默错配）。
 * <p>纯 JDK record（contract 模块禁引 Jackson/Spring）：序列化按 component 名产出 JSON 键，与原 Map 键一致。
 *
 * @param projectCode         项目稳定标识
 * @param externalWorkspaceId 业务面工作区标识（项目池粒度为 null）
 * @param workspaceId         网关侧工作区主键（项目池粒度为 null）
 * @param unlimited           是否不限额（true 时 {@code balance} 为 null·哨兵值不外泄）
 * @param balance             余额（USD 字符串·{@code UsdAmount.format} 同口径；unlimited 时 null）
 * @author nexusai
 */
public record BalanceView(String projectCode, String externalWorkspaceId, Long workspaceId,
                          boolean unlimited, String balance) {

    /** 项目池粒度余额（无工作区维度）。 */
    public static BalanceView ofProject(String projectCode, boolean unlimited, String balance) {
        return new BalanceView(projectCode, null, null, unlimited, balance);
    }

    /** 工作区钱包粒度余额。 */
    public static BalanceView ofWorkspace(String projectCode, String externalWorkspaceId, Long workspaceId,
                                          boolean unlimited, String balance) {
        return new BalanceView(projectCode, externalWorkspaceId, workspaceId, unlimited, balance);
    }
}
