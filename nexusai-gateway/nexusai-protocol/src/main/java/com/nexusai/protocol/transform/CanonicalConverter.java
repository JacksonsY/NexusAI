package com.nexusai.protocol.transform;

import com.nexusai.common.enums.ApiFormat;

import java.util.EnumMap;
import java.util.Map;

/**
 * Canonical 互转中枢：按 (源格式→Canonical→目标格式) 转换聊天请求/响应，使「OpenAI 客户端 ↔ Anthropic 上游」
 * 等跨协议家族打通。MVP 内置 OpenAI/Anthropic 非流式；Gemini、流式 SSE 转码留后续。
 * <p>本类属纯净 protocol 模块（无 Spring 依赖），由 relay 的 {@code RelayConfig} 以 {@code @Bean} 注册。
 *
 * @author nexusai
 */
public class CanonicalConverter {

    private final Map<ApiFormat, ChatTransformer> transformers = new EnumMap<>(ApiFormat.class);

    public CanonicalConverter() {
        register(new OpenAiChatTransformer());
        register(new AnthropicChatTransformer());
        register(new GeminiChatTransformer());
    }

    private void register(ChatTransformer t) {
        transformers.put(t.format(), t);
    }

    public boolean supports(ApiFormat from, ApiFormat to) {
        return transformers.containsKey(from) && transformers.containsKey(to);
    }

    /** 入站请求体 from → to（同格式直返）。 */
    public byte[] convertRequest(byte[] body, ApiFormat from, ApiFormat to) {
        if (from == to) {
            return body;
        }
        return transformers.get(to).fromCanonicalRequest(transformers.get(from).toCanonicalRequest(body));
    }

    /** 上游响应体 upstream → client（同格式直返）。 */
    public byte[] convertResponse(byte[] body, ApiFormat upstream, ApiFormat client) {
        if (upstream == client) {
            return body;
        }
        return transformers.get(client).fromCanonicalResponse(transformers.get(upstream).toCanonicalResponse(body));
    }
}
