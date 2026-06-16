package com.nexusai.protocol.canonical;

import java.util.Map;

/**
 * Canonical 聊天响应（中枢中立）。MVP 文本 + usage + finishReason；其余入 extensions。
 *
 * @author nexusai
 */
public record CanonicalChatResponse(
        String id,
        String model,
        String role,
        String content,
        String finishReason,
        int promptTokens,
        int completionTokens,
        Map<String, Object> extensions) {
}
