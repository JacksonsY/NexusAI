package com.nexusai.relay.controller.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单请求计量明细外吐口径（{@code GET /v1/generation/{requestId}} 的 {@code data} 体）。
 * 仅含接入应用可见字段（channelId/keyId/costBasis 等网关内部信息不外泄）。
 * <p>组件名经 {@link JsonProperty} 固定为 snake_case，与历史 Map 键逐字一致（线序不可变）。
 *
 * @author nexusai
 */
public record GenerationDataVo(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("model") String model,
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("cached_tokens") Integer cachedTokens,
        @JsonProperty("cache_creation_tokens") Integer cacheCreationTokens,
        @JsonProperty("total_tokens") Integer totalTokens,
        @JsonProperty("cost") BigDecimal cost,
        @JsonProperty("status") Integer status,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
