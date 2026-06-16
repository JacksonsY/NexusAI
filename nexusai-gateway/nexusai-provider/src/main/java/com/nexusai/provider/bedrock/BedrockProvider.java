package com.nexusai.provider.bedrock;

import com.nexusai.common.enums.ApiFormat;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;

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
        byte[] body = ctx.getRawBody();

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
}
