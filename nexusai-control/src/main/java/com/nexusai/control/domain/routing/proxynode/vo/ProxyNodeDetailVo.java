package com.nexusai.control.domain.routing.proxynode.vo;

/**
 * 出口代理节点详情（编辑回显·admin 会话内附明文密码，与渠道密钥编辑同纪律）。
 * <p>对齐前端 {@code NexusAI.ProxyNodeDetail}（= ProxyNode + password）：字段顺序与 {@link ProxyNodeVo}
 * 一致并在末尾追加 {@code password}（与原 Map「先 toView 再 put password」字节一致）。
 *
 * @author nexusai
 */
public record ProxyNodeDetailVo(
        String id,
        String name,
        String proxyUrl,
        String username,
        boolean hasPassword,
        String region,
        String status,
        Long totalRequests,
        Long failedRequests,
        Double avgLatencyMs,
        String lastProbeAt,
        String lastOkAt,
        String createDt,
        String password) {
}
