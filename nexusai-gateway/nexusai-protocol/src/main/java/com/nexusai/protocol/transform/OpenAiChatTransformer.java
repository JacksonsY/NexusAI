package com.nexusai.protocol.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.protocol.canonical.CanonicalChatRequest;
import com.nexusai.protocol.canonical.CanonicalChatResponse;
import com.nexusai.protocol.canonical.CanonicalContentPart;
import com.nexusai.protocol.canonical.CanonicalImage;
import com.nexusai.protocol.canonical.CanonicalMessage;
import com.nexusai.protocol.canonical.CanonicalToolCall;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat 互转器：OpenAI {@code /v1/chat/completions} 请求/响应 ↔ Canonical。
 * 含 tool/function-calling 映射：请求 tools[{type:function,function:{name,description,parameters}}]、
 * 响应 message.tool_calls[{id,function:{name,arguments(JSON字符串)}}] ↔ Canonical extensions(tools/toolCalls)。
 *
 * @author nexusai
 */
public class OpenAiChatTransformer implements ChatTransformer {

    /**
     * 跨协议透传请求参数（OpenAI 入站 → extensions → 各出站回放）：stop/tool_choice 计费/行为影响最大优先。
     * 顺序仅为可读·实际按 key 存取。OpenAI 出站逐字回放；Anthropic/Gemini 至少回放 stop（见各 fromCanonicalRequest）。
     */
    static final List<String> PASS_THROUGH_PARAMS = List.of(
            "stop", "tool_choice", "response_format", "n", "presence_penalty", "frequency_penalty", "seed");

    @Override
    public ApiFormat format() {
        return ApiFormat.OPENAI;
    }

    @Override
    public CanonicalChatRequest toCanonicalRequest(byte[] body) {
        JsonNode root = JsonUtils.readTree(body);
        String model = root.path("model").asText(null);
        String system = null;
        List<CanonicalMessage> messages = new ArrayList<>();
        for (JsonNode m : root.path("messages")) {
            String role = m.path("role").asText("");
            if ("system".equals(role) || "developer".equals(role)) {
                String text = textOfContent(m.path("content"));
                system = system == null ? text : system + "\n" + text;
                continue;
            }
            List<CanonicalContentPart> parts = partsOfContent(m.path("content"));
            List<CanonicalToolCall> toolCalls = toolCallsOf(m.path("tool_calls"));
            String toolCallId = "tool".equals(role) && m.hasNonNull("tool_call_id") ? m.get("tool_call_id").asText() : null;
            messages.add(CanonicalMessage.of(role, parts, toolCalls, toolCallId));
        }
        Integer maxTokens = root.has("max_tokens") ? root.get("max_tokens").asInt() : null;
        Double temperature = root.has("temperature") ? root.get("temperature").asDouble() : null;
        Double topP = root.has("top_p") ? root.get("top_p").asDouble() : null;
        boolean stream = root.path("stream").asBoolean(false);
        Map<String, Object> ext = new HashMap<>();
        // 透传请求参数原样存 extensions（JsonNode），由各出站 fromCanonicalRequest 按协议回放·防跨协议转换静默丢失
        for (String key : PASS_THROUGH_PARAMS) {
            if (root.hasNonNull(key)) {
                ext.put(key, root.get(key));
            }
        }
        if (root.path("tools").isArray()) {
            ArrayNode tools = JsonUtils.mapper().createArrayNode();
            for (JsonNode t : root.get("tools")) {
                JsonNode fn = t.path("function");
                ObjectNode ct = tools.addObject();
                ct.put("name", fn.path("name").asText(""));
                ct.put("description", fn.path("description").asText(""));
                ct.set("parameters", fn.has("parameters") ? fn.get("parameters") : JsonUtils.mapper().createObjectNode());
            }
            ext.put("tools", tools);
        }
        return new CanonicalChatRequest(model, messages, system, maxTokens, temperature, topP, stream, ext);
    }

