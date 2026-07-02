package com.nexusai.provider.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AWS Bedrock Provider（档③·SigV4 签名）：bedrock-runtime InvokeModel。
 * <ul>
 *   <li>凭证格式：{@code accessKeyId:secretAccessKey}（AES 解密后明文）；</li>
 *   <li>region：header_override 的 {@code region}，缺省 us-east-1；</li>
 *   <li>URL：{@code https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/invoke}（流式 invoke-with-response-stream）；</li>
 *   <li>body：Bedrock 原生体（Claude on Bedrock = anthropic_version + messages），由 Canonical/模板前置产出。</li>
 * </ul>
 * apiFormat=ANTHROPIC（Bedrock Claude 体接近 Anthropic）。<b>无 AWS 凭证不在本机活测</b>，签名按 SigV4 规范实现。
 *
 * @author nexusai
 */
public class BedrockProvider implements Provider {

    private static final Set<String> KEYS = Set.of("bedrock", "aws-bedrock");

    /** Bedrock 上 Claude 模型的 anthropic_version（InvokeModel 必填·缺失即 400）。 */
    private static final String BEDROCK_ANTHROPIC_VERSION = "bedrock-2023-05-31";

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
        String cred = ctx.getUpstreamApiKey() == null ? "" : ctx.getUpstreamApiKey();
        int i = cred.indexOf(':');
        if (i <= 0) {
            throw new IllegalArgumentException("Bedrock 凭证须为 accessKeyId:secretAccessKey");
        }
        String accessKey = cred.substring(0, i);
        String secretKey = cred.substring(i + 1);

        // 🔴 Bedrock 流式返回 application/vnd.amazon.eventstream 二进制帧（非 SSE data: 文本），网关只解析 SSE，
        // 解码缺失会导致 usage 恒 0（系统性少计费）+ 二进制帧直吐客户端。AWS event-stream 解码器落地前显式拒绝，
        // 避免静默双坏（计费 + 输出）。终态退款由编排器故障转移兜底。
        if (ctx.isStream()) {
            throw new IllegalArgumentException("Bedrock 暂不支持流式：invoke-with-response-stream 为二进制 "
                    + "event-stream，网关 SSE 解析器无法解码（待 AWS event-stream 解码器落地）");
        }
        Map<String, String> override = ctx.getHeaderOverride();
        String region = override != null && override.containsKey("region") ? override.get("region") : "us-east-1";
        String op = "invoke";
        String host = "bedrock-runtime." + region + ".amazonaws.com";
        String uri = "/model/" + ctx.getTargetModel() + "/" + op;
        String url = "https://" + host + uri;
        byte[] body = toBedrockBody(ctx.getRawBody());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.putAll(AwsV4Signer.sign(accessKey, secretKey, region, "bedrock", host, uri, body));
        if (override != null) {
            override.forEach((k, v) -> {
                if (!"region".equalsIgnoreCase(k)) {
                    headers.put(k, v);
                }
            });
        }
        return new UpstreamRequest("POST", url, headers, body);
    }

    /**
     * 出站体规整为 Bedrock InvokeModel 严格 schema：剥离 Canonical/Anthropic 转换器产出的顶层 {@code model}
     * （Bedrock 由 URL path 定模型）与 {@code stream}（invoke 为非流式），并注入 Bedrock 上 Claude 必填的
     * {@code anthropic_version}（缺失即 400·已显式声明则保留）。体非 JSON 对象（解析失败/非对象）则原样返回不改写。
     */
    private static byte[] toBedrockBody(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return rawBody;
        }
        JsonNode root;
        try {
            root = JsonUtils.readTree(rawBody);
        } catch (RuntimeException e) {
            return rawBody;   // 非 JSON 体：原样透传，不改写
        }
        if (!(root instanceof ObjectNode obj)) {
            return rawBody;
        }
        obj.remove("model");
        obj.remove("stream");
        if (!obj.has("anthropic_version")) {
            obj.put("anthropic_version", BEDROCK_ANTHROPIC_VERSION);
        }
        return JsonUtils.toJson(obj).getBytes(StandardCharsets.UTF_8);
    }
}
