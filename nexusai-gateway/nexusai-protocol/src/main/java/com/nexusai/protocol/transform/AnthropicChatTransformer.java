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
 * Anthropic Messages 互转器：Anthropic {@code /v1/messages} 请求/响应 ↔ Canonical。
 * 要点：system 走顶层字段；max_tokens 必填（缺省兜底）；content 为 block 数组（取/拼 text）；
 * usage 为 input_tokens/output_tokens。含 tool 映射：请求 tools[{name,description,input_schema}]、
 * 响应 content 的 {type:tool_use,id,name,input} ↔ Canonical extensions(tools/toolCalls)。
 *
 * @author nexusai
 */
public class AnthropicChatTransformer implements ChatTransformer {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    @Override
    public ApiFormat format() {
        return ApiFormat.ANTHROPIC;
    }

    @Override
    public CanonicalChatRequest toCanonicalRequest(byte[] body) {
        JsonNode root = JsonUtils.readTree(body);
        String model = root.path("model").asText(null);
        String system = systemText(root.path("system"));
        List<CanonicalMessage> messages = new ArrayList<>();
        for (JsonNode m : root.path("messages")) {
            parseMessage(m, messages);
        }
        Integer maxTokens = root.has("max_tokens") ? root.get("max_tokens").asInt() : null;
        Double temperature = root.has("temperature") ? root.get("temperature").asDouble() : null;
        Double topP = root.has("top_p") ? root.get("top_p").asDouble() : null;
        boolean stream = root.path("stream").asBoolean(false);
        Map<String, Object> ext = new HashMap<>();
        // tools[{name,description,input_schema}] → canonical [{name,description,parameters}]
        if (root.path("tools").isArray()) {
            ArrayNode tools = JsonUtils.mapper().createArrayNode();
            for (JsonNode t : root.get("tools")) {
                ObjectNode ct = tools.addObject();
                ct.put("name", t.path("name").asText(""));
                ct.put("description", t.path("description").asText(""));
                ct.set("parameters", t.has("input_schema") ? t.get("input_schema") : JsonUtils.mapper().createObjectNode());
            }
            ext.put("tools", tools);
        }
        return new CanonicalChatRequest(model, messages, system, maxTokens, temperature, topP, stream, ext);
    }