    @Override
    public byte[] fromCanonicalRequest(CanonicalChatRequest req) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("model", req.model());
        ArrayNode msgs = root.putArray("messages");
        if (req.system() != null && !req.system().isBlank()) {
            ObjectNode sys = msgs.addObject();
            sys.put("role", "system");
            sys.put("content", req.system());
        }
        for (CanonicalMessage m : req.messages()) {
            ObjectNode mn = msgs.addObject();
            mn.put("role", m.role());
            if ("tool".equals(m.role())) {
                if (m.toolCallId() != null) {
                    mn.put("tool_call_id", m.toolCallId());
                }
                mn.put("content", m.text());
            } else {
                writeContent(mn, m.parts());
                writeToolCalls(mn, m.toolCalls());
            }
        }
        if (req.maxTokens() != null) {
            root.put("max_tokens", req.maxTokens());
        }
        if (req.temperature() != null) {
            root.put("temperature", req.temperature());
        }
        if (req.topP() != null) {
            root.put("top_p", req.topP());
        }
        // 透传参数逐字回放（OpenAI↔OpenAI 兼容上游）：stop/tool_choice/response_format/n/penalties/seed
        if (req.extensions() != null) {
            for (String key : PASS_THROUGH_PARAMS) {
                if (req.extensions().get(key) instanceof JsonNode node && !node.isNull()) {
                    root.set(key, node);
                }
            }
        }
        Object tools = req.extensions() == null ? null : req.extensions().get("tools");
        if (tools instanceof JsonNode tn && tn.isArray()) {
            ArrayNode out = root.putArray("tools");
            for (JsonNode ct : tn) {
                ObjectNode t = out.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", ct.path("name").asText(""));
                fn.put("description", ct.path("description").asText(""));
                fn.set("parameters", ct.has("parameters") ? ct.get("parameters") : JsonUtils.mapper().createObjectNode());
            }
        }
        root.put("stream", req.stream());
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalChatResponse toCanonicalResponse(byte[] body) {
        JsonNode root = JsonUtils.readTree(body);
        JsonNode choice0 = root.path("choices").path(0);
        JsonNode message = choice0.path("message");
        JsonNode usage = root.path("usage");
        Map<String, Object> ext = new HashMap<>();
        if (message.path("tool_calls").isArray()) {
            ArrayNode calls = JsonUtils.mapper().createArrayNode();
            for (JsonNode tc : message.get("tool_calls")) {
                JsonNode fn = tc.path("function");
                ObjectNode cc = calls.addObject();
                cc.put("id", tc.path("id").asText(""));
                cc.put("name", fn.path("name").asText(""));
                cc.set("arguments", parseJsonOrText(fn.path("arguments").asText("{}")));
            }
            ext.put("toolCalls", calls);
        }
        return new CanonicalChatResponse(
                root.path("id").asText(null),
                root.path("model").asText(null),
                message.path("role").asText("assistant"),
                message.path("content").asText(""),
                choice0.path("finish_reason").asText(null),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                ext);
    }

