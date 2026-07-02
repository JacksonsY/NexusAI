package com.nexusai.protocol.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.util.JsonUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 流式跨协议转码：上游 Anthropic/Gemini SSE → 客户端 OpenAI {@code chat.completion.chunk} SSE。
 * <p><b>有状态状态机</b>：文本增量(text delta)、<b>tool_call 生命周期</b>（开块→参数增量→收尾）、usage 累计、finish_reason 映射。
 * 跨 DataBuffer 的半行用 {@link State#lineBuf} 缓存；上游块 index 经 {@link State#blockToToolIndex} 映射到 OpenAI tool_call index。
 * <ul>
 *   <li>Anthropic：{@code content_block_start[tool_use]}→开块、{@code input_json_delta}→参数增量、{@code message_delta.stop_reason}→finish_reason；</li>
 *   <li>Gemini：{@code functionCall} part→一次性开块+整段参数（Gemini 不分片 JSON）。</li>
 * </ul>
 *
 * @author nexusai
 */
public final class OpenAiSseConverter {

    /** 单 SSE 行累积字节上限（防异常上游发无换行超长流 → 单请求 lineBuf 无界堆积·评审缺口5）。 */
    public static final int MAX_LINE_BYTES = 4 * 1024 * 1024;

    private OpenAiSseConverter() {
    }

    /** 单请求转码状态（跨 chunk）。调用方每请求 new 一个。 */
    public static final class State {
        public String id = "chatcmpl-" + Long.toHexString(System.nanoTime());
        public String model = "";
        public int promptTokens;
        public int completionTokens;
        /** 缓存读命中 token（Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount·计费按 cachedInputRate 折扣） */
        public int cachedTokens;
        /** 缓存创建写入 token（Anthropic cache_creation_input_tokens·计费按 writeCachedRate） */
        public int cacheCreationTokens;
        /** 跨 DataBuffer 的半行原始字节缓存（整行才 UTF-8 decode·防多字节字符跨块乱码·评审缺口4） */
        public final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        /** 当前行是否已超 {@link #MAX_LINE_BYTES} 被丢弃（跨块保持·至行尾 {@code \n} 复位·防单请求堆无界·评审缺口5） */
        public boolean lineOverflow;
        /** OpenAI tool_call 下一序号（仅 tool_use 块占用，text 块不占） */
        public int toolIndexCounter;
        /** 上游块 index → OpenAI tool_call index（区分 text/tool 混排时的序号） */
        public final Map<Integer, Integer> blockToToolIndex = new HashMap<>();
        /** 是否已产出过 tool_call（决定 finish_reason 兜底为 tool_calls） */
        public boolean sawToolCall;
        /** 映射后的 finish_reason（默认 stop·tool 调用时 tool_calls·截断时 length） */
        public String finishReason = "stop";
    }

    /** 喂入上游 SSE 文本块（可含多行/半行），返回应下发的 OpenAI SSE 文本（可能为空）。 */
    public static String feed(ApiFormat upstream, String chunk, State st) {
        return feedBytes(upstream, chunk.getBytes(StandardCharsets.UTF_8), st);
    }

    /**
     * 喂入上游 SSE 原始字节块（可含多行/半行/多字节字符跨块），返回应下发的 OpenAI SSE 文本（可能为空）。
     * <p>SSE 行分隔符 {@code \n} 是单字节 ASCII，在字节层切行安全；多字节 UTF-8 字符只可能落在行内部，
     * 整行字节凑齐再 decode 杜绝跨 DataBuffer 边界的乱码（评审缺口4）。
     */
    public static String feedBytes(ApiFormat upstream, byte[] chunk, State st) {
        if (chunk == null || chunk.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (byte b : chunk) {
            if (b == '\n') {
                boolean overflow = st.lineOverflow;
                String line = overflow ? null : new String(st.lineBuf.toByteArray(), StandardCharsets.UTF_8).trim();
                st.lineBuf.reset();
                st.lineOverflow = false;
                if (!overflow) {
                    processLine(upstream, line, st, out);   // 超限行整行丢弃·不解析半截脏数据
                }
            } else if (!st.lineOverflow) {
                if (st.lineBuf.size() >= MAX_LINE_BYTES) {
                    // 单行累积超上限：丢弃本行已缓冲字节并进入丢弃模式（剩余字节直到 \n 全忽略·防无界堆积·评审缺口5）
                    st.lineBuf.reset();
                    st.lineOverflow = true;
                } else {
                    st.lineBuf.write(b);
                }
            }
        }
        return out.toString();
    }

    /** 流末：输出 finish_reason + usage 块 + [DONE]。 */
    public static String finish(State st) {
        ObjectNode chunk = baseChunk(st);
        ObjectNode c0 = (ObjectNode) chunk.path("choices").get(0);
        c0.putObject("delta");
        c0.put("finish_reason", st.sawToolCall && "stop".equals(st.finishReason) ? "tool_calls" : st.finishReason);
        ObjectNode usage = chunk.putObject("usage");
        usage.put("prompt_tokens", st.promptTokens);
        usage.put("completion_tokens", st.completionTokens);
        usage.put("total_tokens", st.promptTokens + st.completionTokens);
        if (st.cachedTokens > 0) {
            usage.putObject("prompt_tokens_details").put("cached_tokens", st.cachedTokens);   // 缓存命中明细回吐客户端
        }
        return "data: " + JsonUtils.toJson(chunk) + "\n\n" + "data: [DONE]\n\n";
    }

    private static void processLine(ApiFormat upstream, String line, State st, StringBuilder out) {
        if (!line.startsWith("data:")) {
            return;   // Anthropic 的 event: 行/空行忽略
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        JsonNode n;
        try {
            n = JsonUtils.readTree(payload);
        } catch (Exception e) {
            return;
        }
        if (upstream == ApiFormat.ANTHROPIC) {
            processAnthropic(n, st, out);
        } else if (upstream == ApiFormat.GEMINI) {
            processGemini(n, st, out);
        }
    }

    private static void processAnthropic(JsonNode n, State st, StringBuilder out) {
        switch (n.path("type").asText("")) {
            case "message_start" -> {
                st.model = n.path("message").path("model").asText(st.model);
                // 口径与 UsageExtractor 一致：Anthropic input_tokens 不含 cache_read/cache_creation，二者都补进 prompt 口径
                // （防流式转码漏计缓存 token），并分别记 cached/creation 供计费按缓存折扣价计。
                JsonNode usage = n.path("message").path("usage");
                int input = usage.path("input_tokens").asInt(0);
                int cr = usage.path("cache_read_input_tokens").asInt(0);
                int cc = usage.path("cache_creation_input_tokens").asInt(0);
                st.promptTokens = input + cr + cc;
                st.cachedTokens = cr;
                st.cacheCreationTokens = cc;
            }
            case "content_block_start" -> {
                JsonNode cb = n.path("content_block");
                if ("tool_use".equals(cb.path("type").asText(""))) {
                    int blockIdx = n.path("index").asInt();
                    int toolIdx = st.toolIndexCounter++;
                    st.blockToToolIndex.put(blockIdx, toolIdx);
                    st.sawToolCall = true;
                    emitToolStart(st, toolIdx, cb.path("id").asText(""), cb.path("name").asText(""), out);
                }
            }
            case "content_block_delta" -> {
                JsonNode delta = n.path("delta");
                String dtype = delta.path("type").asText("");
                if ("input_json_delta".equals(dtype)) {
                    Integer toolIdx = st.blockToToolIndex.get(n.path("index").asInt());
                    if (toolIdx != null) {
                        emitToolArgs(st, toolIdx, delta.path("partial_json").asText(""), out);
                    }
                } else if ("thinking_delta".equals(dtype)) {
                    emitReasoning(st, delta.path("thinking").asText(""), out);   // 思维链→OpenAI delta.reasoning_content
                } else if ("signature_delta".equals(dtype)) {
                    // thinking 签名块无 OpenAI 对应：忽略
                } else {
                    String text = delta.path("text").asText("");   // text_delta
                    emitText(st, text, out);
                }
            }
            case "message_delta" -> {
                st.completionTokens = n.path("usage").path("output_tokens").asInt(st.completionTokens);
                String sr = n.path("delta").path("stop_reason").asText("");
                if (!sr.isEmpty()) {
                    st.finishReason = mapAnthropicStop(sr);
                }
            }
            default -> { }
        }
    }

    private static void processGemini(JsonNode n, State st, StringBuilder out) {
        if (n.has("modelVersion")) {
            st.model = n.path("modelVersion").asText(st.model);
        }
        JsonNode um = n.path("usageMetadata");
        if (um.has("promptTokenCount")) {
            st.promptTokens = um.path("promptTokenCount").asInt(st.promptTokens);   // Gemini prompt 已含 cached
        }
        if (um.has("candidatesTokenCount") || um.has("thoughtsTokenCount")) {
            // 🔴 思维链 thoughtsTokenCount 并入 completion（Gemini 2.5·Google 按输出价计费）；usage 为累计值，末块覆盖
            st.completionTokens = um.path("candidatesTokenCount").asInt(0) + um.path("thoughtsTokenCount").asInt(0);
        }
        if (um.has("cachedContentTokenCount")) {
            st.cachedTokens = um.path("cachedContentTokenCount").asInt(st.cachedTokens);
        }
        JsonNode cand = n.path("candidates").path(0);
        String fr = cand.path("finishReason").asText("");
        if (!fr.isEmpty()) {
            // 与非流式 Gemini 转换共用同一映射：MAX_TOKENS→length、SAFETY/RECITATION→content_filter、STOP/未知→stop
            st.finishReason = GeminiChatTransformer.mapFinish(fr);
        }
        JsonNode parts = cand.path("content").path("parts");
        if (!parts.isArray()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode p : parts) {
            if (p.has("functionCall")) {
                // Gemini 不分片 JSON：一次性开块 + 整段 arguments（生成 call id）
                JsonNode fc = p.path("functionCall");
                int toolIdx = st.toolIndexCounter++;
                st.sawToolCall = true;
                String callId = "call_" + Long.toHexString(System.nanoTime()) + toolIdx;
                emitToolStart(st, toolIdx, callId, fc.path("name").asText(""), out);
                JsonNode args = fc.path("args");
                emitToolArgs(st, toolIdx, args.isMissingNode() ? "{}" : JsonUtils.toJson(args), out);
            } else if (p.path("thought").asBoolean(false)) {
                emitReasoning(st, p.path("text").asText(""), out);   // Gemini thought part→reasoning_content
            } else if (p.has("text")) {
                text.append(p.path("text").asText(""));
            }
        }
        emitText(st, text.toString(), out);
    }

    private static void emitText(State st, String text, StringBuilder out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ObjectNode chunk = baseChunk(st);
        ((ObjectNode) chunk.path("choices").get(0)).putObject("delta").put("content", text);
        out.append("data: ").append(JsonUtils.toJson(chunk)).append("\n\n");
    }

    /**
     * 思维链增量 delta：{@code {choices:[{delta:{reasoning_content:<text>}}]}}（DeepSeek/OpenRouter 事实标准）。
     * 空串增量过滤（首个空 reasoning 不发）。
     */
    private static void emitReasoning(State st, String text, StringBuilder out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ObjectNode chunk = baseChunk(st);
        ((ObjectNode) chunk.path("choices").get(0)).putObject("delta").put("reasoning_content", text);
        out.append("data: ").append(JsonUtils.toJson(chunk)).append("\n\n");
    }

    /** tool_call 开块 delta：{tool_calls:[{index,id,type:function,function:{name,arguments:""}}]}。 */
    private static void emitToolStart(State st, int toolIdx, String id, String name, StringBuilder out) {
        ObjectNode chunk = baseChunk(st);
        ObjectNode delta = ((ObjectNode) chunk.path("choices").get(0)).putObject("delta");
        ArrayNode tcs = delta.putArray("tool_calls");
        ObjectNode tc = tcs.addObject();
        tc.put("index", toolIdx);
        if (!id.isEmpty()) {
            tc.put("id", id);
        }
        tc.put("type", "function");
        ObjectNode fn = tc.putObject("function");
        fn.put("name", name);
        fn.put("arguments", "");
        out.append("data: ").append(JsonUtils.toJson(chunk)).append("\n\n");
    }

    /** tool_call 参数增量 delta：{tool_calls:[{index,function:{arguments:<partial>}}]}。 */
    private static void emitToolArgs(State st, int toolIdx, String partial, StringBuilder out) {
        if (partial == null || partial.isEmpty()) {
            return;
        }
        ObjectNode chunk = baseChunk(st);
        ObjectNode delta = ((ObjectNode) chunk.path("choices").get(0)).putObject("delta");
        ArrayNode tcs = delta.putArray("tool_calls");
        ObjectNode tc = tcs.addObject();
        tc.put("index", toolIdx);
        tc.putObject("function").put("arguments", partial);
        out.append("data: ").append(JsonUtils.toJson(chunk)).append("\n\n");
    }

    private static String mapAnthropicStop(String stopReason) {
        return switch (stopReason) {
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> "stop";   // end_turn / stop_sequence
        };
    }

    private static ObjectNode baseChunk(State st) {
        ObjectNode chunk = JsonUtils.mapper().createObjectNode();
        chunk.put("id", st.id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", st.model);
        ArrayNode choices = chunk.putArray("choices");
        ObjectNode c0 = choices.addObject();
        c0.put("index", 0);
        return chunk;
    }
}
