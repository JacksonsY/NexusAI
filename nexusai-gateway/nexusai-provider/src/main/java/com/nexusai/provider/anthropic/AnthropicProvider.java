package com.nexusai.provider.anthropic;

import com.nexusai.common.enums.ApiFormat;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic 原生协议 Provider（特殊鉴权信封档）：信封适配——换 Anthropic 鉴权头（{@code x-api-key} +
 * {@code anthropic-version}，非 Bearer）+ 拼 /v1/messages 上游 URL。
 * <p>MVP=原生透传（客户端直发 Anthropic 体到 /v1/messages）；OpenAI↔Anthropic <b>请求/响应体互转</b>由 P4 Canonical
 * 在 relay 前后置 Transformer 处理，本类只管协议信封。
 *
 * @author nexusai
 */
public class AnthropicProvider implements Provider {

    private static final Set<String> KEYS = Set.of("anthropic", "claude");
    private static final String DEFAULT_VERSION = "2023-06-01";

    @Override
    public ApiFormat apiFormat() {
        return ApiFormat.ANTHROPIC;
    }

    @Override
    public boolean supports(String providerKey) {
        return providerKey != null && KEYS.contains(providerKey.trim().toLowerCase());
    }

    @Override
    public UpstreamRequest adapt(UpstreamContext ctx) {
        String base = ctx.getBaseUrl().replaceAll("/+$", "");
        String path = ctx.getPath().startsWith("/") ? ctx.getPath() : "/" + ctx.getPath();
        String url = base + path;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("x-api-key", ctx.getUpstreamApiKey());
        headers.put("anthropic-version", DEFAULT_VERSION);
        if (ctx.getHeaderOverride() != null) {
            headers.putAll(ctx.getHeaderOverride());   // 渠道可覆盖 anthropic-version 等
        }
        return new UpstreamRequest("POST", url, headers, ctx.getRawBody());
    }
}
