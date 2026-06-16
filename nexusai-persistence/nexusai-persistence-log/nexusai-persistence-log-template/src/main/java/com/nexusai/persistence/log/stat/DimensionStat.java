package com.nexusai.persistence.log.stat;

/**
 * 维度聚合（渠道/模型/上游凭证通用·Dashboard 维度分析·去钱化）：分组键 + 请求量/成功数 + token 三态。
 * <p>{@code key} 一律为字符串（{@code channel_id}/{@code key_id} 用 id 串、{@code model} 用模型名），
 * 展示名由上层（前端已持 channels/keys 列表）映射，存储层不查名解耦。
 *
 * @author nexusai
 */
public record DimensionStat(String key, long total, long success,
                            long inputTokens, long outputTokens, long cachedTokens) {
}
