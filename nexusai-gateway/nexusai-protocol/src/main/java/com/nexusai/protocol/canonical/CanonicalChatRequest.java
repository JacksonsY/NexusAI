package com.nexusai.protocol.canonical;

import java.util.List;
import java.util.Map;

/**
 * Canonical 聊天请求（中枢中立）：各入站格式 → 此 → 各出站格式，互转不经 OpenAI 当中枢（避免丢
 * Claude thinking/cache token、Gemini 多模态）。MVP 覆盖文本 chat 核心字段；其余入 extensions。
 *
 * @author nexusai
 */
public record CanonicalChatRequest(
        String model,
        List<CanonicalMessage> messages,
        String system,
        Integer maxTokens,
        Double temperature,
        Double topP,
        boolean stream,
        Map<String, Object> extensions) {
}
