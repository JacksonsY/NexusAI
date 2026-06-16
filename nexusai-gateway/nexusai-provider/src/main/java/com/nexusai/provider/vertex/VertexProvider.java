package com.nexusai.provider.vertex;

import com.nexusai.common.enums.ApiFormat;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GCP Vertex AI Provider（档③·SA OAuth2）：Gemini generateContent on Vertex。
 * <ul>
 *   <li>凭证：服务账号 JSON（AES 解密后明文）→ {@link GoogleSaTokenSource} 换 access token（带缓存）；</li>
 *   <li>project / region：header_override 的 {@code project}（必填）/ {@code region}（缺省 us-central1）；</li>
 *   <li>URL：{@code https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/publishers/google/models/{model}:generateContent}。</li>
 * </ul>
 * apiFormat=GEMINI（体即 Gemini generateContent，由 Canonical 产出）。<b>无 GCP 凭证不在本机活测</b>。
 *
 * @author nexusai
 */
public class VertexProvider implements Provider {

    private static final Set<String> KEYS = Set.of("vertex", "vertex-ai", "gcp");

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
        Map<String, String> override = ctx.getHeaderOverride();
        String project = override == null ? null : override.get("project");
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("Vertex 需 header_override.project");
        }
        String region = override != null && override.containsKey("region") ? override.get("region") : "us-central1";
        String op = ctx.isStream() ? "streamGenerateContent" : "generateContent";
        String token = GoogleSaTokenSource.accessToken(ctx.getUpstreamApiKey());

        String url = "https://" + region + "-aiplatform.googleapis.com/v1/projects/" + project
                + "/locations/" + region + "/publishers/google/models/" + ctx.getTargetModel() + ":" + op;
        // 流式必须带 alt=sse：否则 streamGenerateContent 返回 JSON 数组流而非 SSE data: 帧，
        // OpenAiSseConverter/UsageExtractor 仅解析 data: 行（URL 尚无 query·此处用 ?）。
        if (ctx.isStream()) {
            url = url + "?alt=sse";
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + token);
        if (override != null) {
            override.forEach((k, v) -> {
                if (!"project".equalsIgnoreCase(k) && !"region".equalsIgnoreCase(k)) {
                    headers.put(k, v);
                }
            });
        }
        return new UpstreamRequest("POST", url, headers, ctx.getRawBody());
    }
}
