package com.nexusai.provider.openai;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容透传 Provider（MVP 主路·档①+档②）：换鉴权头 + 拼上游 URL，请求体透传
 * （档②的 model_mapping/param_override 由 router/relay 在 ctx 前置处理；这里只做最小适配）。
 *
 * @author nexusai
 */
public class OpenAiCompatibleProvider implements Provider {

    private static final Set<String> KEYS = Set.of("openai", "openai-compatible", "oneapi", "newapi");

    @Override
    public ApiFormat apiFormat() {
        return ApiFormat.OPENAI;
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
        headers.put(GatewayConstants.AUTH_HEADER, GatewayConstants.BEARER_PREFIX + ctx.getUpstreamApiKey());
        if (ctx.getHeaderOverride() != null) {
            headers.putAll(ctx.getHeaderOverride());
        }
        return new UpstreamRequest("POST", url, headers, ctx.getRawBody());
    }
}
