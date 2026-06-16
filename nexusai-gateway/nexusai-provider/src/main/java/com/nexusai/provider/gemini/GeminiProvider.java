package com.nexusai.provider.gemini;

import com.nexusai.common.enums.ApiFormat;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gemini 原生协议 Provider（特殊鉴权信封档）：信封适配——API Key 走 {@code x-goog-api-key} 请求头
 * （generativelanguage 形态·密钥不落 URL 防日志泄露）+ 拼上游 URL。
 * <p>MVP=原生透传（信封）；OpenAI↔Gemini 体互转 + Gemini「模型在 URL 路径」的入站路由（/v1beta/models/{model}:xxx）
 * 由 P4 Canonical/入站适配处理，本类只管协议信封。
 *
 * @author nexusai
 */
public class GeminiProvider implements Provider {

    private static final Set<String> KEYS = Set.of("gemini", "google");

    @Override
    public ApiFormat apiFormat() {
        return ApiFormat.GEMINI;
    }

    @Override
    public boolean supports(String providerKey) {
        return providerKey != null && KEYS.contains(providerKey.trim().toLowerCase());
    }

    @Override
    public UpstreamRequest adapt(UpstreamContext ctx) {
        String base = ctx.getBaseUrl().replaceAll("/+$", "");
        String path = ctx.getPath().startsWith("/") ? ctx.getPath() : "/" + ctx.getPath();
        // 🔴 凭证不落 URL：原 ?key= 会把明文 Key 写进请求行，被出站代理/访问日志/wiretap 记录泄露（违背 AES
        // 落库与「明文永不外泄」纪律）。Gemini 支持 x-goog-api-key 头携带密钥，保持密钥仅在 header。
        String url = base + path;
        // 流式必须带 alt=sse：否则 streamGenerateContent 返回 JSON 数组流而非 SSE data: 帧，
        // OpenAiSseConverter/UsageExtractor 仅解析 data: 行。
        if (ctx.isStream()) {
            url = url + (url.contains("?") ? "&" : "?") + "alt=sse";
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("x-goog-api-key", ctx.getUpstreamApiKey());
        if (ctx.getHeaderOverride() != null) {
            headers.putAll(ctx.getHeaderOverride());
        }
        return new UpstreamRequest("POST", url, headers, ctx.getRawBody());
    }
}