    @Override
    public byte[] fromCanonicalRequest(CanonicalChatRequest req) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("model", req.model());
        root.put("max_tokens", req.maxTokens() == null ? DEFAULT_MAX_TOKENS : req.maxTokens());
        if (req.system() != null && !req.system().isBlank()) {
            root.put("system", req.system());
        }
        ArrayNode msgs = root.putArray("messages");
        // 先修复 tool_use/tool_result 配对 + 悬空丢弃（Anthropic 强不变式），再把每条 canonical 消息映射为
        // (Anthropic 角色, 块数组)——tool→user(tool_result)——并合并相邻同角色（含 tool_result 与后续 user 合并），
        // 最终满足 user/assistant 严格交替。
        List<CanonicalMessage> repaired = AnthropicToolPairing.repair(req.messages());
        writeMessages(msgs, repaired);
        if (req.temperature() != null) {
            root.put("temperature", req.temperature());
        }
        if (req.topP() != null) {
            root.put("top_p", req.topP());
        }
        // 透传参数映射到 Anthropic：stop→stop_sequences(数组)、tool_choice→Anthropic tool_choice 形态。
        // response_format/n/presence_penalty/frequency_penalty/seed：Anthropic Messages 无对应字段·有意不映射（避免上游 400）。
        if (req.extensions() != null) {
            JsonNode stop = asJsonNode(req.extensions().get("stop"));
            if (stop != null && !stop.isNull()) {
                ArrayNode seqs = root.putArray("stop_sequences");
                if (stop.isArray()) {
                    stop.forEach(s -> seqs.add(s.asText()));
                } else if (stop.isTextual()) {
                    seqs.add(stop.asText());
                }
            }
            JsonNode toolChoice = mapToolChoice(asJsonNode(req.extensions().get("tool_choice")));
            if (toolChoice != null) {
                root.set("tool_choice", toolChoice);
            }
        }
        // canonical tools → anthropic tools{name,description,input_schema}
        Object tools = req.extensions() == null ? null : req.extensions().get("tools");
        if (tools instanceof JsonNode tn && tn.isArray()) {
            ArrayNode out = root.putArray("tools");
            for (JsonNode ct : tn) {
                ObjectNode t = out.addObject();
                t.put("name", ct.path("name").asText(""));
                t.put("description", ct.path("description").asText(""));
                t.set("input_schema", ct.has("parameters") ? ct.get("parameters") : JsonUtils.mapper().createObjectNode());
            }
        }
        root.put("stream", req.stream());
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalChatResponse toCanonicalResponse(byte[] body) {
        JsonNode root = JsonUtils.readTree(body);
        JsonNode usage = root.path("usage");
        Map<String, Object> ext = new HashMap<>();
        // content 中 tool_use block → canonical toolCalls[{id,name,arguments(对象)}]
        JsonNode content = root.path("content");
        if (content.isArray()) {
            ArrayNode calls = JsonUtils.mapper().createArrayNode();
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    ObjectNode cc = calls.addObject();
                    cc.put("id", block.path("id").asText(""));
                    cc.put("name", block.path("name").asText(""));
                    cc.set("arguments", block.has("input") ? block.get("input") : JsonUtils.mapper().createObjectNode());
                }
            }
            if (!calls.isEmpty()) {
                ext.put("toolCalls", calls);
            }
        }
        // Anthropic input_tokens 仅"非缓存"prompt；canonical promptTokens 取全量(含 cache_read+cache_creation)·
        // 对齐 OpenAI 口径(prompt_tokens 含 cached)，避免转换后客户端少报 prompt（缓存重时严重偏低）。
        int promptTokens = usage.path("input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0);
        return new CanonicalChatResponse(
                root.path("id").asText(null),
                root.path("model").asText(null),
                root.path("role").asText("assistant"),
                textOf(content),
                mapStopReason(root.path("stop_reason").asText(null)),
                promptTokens,
                usage.path("output_tokens").asInt(0),
                ext);
    }

    @Override
    public byte[] fromCanonicalResponse(CanonicalChatResponse resp) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("id", resp.id() == null ? "msg_canonical" : resp.id());
        root.put("type", "message");
        root.put("role", resp.role() == null ? "assistant" : resp.role());
        root.put("model", resp.model());
        ArrayNode content = root.putArray("content");
        if (resp.content() != null && !resp.content().isEmpty()) {
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", resp.content());
        }
        Object toolCalls = resp.extensions() == null ? null : resp.extensions().get("toolCalls");
        boolean hasTools = toolCalls instanceof JsonNode tn && tn.isArray() && !tn.isEmpty();
        if (hasTools) {
            for (JsonNode cc : (JsonNode) toolCalls) {
                ObjectNode block = content.addObject();
                block.put("type", "tool_use");
                block.put("id", cc.path("id").asText(""));
                block.put("name", cc.path("name").asText(""));
                block.set("input", cc.has("arguments") ? cc.get("arguments") : JsonUtils.mapper().createObjectNode());
            }
        }
        String finish = resp.finishReason();
        root.put("stop_reason", hasTools ? "tool_use" : stopReasonForAnthropic(finish));
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", resp.promptTokens());
        usage.put("output_tokens", resp.completionTokens());
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 把已配对的 canonical 消息写为 Anthropic messages：每条映射为 (Anthropic 角色, 块数组)
     * （tool→user 的 tool_result 块），合并相邻同角色后输出，保证 user/assistant 严格交替。
     */
    private static void writeMessages(ArrayNode msgs, List<CanonicalMessage> repaired) {
        String pendingRole = null;
        ArrayNode pendingContent = null;
        for (CanonicalMessage m : repaired) {
            String anthropicRole = "tool".equals(m.role()) ? "user"
                    : ("assistant".equals(m.role()) ? "assistant" : "user");
            if (pendingRole == null || !pendingRole.equals(anthropicRole)) {
                ObjectNode mn = msgs.addObject();
                mn.put("role", anthropicRole);
                pendingContent = mn.putArray("content");
                pendingRole = anthropicRole;
            }
            if ("tool".equals(m.role())) {
                writeToolResultBlock(pendingContent, m);
            } else {
                writeContentBlocks(pendingContent, m.parts());
                writeToolUseBlocks(pendingContent, m.toolCalls());
            }
        }
    }

    /** system 字段（字符串或块数组）→ 纯文本。 */
    private static String systemText(JsonNode system) {
        if (system == null || system.isMissingNode() || system.isNull()) {
            return null;
        }
        if (system.isTextual()) {
            return system.asText();
        }
        String text = textOf(system);
        return text.isEmpty() ? null : text;
    }

    /**
     * 解析一条 Anthropic 消息的 content 块数组为 canonical：text→text 块；image→图片块；
     * tool_use→assistant 的 toolCall；tool_result→拆出独立 {@code role=tool} 消息（toolCallId=tool_use_id）。
     * 同一条消息内 tool_result 可能多块（并行调用），各拆为一条 tool 消息。
     */
    private static void parseMessage(JsonNode m, List<CanonicalMessage> out) {
        String role = m.path("role").asText("");
        JsonNode content = m.path("content");
        // content 为字符串：纯文本消息
        if (content.isTextual()) {
            out.add(CanonicalMessage.of(role, content.asText()));
            return;
        }
        List<CanonicalContentPart> parts = new ArrayList<>();
        List<CanonicalToolCall> toolCalls = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                switch (type) {
                    case "text" -> parts.add(CanonicalContentPart.text(block.path("text").asText("")));
                    case "image" -> {
                        JsonNode src = block.path("source");
                        if ("url".equals(src.path("type").asText(""))) {
                            parts.add(CanonicalContentPart.image(CanonicalImage.url(src.path("url").asText(""))));
                        } else {
                            parts.add(CanonicalContentPart.image(CanonicalImage.base64(
                                    src.path("media_type").asText(""), src.path("data").asText(""))));
                        }
                    }
                    case "tool_use" -> toolCalls.add(new CanonicalToolCall(
                            block.path("id").asText(""),
                            block.path("name").asText(""),
                            block.has("input") ? block.get("input") : JsonUtils.mapper().createObjectNode()));
                    case "tool_result" -> {
                        // 🔴 tool_result 可含 image/结构化块（Anthropic 视觉工具结果）：解析为内容块(text+image)，
                        // 不再仅拍平为文本而丢图。空结果留空文本占位保配对。
                        List<CanonicalContentPart> trParts = parseContentParts(block.path("content"));
                        if (trParts.isEmpty()) {
                            trParts = List.of(CanonicalContentPart.text(""));
                        }
                        out.add(CanonicalMessage.of("tool", trParts, List.of(),
                                block.path("tool_use_id").asText("")));
                    }
                    default -> {
                        // 未知块（thinking 等）：略过文本聚合·不阻断
                    }
                }
            }
        }
        if (!parts.isEmpty() || !toolCalls.isEmpty()) {
            out.add(CanonicalMessage.of(role, parts, toolCalls, null));
        }
    }

    /** tool_result.content（字符串或块数组）→ canonical 内容块（text + image·保多模态工具结果不丢图）。 */
    private static List<CanonicalContentPart> parseContentParts(JsonNode content) {
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
                if ("text".equals(type)) {
                    parts.add(CanonicalContentPart.text(block.path("text").asText("")));
                } else if ("image".equals(type)) {
                    JsonNode src = block.path("source");
                    if ("url".equals(src.path("type").asText(""))) {
                        parts.add(CanonicalContentPart.image(CanonicalImage.url(src.path("url").asText(""))));
                    } else {
                        parts.add(CanonicalContentPart.image(CanonicalImage.base64(
                                src.path("media_type").asText(""), src.path("data").asText(""))));
                    }
                }
            }
        }
        return parts;
    }

    /** canonical 块 → Anthropic content：text→{type:text}；image→{type:image,source{base64}}。 */
    private static void writeContentBlocks(ArrayNode content, List<CanonicalContentPart> parts) {
        for (CanonicalContentPart part : parts) {
            if (part.isText()) {
                ObjectNode block = content.addObject();
                block.put("type", "text");
                block.put("text", part.text() == null ? "" : part.text());
            } else if (part.isImage()) {
                ObjectNode block = content.addObject();
                block.put("type", "image");
                ObjectNode source = block.putObject("source");
                if (part.image().isUrl()) {
                    // Anthropic 远程 URL 图源（type=url）·不内联 base64
                    source.put("type", "url");
                    source.put("url", part.image().url());
                } else {
                    source.put("type", "base64");
                    source.put("media_type", part.image().mediaType());
                    source.put("data", part.image().base64Data());
                }
            }
        }
    }

    /** canonical toolCalls → Anthropic {type:tool_use,id,name,input}。 */
    private static void writeToolUseBlocks(ArrayNode content, List<CanonicalToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        for (CanonicalToolCall call : toolCalls) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", call.id() == null ? "" : call.id());
            block.put("name", call.name() == null ? "" : call.name());
            block.set("input", call.arguments() == null ? JsonUtils.mapper().createObjectNode() : call.arguments());
        }
    }

    /** role=tool 消息 → Anthropic {type:tool_result,tool_use_id,content}。含图写结构化块数组(text+image)·否则纯文本字符串。 */
    private static void writeToolResultBlock(ArrayNode content, CanonicalMessage toolMsg) {
        ObjectNode block = content.addObject();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolMsg.toolCallId() == null ? "" : toolMsg.toolCallId());
        List<CanonicalContentPart> parts = toolMsg.parts();
        boolean hasImage = parts != null && parts.stream().anyMatch(CanonicalContentPart::isImage);
        if (hasImage) {
            writeContentBlocks(block.putArray("content"), parts);   // 含图：结构化块·不拍平丢图
        } else {
            block.put("content", toolMsg.text());                   // 纯文本：字符串形态（向后兼容）
        }
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText()) || block.has("text")) {
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** extensions 值取 JsonNode（透传参数均以 JsonNode 入站·非则忽略）。 */
    private static JsonNode asJsonNode(Object value) {
        return value instanceof JsonNode node ? node : null;
    }

    /**
     * OpenAI tool_choice → Anthropic tool_choice：
     * "auto"→{type:auto}；"required"→{type:any}；{type:function,function:{name}}→{type:tool,name}。
     * "none"（OpenAI 禁用工具）无 Anthropic 对应·返回 null（不发·有意不映射）。
     */
    private static JsonNode mapToolChoice(JsonNode tc) {
        if (tc == null || tc.isNull()) {
            return null;
        }
        if (tc.isTextual()) {
            return switch (tc.asText()) {
                case "auto" -> JsonUtils.mapper().createObjectNode().put("type", "auto");
                case "required" -> JsonUtils.mapper().createObjectNode().put("type", "any");
                default -> null;   // "none" 等：Anthropic 无对应
            };
        }
        if (tc.isObject() && "function".equals(tc.path("type").asText(""))) {
            String name = tc.path("function").path("name").asText("");
            if (!name.isEmpty()) {
                return JsonUtils.mapper().createObjectNode().put("type", "tool").put("name", name);
            }
        }
        return null;
    }

    private static String mapStopReason(String stop) {
        if (stop == null) {
            return null;
        }
        return switch (stop) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> stop;
        };
    }

    /**
     * canonical finish_reason → Anthropic stop_reason（合法枚举）：length→max_tokens（不再原样透传 OpenAI 的 "length"）、
     * tool_calls→tool_use、content_filter→refusal；stop/null 及未知值保守回退 end_turn（避免非法 stop_reason 破坏严格客户端）。
     */
    private static String stopReasonForAnthropic(String finish) {
        if (finish == null) {
            return "end_turn";
        }
        return switch (finish) {
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "refusal";
            case "stop", "end_turn", "stop_sequence" -> "end_turn";
            default -> "end_turn";
        };
    }
}
