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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini generateContent 互转器：Gemini 请求/响应 ↔ Canonical。
 * 要点：role 为 user/model（assistant↔model 归一）；system 走 systemInstruction.parts；
 * content 为 contents[].parts[].text；generationConfig.maxOutputTokens/temperature/topP；
 * usage 为 usageMetadata.promptTokenCount/candidatesTokenCount；finishReason 大写枚举。
 * <p><b>含 tool/function-call 映射（P4·非流式）</b>：请求 {@code tools[].functionDeclarations[]{name,description,parameters}}
 * ↔ Canonical extensions(tools)；响应 {@code candidates[0].content.parts[].functionCall{name,args}}
 * ↔ Canonical extensions(toolCalls)。Gemini 无 call id 故合成 {@code call_N}；有 functionCall 时 finishReason 置 null
 * 让下游推断 tool_calls（Gemini 即便函数调用也回 STOP）。流式 functionCall 由 OpenAiSseConverter.processGemini 处理。
 *
 * @author nexusai
 */
public class GeminiChatTransformer implements ChatTransformer {

    /**
     * Gemini 模型占位哨兵：Gemini 原生请求体不含 model（模型在 URL path），入站解析拿不到模型时以此占位，
     * <b>杜绝产出 {@code null} model</b>（跨协议出站 model=null → 上游 400）。真实模型由 relay 出站装配
     * 依 peek.model（URL 提取）回填——本模块不越界读 URL；relay 侧回填由他人处理。
     */
    public static final String MODEL_FROM_URL_PLACEHOLDER = "__nexusai_gemini_model_from_url__";

    @Override
    public ApiFormat format() {
        return ApiFormat.GEMINI;
    }

    @Override
    public CanonicalChatRequest toCanonicalRequest(byte[] body) {
        JsonNode root = JsonUtils.readTree(body);
        // Gemini 原生体不带 model（模型在 URL path）；缺失时置哨兵占位（禁 null·由 relay 依 peek.model 回填）
        String model = root.path("model").asText(MODEL_FROM_URL_PLACEHOLDER);
        String system = null;
        JsonNode si = root.path("systemInstruction");
        if (si.isObject()) {
            system = textOfParts(si.path("parts"));
        }
        List<CanonicalMessage> messages = new ArrayList<>();
        int[] callSeq = {0};
        // functionCall.name → 合成 call id 的 FIFO 队列：同一 model 轮次内同名并行调用各自入队，
        // functionResponse 按同名取队首配对（Gemini 无 call id；按名配对但用队列防同名并行互相覆盖错位）。
        Map<String, Deque<String>> pendingCalls = new HashMap<>();
        for (JsonNode c : root.path("contents")) {
            String role = "model".equals(c.path("role").asText("")) ? "assistant" : "user";
            parseContent(role, c.path("parts"), messages, callSeq, pendingCalls);
        }
        JsonNode gc = root.path("generationConfig");
        Integer maxTokens = gc.has("maxOutputTokens") ? gc.get("maxOutputTokens").asInt() : null;
        Double temperature = gc.has("temperature") ? gc.get("temperature").asDouble() : null;
        Double topP = gc.has("topP") ? gc.get("topP").asDouble() : null;
        Map<String, Object> ext = new HashMap<>();
        // Gemini tools[].functionDeclarations[]{name,description,parameters} → canonical tools[{name,description,parameters}]
        ArrayNode decls = collectFunctionDeclarations(root.path("tools"));
        if (!decls.isEmpty()) {
            ext.put("tools", decls);
        }
        return new CanonicalChatRequest(model, messages, system, maxTokens, temperature, topP, false, ext);
    }

