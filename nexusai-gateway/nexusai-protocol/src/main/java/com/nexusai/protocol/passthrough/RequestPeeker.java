package com.nexusai.protocol.passthrough;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;

import java.nio.charset.StandardCharsets;

/**
 * 透传直采解析器：从原始请求体树取 model/stream，并对流式请求强制注入
 * {@code stream_options.include_usage=true}（防 token 旁路·计量生死线）。
 * <p>不做全量绑定——只读必需字段，原体仍按 byte 透传。
 *
 * @author nexusai
 */
public final class RequestPeeker {

    private RequestPeeker() {
    }

    /** Gemini 原生入站识别（模型在 URL path：/v1beta/models/{model}:{action}）。 */
    public static boolean isGeminiInbound(String path) {
        return path != null && path.contains("/v1beta/models/");
    }

    /**
     * Gemini 原生入站窥取：模型/动作从 <b>URL path</b> 取（体不含 model），stream 由 action 判定，
     * maxTokens 取 generationConfig.maxOutputTokens。不注入 OpenAI 专有 include_usage。
     */
    public static ChatRequestPeek peekGemini(String path, JsonNode body) {
        String tail = path.substring(path.indexOf("/models/") + "/models/".length());
        int q = tail.indexOf('?');
        if (q >= 0) {
            tail = tail.substring(0, q);
        }
        String model;
        String action;
        int colon = tail.lastIndexOf(':');
        if (colon >= 0) {
            model = tail.substring(0, colon);
            action = tail.substring(colon + 1);
        } else {
            model = tail;
            action = "generateContent";
        }
        if (model.isBlank()) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "Gemini 入站路径缺少模型名");
        }
        boolean stream = action.startsWith("stream");
        int maxTokens = body == null ? 0 : body.path("generationConfig").path("maxOutputTokens").asInt(0);
        return new ChatRequestPeek(model, stream, false, Math.max(0, maxTokens));
    }

    /**
     * multipart/form-data 入站窥取（音频转写/翻译、图像编辑·文件上传）：从表单 {@code model} 文本字段取模型，
     * 非流式。原始 multipart 字节不做任何改写（含 boundary 原样透传上游）。
     */
    public static ChatRequestPeek peekMultipart(byte[] body) {
        String model = extractMultipartField(body, "model");
        if (model == null || model.isBlank()) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "multipart 请求缺少 model 字段");
        }
        return new ChatRequestPeek(model.trim(), false, false, 0);
    }

    /**
     * 从 multipart 字节里抽取某个<b>表单字段</b>值（防上传文件内容伪造 {@code name="model"} 冒名低价模型）：
     * 依 body 首行分隔符还原 boundary（RFC 2046：HTTP multipart 体以 {@code --boundary} 开头，无需依赖调用方传入
     * Content-Type 头），按 boundary 切分 part，<b>仅在 part 的 header 段（Content-Disposition）精确匹配
     * {@code name="field"}</b> 才取其 value——不裸扫描整体字节，故文件正文里藏的 {@code name="model"} 无法冒名。
     * 无法还原 boundary 或无匹配字段则回退返回 null（由调用方按 BAD_REQUEST 拒绝）。
     */
    private static String extractMultipartField(byte[] body, String field) {
        if (body == null || body.length == 0) {
            return null;
        }
        // ISO-8859-1 保字节：定位分隔符/头部（纯 ASCII 结构字符），字段值取出后再按 UTF-8 还原
        String s = new String(body, StandardCharsets.ISO_8859_1);
        String boundary = leadingBoundary(s);
        if (boundary == null) {
            return null;
        }
        String delimiter = "--" + boundary;
        int pos = 0;
        while (true) {
            int partStart = s.indexOf(delimiter, pos);
            if (partStart < 0) {
                return null;
            }
            partStart += delimiter.length();
            if (s.startsWith("--", partStart)) {
                return null;   // 收尾分隔符 "--boundary--"：已无更多 part
            }
            int headerEnd = s.indexOf("\r\n\r\n", partStart);
            if (headerEnd < 0) {
                return null;
            }
            String headers = s.substring(partStart, headerEnd);
            int valEnd = s.indexOf("\r\n" + delimiter, headerEnd + 4);
            if (valEnd < 0) {
                valEnd = s.length();
            }
            if (field.equals(dispositionName(headers))) {
                String value = s.substring(headerEnd + 4, valEnd);
                return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            }
            pos = valEnd;
        }
    }

    /** multipart 体首行即分隔符 {@code --boundary}（RFC 2046），据此还原 boundary（免依赖 Content-Type 头·本模块拿不到）。 */
    private static String leadingBoundary(String s) {
        if (!s.startsWith("--")) {
            return null;
        }
        int eol = s.indexOf("\r\n");
        if (eol <= 2) {
            return null;
        }
        return s.substring(2, eol).stripTrailing();
    }

    /** 从 part 的 header 段取 Content-Disposition 的 {@code name} 参数值（精确取字段名·防 filename 内混入 name= 伪造）。 */
    private static String dispositionName(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-disposition:")) {
                return headerParam(line, "name");
            }
        }
        return null;
    }

    /** 从 header 行取 {@code param="value"} 的 value（不含引号）；无则 null。 */
    private static String headerParam(String line, String param) {
        String needle = param + "=\"";
        int i = line.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i += needle.length();
        int end = line.indexOf('"', i);
        return end < 0 ? null : line.substring(i, end);
    }

    public static ChatRequestPeek peek(JsonNode body) {
        if (body == null || !body.hasNonNull("model")) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "缺少 model 字段");
        }
        String model = body.get("model").asText();
        boolean stream = body.path("stream").asBoolean(false);
        boolean includeUsage = stream
                && body.path(GatewayConstants.STREAM_OPTIONS).path(GatewayConstants.INCLUDE_USAGE).asBoolean(false);
        // 最大补全 token（freeze 预扣上界）：OpenAI max_tokens / max_completion_tokens、Anthropic max_tokens、
        // Gemini generationConfig.maxOutputTokens 自适应；缺省 0=未声明（freeze 用模型保守默认）。
        int maxTokens = body.path("max_tokens").asInt(
                body.path("max_completion_tokens").asInt(
                        body.path("generationConfig").path("maxOutputTokens").asInt(0)));
        return new ChatRequestPeek(model, stream, includeUsage, Math.max(0, maxTokens));
    }

    /** enforce 注入结果：可能改写后的体 + 窥取结果（一次解析完成·供调用方免二次 readTree）。 */
    public record PeekedBody(byte[] body, ChatRequestPeek peek) {
    }

    /**
     * 对流式请求强制注入 {@code stream_options.include_usage=true}，<b>一次解析</b>同时返回
     * 可能改写后的请求体字节与窥取结果。非流式或已带 include_usage 时原体返回。
     * <p>替代原「enforceIncludeUsage 解析后丢弃树 → 调用方再 readTree 一次」的每请求双重 JSON 解析。
     */
    public static PeekedBody enforceAndPeek(byte[] rawBody) {
        JsonNode root = JsonUtils.readTree(rawBody);
        ChatRequestPeek peek = peek(root);
        if (!peek.stream() || peek.includeUsage()) {
            return new PeekedBody(rawBody, peek);
        }
        ObjectNode obj = (ObjectNode) root;
        ObjectNode streamOptions = obj.has(GatewayConstants.STREAM_OPTIONS)
                ? (ObjectNode) obj.get(GatewayConstants.STREAM_OPTIONS)
                : obj.putObject(GatewayConstants.STREAM_OPTIONS);
        streamOptions.put(GatewayConstants.INCLUDE_USAGE, true);
        byte[] rewritten = JsonUtils.toJson(obj).getBytes(StandardCharsets.UTF_8);
        // 改写后 includeUsage 已为 true（其余字段不变），直接构造避免再解析
        return new PeekedBody(rewritten, new ChatRequestPeek(peek.model(), peek.stream(), true, peek.maxTokens()));
    }
}