    @Override
    public byte[] fromCanonicalResponse(CanonicalChatResponse resp) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("id", resp.id() == null ? "chatcmpl-canonical" : resp.id());
        root.put("object", "chat.completion");
        root.put("model", resp.model());
        ArrayNode choices = root.putArray("choices");
        ObjectNode c0 = choices.addObject();
        c0.put("index", 0);
        ObjectNode msg = c0.putObject("message");
        msg.put("role", resp.role() == null ? "assistant" : resp.role());
        msg.put("content", resp.content());
        Object toolCalls = resp.extensions() == null ? null : resp.extensions().get("toolCalls");
        boolean hasTools = toolCalls instanceof JsonNode tn && tn.isArray() && !tn.isEmpty();
        if (hasTools) {
            ArrayNode out = msg.putArray("tool_calls");
            for (JsonNode cc : (JsonNode) toolCalls) {
                ObjectNode tc = out.addObject();
                tc.put("id", cc.path("id").asText(""));
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", cc.path("name").asText(""));
                fn.put("arguments", JsonUtils.toJson(cc.has("arguments") ? cc.get("arguments") : JsonUtils.mapper().createObjectNode()));
            }
        }
        c0.put("finish_reason", resp.finishReason() == null ? (hasTools ? "tool_calls" : "stop") : resp.finishReason());
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", resp.promptTokens());
        usage.put("completion_tokens", resp.completionTokens());
        usage.put("total_tokens", resp.promptTokens() + resp.completionTokens());
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /** content（字符串或块数组）→ 拼接纯文本（system/developer 取文本用）。 */
    private static String textOfContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (CanonicalContentPart part : partsOfContent(content)) {
            if (part.isText() && part.text() != null) {
                sb.append(part.text());
            }
        }
        return sb.toString();
    }

    /** content（字符串或块数组 {type:text}/{type:image_url,image_url.url=dataURI}）→ canonical 块。 */
    private static List<CanonicalContentPart> partsOfContent(JsonNode content) {
        List<CanonicalContentPart> parts = new ArrayList<>();
        if (content == null || content.isNull()) {
            return parts;
        }
        if (content.isTextual()) {
            parts.add(CanonicalContentPart.text(content.asText()));
            return parts;
        }
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type) || ("input_text".equals(type))) {
                    parts.add(CanonicalContentPart.text(block.path("text").asText("")));
                } else if ("image_url".equals(type) || "input_image".equals(type)) {
                    String url = block.path("image_url").path("url").asText(block.path("image_url").asText(""));
                    CanonicalImage img = CanonicalImages.fromDataUri(url);
                    if (img == null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        img = CanonicalImage.url(url);   // 远程 URL：保留为 URL 形态·不再静默丢弃图片
                    }
                    if (img != null) {
                        parts.add(CanonicalContentPart.image(img));
                    }
                }
            }
        }
        return parts;
    }

    /** message.tool_calls[{id,function:{name,arguments(JSON字符串)}}] → canonical toolCalls。 */
    private static List<CanonicalToolCall> toolCallsOf(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return List.of();
        }
        List<CanonicalToolCall> calls = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            JsonNode fn = tc.path("function");
            calls.add(new CanonicalToolCall(
                    tc.path("id").asText(""),
                    fn.path("name").asText(""),
                    parseJsonOrText(fn.path("arguments").asText("{}"))));
        }
        return calls;
    }

    /** canonical 块 → content：单 text 块→字符串；含图片→数组（text/image_url dataURI）。 */
    private static void writeContent(ObjectNode mn, List<CanonicalContentPart> parts) {
        boolean hasImage = parts.stream().anyMatch(CanonicalContentPart::isImage);
        if (!hasImage) {
            StringBuilder sb = new StringBuilder();
            for (CanonicalContentPart part : parts) {
                if (part.isText() && part.text() != null) {
                    sb.append(part.text());
                }
            }
            mn.put("content", sb.toString());
            return;
        }
        ArrayNode arr = mn.putArray("content");
        for (CanonicalContentPart part : parts) {
            if (part.isText()) {
                ObjectNode t = arr.addObject();
                t.put("type", "text");
                t.put("text", part.text() == null ? "" : part.text());
            } else if (part.isImage()) {
                ObjectNode img = arr.addObject();
                img.put("type", "image_url");
                // 远程 URL 直透传；base64 形态拼 dataURI
                String src = part.image().isUrl() ? part.image().url() : CanonicalImages.toDataUri(part.image());
                img.putObject("image_url").put("url", src);
            }
        }
    }

    /** canonical toolCalls → message.tool_calls[{id,type:function,function:{name,arguments(JSON字符串)}}]。 */
    private static void writeToolCalls(ObjectNode mn, List<CanonicalToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        ArrayNode out = mn.putArray("tool_calls");
        for (CanonicalToolCall call : toolCalls) {
            ObjectNode tc = out.addObject();
            tc.put("id", call.id() == null ? "" : call.id());
            tc.put("type", "function");
            ObjectNode fn = tc.putObject("function");
            fn.put("name", call.name() == null ? "" : call.name());
            fn.put("arguments", JsonUtils.toJson(call.arguments() == null ? JsonUtils.mapper().createObjectNode() : call.arguments()));
        }
    }

    private static JsonNode parseJsonOrText(String s) {
        try {
            return JsonUtils.readTree(s);
        } catch (Exception e) {
            return JsonUtils.mapper().valueToTree(Map.of("_raw", s));
        }
    }
}