    @Override
    public byte[] fromCanonicalRequest(CanonicalChatRequest req) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        if (req.system() != null && !req.system().isBlank()) {
            ObjectNode si = root.putObject("systemInstruction");
            si.putArray("parts").addObject().put("text", req.system());
        }
        ArrayNode contents = root.putArray("contents");
        // toolCall.id → 函数名映射（Gemini functionResponse 需 name·canonical tool 消息只带 toolCallId）
        Map<String, String> callIdToName = toolCallNameIndex(req.messages());
        for (CanonicalMessage m : req.messages()) {
            ObjectNode cn = contents.addObject();
            if ("tool".equals(m.role())) {
                cn.put("role", "user");
                ArrayNode parts = cn.putArray("parts");
                String name = callIdToName.getOrDefault(m.toolCallId(), m.toolCallId() == null ? "" : m.toolCallId());
                ObjectNode fr = parts.addObject().putObject("functionResponse");
                fr.put("name", name);
                fr.putObject("response").put("result", m.text());
                continue;
            }
            cn.put("role", "assistant".equals(m.role()) ? "model" : "user");
            ArrayNode parts = cn.putArray("parts");
            writeParts(parts, m.parts());
            writeFunctionCalls(parts, m.toolCalls());
        }
        ObjectNode gc = root.putObject("generationConfig");
        if (req.maxTokens() != null) {
            gc.put("maxOutputTokens", req.maxTokens());
        }
        if (req.temperature() != null) {
            gc.put("temperature", req.temperature());
        }
        if (req.topP() != null) {
            gc.put("topP", req.topP());
        }
        // 透传参数映射到 Gemini：stop→generationConfig.stopSequences(数组)。
        // tool_choice→toolConfig.functionCallingConfig.mode（auto/required→AUTO/ANY·none→NONE）。
        // response_format/n/presence_penalty/frequency_penalty/seed：Gemini generateContent 无直接对应·有意不映射。
        if (req.extensions() != null) {
            JsonNode stop = req.extensions().get("stop") instanceof JsonNode s ? s : null;
            if (stop != null && !stop.isNull()) {
                ArrayNode seqs = gc.putArray("stopSequences");
                if (stop.isArray()) {
                    stop.forEach(x -> seqs.add(x.asText()));
                } else if (stop.isTextual()) {
                    seqs.add(stop.asText());
                }
            }
            JsonNode toolChoice = req.extensions().get("tool_choice") instanceof JsonNode t ? t : null;
            String mode = geminiFunctionMode(toolChoice);
            if (mode != null) {
                root.putObject("toolConfig").putObject("functionCallingConfig").put("mode", mode);
            }
        }
        // canonical tools → Gemini tools:[{functionDeclarations:[{name,description,parameters}]}]
        Object tools = req.extensions() == null ? null : req.extensions().get("tools");
        if (tools instanceof JsonNode tn && tn.isArray() && !tn.isEmpty()) {
            ArrayNode fds = root.putArray("tools").addObject().putArray("functionDeclarations");
            for (JsonNode ct : tn) {
                ObjectNode fd = fds.addObject();
                fd.put("name", ct.path("name").asText(""));
                if (ct.hasNonNull("description") && !ct.path("description").asText("").isEmpty()) {
                    fd.put("description", ct.path("description").asText(""));
                }
                fd.set("parameters", ct.has("parameters") ? ct.get("parameters") : JsonUtils.mapper().createObjectNode());
            }
        }
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalChatResponse toCanonicalResponse(byte[] body) {
        JsonNode root = JsonUtils.readTree(body);
        JsonNode cand = root.path("candidates").path(0);
        JsonNode um = root.path("usageMetadata");
        JsonNode parts = cand.path("content").path("parts");
        Map<String, Object> ext = new HashMap<>();
        // candidates[0].content.parts[].functionCall{name,args} → canonical toolCalls[{id,name,arguments(对象)}]
        ArrayNode calls = JsonUtils.mapper().createArrayNode();
        int idx = 0;
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                if (p.has("functionCall")) {
                    JsonNode fcn = p.path("functionCall");
                    ObjectNode cc = calls.addObject();
                    cc.put("id", "call_" + (idx++));   // Gemini 无 call id，合成稳定序号
                    cc.put("name", fcn.path("name").asText(""));
                    cc.set("arguments", fcn.has("args") ? fcn.get("args") : JsonUtils.mapper().createObjectNode());
                }
            }
        }
        boolean hasTools = !calls.isEmpty();
        if (hasTools) {
            ext.put("toolCalls", calls);
        }
        // 有 functionCall 时 finishReason 置 null，让下游推断 tool_calls（Gemini 函数调用仍回 STOP）
        String finish = hasTools ? null : mapFinish(cand.path("finishReason").asText(null));
        return new CanonicalChatResponse(
                root.path("responseId").asText(null),
                root.path("modelVersion").asText(null),
                "assistant",
                textOfParts(parts),
                finish,
                um.path("promptTokenCount").asInt(0),
                // 🔴 思维链 thoughtsTokenCount 并入 completion（Gemini 2.5·按输出价计费），否则漏计资损
                um.path("candidatesTokenCount").asInt(0) + um.path("thoughtsTokenCount").asInt(0),
                ext);
    }

    @Override
    public byte[] fromCanonicalResponse(CanonicalChatResponse resp) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        ObjectNode cand = root.putArray("candidates").addObject();
        ObjectNode content = cand.putObject("content");
        content.put("role", "model");
        ArrayNode parts = content.putArray("parts");
        if (resp.content() != null && !resp.content().isEmpty()) {
            parts.addObject().put("text", resp.content());
        }
        // canonical toolCalls → Gemini parts[].functionCall{name,args}
        Object toolCalls = resp.extensions() == null ? null : resp.extensions().get("toolCalls");
        boolean hasTools = toolCalls instanceof JsonNode tn && tn.isArray() && !tn.isEmpty();
        if (hasTools) {
            for (JsonNode cc : (JsonNode) toolCalls) {
                ObjectNode fc = parts.addObject().putObject("functionCall");
                fc.put("name", cc.path("name").asText(""));
                fc.set("args", cc.has("arguments") ? cc.get("arguments") : JsonUtils.mapper().createObjectNode());
            }
        }
        cand.put("finishReason", hasTools ? "STOP" : unmapFinish(resp.finishReason()));
        cand.put("index", 0);
        ObjectNode um = root.putObject("usageMetadata");
        um.put("promptTokenCount", resp.promptTokens());
        um.put("candidatesTokenCount", resp.completionTokens());
        um.put("totalTokenCount", resp.promptTokens() + resp.completionTokens());
        if (resp.model() != null) {
            root.put("modelVersion", resp.model());
        }
        return JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static ArrayNode collectFunctionDeclarations(JsonNode tools) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        if (tools != null && tools.isArray()) {
            for (JsonNode t : tools) {
                for (JsonNode fd : t.path("functionDeclarations")) {
                    ObjectNode ct = out.addObject();
                    ct.put("name", fd.path("name").asText(""));
                    ct.put("description", fd.path("description").asText(""));
                    ct.set("parameters", fd.has("parameters") ? fd.get("parameters") : JsonUtils.mapper().createObjectNode());
                }
            }
        }
        return out;
    }

    /**
     * 解析 Gemini parts → canonical：text→text 块；inlineData→图片块；functionCall→toolCall（合成 call_N·按名入队 id）；
     * functionResponse→独立 {@code role=tool} 消息（toolCallId <b>按函数名取队首 call id 回填</b>·与 functionCall 精确配对·
     * 队列保证同名并行调用按出现先后 FIFO 配对·不再各自 ++ 序号或被同名覆盖导致错位）。
     * callSeq 用 int[] 跨调用累加；pendingCalls 跨调用保留待配对的 name→id 队列。
     */
    private static void parseContent(String role, JsonNode parts, List<CanonicalMessage> out,
                                     int[] callSeq, Map<String, Deque<String>> pendingCalls) {
        if (parts == null || !parts.isArray()) {
            return;
        }
        List<CanonicalContentPart> contentParts = new ArrayList<>();
        List<CanonicalToolCall> toolCalls = new ArrayList<>();
        for (JsonNode p : parts) {
            if (p.has("functionCall")) {
                JsonNode fc = p.path("functionCall");
                String name = fc.path("name").asText("");
                String id = "call_" + (callSeq[0]++);
                // 按名入队：同名并行调用各自保留 id·functionResponse 按出现先后取队首回填（FIFO）
                pendingCalls.computeIfAbsent(name, k -> new ArrayDeque<>()).addLast(id);
                toolCalls.add(new CanonicalToolCall(
                        id, name,
                        fc.has("args") ? fc.get("args") : JsonUtils.mapper().createObjectNode()));
            } else if (p.has("functionResponse")) {
                JsonNode fr = p.path("functionResponse");
                String name = fr.path("name").asText("");
                // 按函数名取该名队首 functionCall id（FIFO 配对同名并行调用）；无匹配（异常顺序）才兜底新合成
                Deque<String> queue = pendingCalls.get(name);
                String id = queue == null ? null : queue.pollFirst();
                if (id == null) {
                    id = "call_" + (callSeq[0]++);
                }
                out.add(CanonicalMessage.of("tool",
                        List.of(CanonicalContentPart.text(functionResponseText(fr.path("response")))),
                        List.of(), id));
            } else if (p.has("inlineData")) {
                JsonNode inline = p.path("inlineData");
                contentParts.add(CanonicalContentPart.image(CanonicalImage.base64(
                        inline.path("mimeType").asText(""), inline.path("data").asText(""))));
            } else if (p.has("fileData")) {
                // Gemini 远程 URL 图源（fileData.fileUri）→ URL 形态·不再仅认 inlineData
                contentParts.add(CanonicalContentPart.image(
                        CanonicalImage.url(p.path("fileData").path("fileUri").asText(""))));
            } else if (p.has("text")) {
                contentParts.add(CanonicalContentPart.text(p.path("text").asText("")));
            }
        }
        if (!contentParts.isEmpty() || !toolCalls.isEmpty()) {
            out.add(CanonicalMessage.of(role, contentParts, toolCalls, null));
        }
    }

    /** Gemini functionResponse.response（对象）→ 纯文本（取 result 或整体序列化）。 */
    private static String functionResponseText(JsonNode response) {
        if (response == null || response.isNull()) {
            return "";
        }
        if (response.has("result")) {
            JsonNode result = response.get("result");
            return result.isTextual() ? result.asText() : JsonUtils.toJson(result);
        }
        return JsonUtils.toJson(response);
    }

    /** 建 toolCall.id → 函数名映射（出站 functionResponse 需 name）。 */
    private static Map<String, String> toolCallNameIndex(List<CanonicalMessage> messages) {
        Map<String, String> index = new HashMap<>();
        for (CanonicalMessage m : messages) {
            if (m.toolCalls() == null) {
                continue;
            }
            for (CanonicalToolCall call : m.toolCalls()) {
                if (call.id() != null) {
                    index.put(call.id(), call.name() == null ? "" : call.name());
                }
            }
        }
        return index;
    }

    /** canonical 块 → Gemini parts：text→{text}；image→{inlineData{mimeType,data}}。 */
    private static void writeParts(ArrayNode parts, List<CanonicalContentPart> contentParts) {
        for (CanonicalContentPart part : contentParts) {
            if (part.isText()) {
                parts.addObject().put("text", part.text() == null ? "" : part.text());
            } else if (part.isImage()) {
                if (part.image().isUrl()) {
                    // Gemini 远程 URL 图源（fileData.fileUri）·不内联 base64
                    ObjectNode fileData = parts.addObject().putObject("fileData");
                    fileData.put("fileUri", part.image().url());
                } else {
                    ObjectNode inline = parts.addObject().putObject("inlineData");
                    inline.put("mimeType", part.image().mediaType());
                    inline.put("data", part.image().base64Data());
                }
            }
        }
    }

    /** canonical toolCalls → Gemini parts[].functionCall{name,args}。 */
    private static void writeFunctionCalls(ArrayNode parts, List<CanonicalToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        for (CanonicalToolCall call : toolCalls) {
            ObjectNode fc = parts.addObject().putObject("functionCall");
            fc.put("name", call.name() == null ? "" : call.name());
            fc.set("args", call.arguments() == null ? JsonUtils.mapper().createObjectNode() : call.arguments());
        }
    }

    private static String textOfParts(JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            if (p.has("text")) {
                sb.append(p.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    /**
     * OpenAI tool_choice → Gemini functionCallingConfig.mode：
     * "auto"→AUTO；"required"→ANY；"none"→NONE；{type:function}→ANY（指定函数·Gemini 无精确单函数强制·退化为 ANY）。
     */
    private static String geminiFunctionMode(JsonNode tc) {
        if (tc == null || tc.isNull()) {
            return null;
        }
        if (tc.isTextual()) {
            return switch (tc.asText()) {
                case "auto" -> "AUTO";
                case "required" -> "ANY";
                case "none" -> "NONE";
                default -> null;
            };
        }
        if (tc.isObject() && "function".equals(tc.path("type").asText(""))) {
            return "ANY";
        }
        return null;
    }

    /**
     * Gemini finishReason → OpenAI finish_reason（非流式 {@link #toCanonicalResponse} 与流式
     * {@link OpenAiSseConverter#feedBytes} 共用·统一口径不重复）：STOP→stop、MAX_TOKENS→length、
     * SAFETY/RECITATION→content_filter（内容过滤·不再误判为正常 stop 致客户端误读完成）；
     * 未知值保守默认 stop（不再 toLowerCase 透传非法枚举）。入参 null 返回 null。
     */
    static String mapFinish(String f) {
        if (f == null) {
            return null;
        }
        return switch (f) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION" -> "content_filter";
            default -> "stop";
        };
    }

    private static String unmapFinish(String f) {
        if (f == null) {
            return "STOP";
        }
        return switch (f) {
            case "stop" -> "STOP";
            case "length" -> "MAX_TOKENS";
            default -> f.toUpperCase();
        };
    }
}
