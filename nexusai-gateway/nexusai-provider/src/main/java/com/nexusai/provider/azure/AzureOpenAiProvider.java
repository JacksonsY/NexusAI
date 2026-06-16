package com.nexusai.provider.azure;

import com.nexusai.common.enums.ApiFormat;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Azure OpenAI Provider（档③信封）：OpenAI 兼容体，但 URL 为 deployment 形态、鉴权头为 {@code api-key}（非 Bearer）。
 * URL：{@code {baseUrl}/openai/deployments/{model}/{op}?api-version=...}（model 即 Azure deployment 名）。
 * api-version 经 header_override 的 {@code api-version} 覆盖，缺省用内置默认。
 *
 * @author nexusai
 */
public class AzureOpenAiProvider implements Provider {

    private static final Set<String> KEYS = Set.of("azure", "azure-openai");
    private static final String DEFAULT_API_VERSION = "2024-10-21";

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
        // /v1/chat/completions → chat/completions
        String op = ctx.getPath().replaceFirst("^/?v1/", "").replaceAll("^/+", "");
        Map<String, String> override = ctx.getHeaderOverride();
        String apiVersion = override != null && override.containsKey("api-version")
                ? override.get("api-version") : DEFAULT_API_VERSION;
        String url = base + "/openai/deployments/" + ctx.getTargetModel() + "/" + op + "?api-version=" + apiVersion;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("api-key", ctx.getUpstreamApiKey());
        if (override != null) {
            override.forEach((k, v) -> {
                if (!"api-version".equalsIgnoreCase(k)) {
                    headers.put(k, v);
                }
            });
        }
        return new UpstreamRequest("POST", url, headers, ctx.getRawBody());
    }
}
