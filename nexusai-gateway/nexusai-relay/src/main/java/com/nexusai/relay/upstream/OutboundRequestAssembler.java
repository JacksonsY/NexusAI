package com.nexusai.relay.upstream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.runtime.ModuleToggles;
import com.nexusai.runtime.RuntimeSettings;
import com.nexusai.protocol.transform.CanonicalConverter;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.UpstreamContext;
import com.nexusai.provider.spi.UpstreamRequest;
import com.nexusai.relay.RelayFormats;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.guard.PiiRedactor;
import com.nexusai.router.RouteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出站请求装配（选号后的"体改写链 + 凭证 + Provider 适配"）：
 * ① 渠道 model_mapping 改写 → ② Canonical 跨协议请求互转（按需）→ ③ PII 出站脱敏 →
 * ④ Anthropic cache_control 自动注入（按需）→ ⑤ 渠道 paramOverride 注入 → BYOK/渠道凭证解密 → Provider.adapt。
 * <p>不操作路由生命周期（槽释放/route 清理归编排器）；流式跨协议不支持时抛
 * {@link SkipAndRetryException}（排渠道改选），其余异常原样上抛由编排器按"排 Key 换号"包装。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class OutboundRequestAssembler {

    private final CanonicalConverter canonicalConverter;
    private final CredentialCipher cipher;
    private final PiiRedactor piiRedactor;
    private final AnthropicCacheControlInjector cacheControlInjector;
    private final ModuleToggles moduleToggles;
    private final RuntimeSettings runtimeSettings;

    /** 装配上游请求（体改写链 + 凭证解密 + Provider 适配）。 */
    public UpstreamRequest assemble(RelayContext ctx, RouteResult route, Provider provider) {
        // multipart 音频转写/翻译·图像编辑：原体二进制透传（含 boundary），禁 JSON 改写链（model 改写/参数覆盖/Canonical/PII 均假定 JSON 体）
        if (ctx.isMultipart()) {
            return assembleMultipart(ctx, route, provider);
        }
        byte[] upstreamBody = rewriteModelIfNeeded(ctx.getBody(), ctx.getPeek().model(), route.targetModel());
        // P4 Canonical：渠道 setting.canonical=true 且 入站格式≠上游格式 → 跨协议互转（非流式）
        upstreamBody = maybeConvertRequest(ctx, route, provider, upstreamBody);
        // P13 敏感信息保护（chat_pii_redaction 模块·出站前正则脱敏·上游看不到原文）
        upstreamBody = piiRedactor.redact(upstreamBody);
        // P12 Anthropic prompt caching：发往 anthropic 协议渠道自动规划 cache_control 断点
        if ("anthropic".equalsIgnoreCase(route.channel().getProtocol())
                && moduleToggles.enabled("anthropic_auto_cache", true)) {
            upstreamBody = cacheControlInjector.inject(upstreamBody);
        }
        // P6 渠道参数覆盖：channel.paramOverride 的参数注入请求体
        upstreamBody = applyParamOverride(upstreamBody, route.channel().getParamOverride());

        // BYOK：token 自带上游凭证则替代渠道池 Key 调上游（仍走选号/计量）
        String byok = ctx.getToken().getByokCredential();
        String upstreamKey = (byok != null && !byok.isBlank())
                ? cipher.decrypt(byok) : cipher.decrypt(route.key().getCredential());
        UpstreamContext uc = UpstreamContext.builder()
                .baseUrl(route.channel().getBaseUrl())
                .upstreamApiKey(upstreamKey)
                .targetModel(route.targetModel())
                .path(canonicalPath(ctx))
                .rawBody(upstreamBody)
                .stream(ctx.getPeek().stream())
                .headerOverride(parseHeaderOverride(route.channel().getHeaderOverride()))
                .build();
        return provider.adapt(uc);
    }

    /** multipart 装配：原始 multipart 字节透传（含 boundary），入站 Content-Type 原样转上游（覆盖 Provider 默认 JSON）。 */
    private UpstreamRequest assembleMultipart(RelayContext ctx, RouteResult route, Provider provider) {
        String byok = ctx.getToken().getByokCredential();
        String upstreamKey = (byok != null && !byok.isBlank())
                ? cipher.decrypt(byok) : cipher.decrypt(route.key().getCredential());
        Map<String, String> headers = new LinkedHashMap<>(parseHeaderOverride(route.channel().getHeaderOverride()));
        if (ctx.getContentType() != null) {
            headers.put("Content-Type", ctx.getContentType());   // 含 boundary·上游按此切分文件/字段
        }
        UpstreamContext uc = UpstreamContext.builder()
                .baseUrl(route.channel().getBaseUrl())
                .upstreamApiKey(upstreamKey)
                .targetModel(route.targetModel())
                .path(ctx.getPath())
                .rawBody(ctx.getBody())
                .stream(false)
                .headerOverride(headers)
                .build();
        return provider.adapt(uc);
    }

    /** Canonical 请求互转（仅在渠道启用 + 格式不同 + 支持时；流式跨协议暂不支持→显式拒绝排渠道）。 */
    private byte[] maybeConvertRequest(RelayContext ctx, RouteResult route, Provider provider, byte[] body) {
        // 渠道级 setting.canonical 或 系统设置 enable_format_conversion 全局开启（基础配置 section）
        if (!canonicalEnabled(route.channel().getSetting())
                && !runtimeSettings.getBool("enable_format_conversion", false)) {
            return body;
        }
        ApiFormat in = RelayFormats.inboundFormat(ctx.getPath());
        ApiFormat up = provider.apiFormat();
        if (in == up || !canonicalConverter.supports(in, up)) {
            return body;
        }
        if (ctx.getPeek().stream() && in != ApiFormat.OPENAI) {
            // 流式跨协议仅支持「客户端 OpenAI ← 上游(Anthropic/Gemini)」(reframer 输出 OpenAI SSE)；其余客户端格式改选渠道
            throw new SkipAndRetryException(route.channel().getId(), null, GatewayErrorCode.BAD_REQUEST,
                    "非 OpenAI 客户端的流式跨协议转码未支持");
        }
        ctx.setCanonicalConvert(true);
        ctx.setInboundFormat(in);
        ctx.setUpstreamFormat(up);
        return canonicalConverter.convertRequest(body, in, up);
    }

    private static byte[] rewriteModelIfNeeded(byte[] body, String sourceModel, String targetModel) {
        if (targetModel == null || targetModel.equals(sourceModel)) {
            return body;
        }
        ObjectNode root = (ObjectNode) JsonUtils.readTree(body);
        root.put("model", targetModel);
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 渠道参数覆盖（代码式适配·有限受控配置）：把 {@code channel.paramOverride}（JSON 对象·由渠道页直接编辑）
     * 合并进请求体顶层。
     * 保护 {@code model/stream/stream_options} 路由/流式不可覆盖字段；解析失败原样返回（不影响主链）。
     */
    private static byte[] applyParamOverride(byte[] body, String paramOverrideJson) {
        if (paramOverrideJson == null || paramOverrideJson.isBlank()) {
            return body;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode ov = JsonUtils.readTree(paramOverrideJson);
            if (ov == null || !ov.isObject() || ov.isEmpty()) {
                return body;
            }
            ObjectNode root = (ObjectNode) JsonUtils.readTree(body);
            ov.fields().forEachRemaining(e -> {
                String k = e.getKey();
                // 保护路由/流式不可覆盖字段：model/stream/stream_options 一律不可被覆盖
                if (!"model".equals(k) && !"stream".equals(k) && !"stream_options".equals(k)) {
                    root.set(k, e.getValue());
                }
            });
            return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return body;
        }
    }

    private static boolean canonicalEnabled(String settingJson) {
        if (settingJson == null || settingJson.isBlank()) {
            return false;
        }
        try {
            return JsonUtils.readTree(settingJson).path("canonical").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Canonical 互转时上游路径映射到目标格式端点（OpenAI→Anthropic 用 /v1/messages；OpenAI→Gemini 用 generateContent）。 */
    private static String canonicalPath(RelayContext ctx) {
        if (ctx.isCanonicalConvert()) {
            return switch (ctx.getUpstreamFormat()) {
                case ANTHROPIC -> "/v1/messages";
                case OPENAI -> "/v1/chat/completions";
                // Gemini「模型在 URL 路径」：/v1beta/models/{model}:generateContent（流式则 streamGenerateContent）
                case GEMINI -> {
                    String model = ctx.getRoute() != null ? ctx.getRoute().targetModel() : ctx.getPeek().model();
                    String method = Boolean.TRUE.equals(ctx.getPeek().stream()) ? "streamGenerateContent" : "generateContent";
                    yield "/v1beta/models/" + model + ":" + method;
                }
                default -> ctx.getPath();   // OpenAI 兼容族（含 DeepSeek/通义千问等）：原 path
            };
        }
        // Gemini 原生入站透传（in==up==GEMINI·不转换）：模型在 URL path，若 model_mapping 改写了模型则同步改写 path
        String path = ctx.getPath();
        if (path != null && path.contains("/v1beta/models/") && ctx.getRoute() != null) {
            String target = ctx.getRoute().targetModel();
            String source = ctx.getPeek().model();
            if (target != null && !target.equals(source)) {
                return path.replace("/models/" + source, "/models/" + target);
            }
        }
        return path;
    }

    private static Map<String, String> parseHeaderOverride(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JsonUtils.parse(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
