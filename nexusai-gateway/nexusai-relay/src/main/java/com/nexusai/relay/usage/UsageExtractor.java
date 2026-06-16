package com.nexusai.relay.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.util.JsonUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从上游响应提取 usage（流末提 usage·强制 include_usage 生死线）：
 * 非流式从 JSON 体取；流式从 SSE 尾部最后一个含 usage 的 data 块取。
 *
 * @author nexusai
 */
public final class UsageExtractor {

    private UsageExtractor() {
    }

    /**
     * SSE 增量 usage 状态。流式上游的 JSON data 行可能被 Netty 任意切块，必须跨 chunk 拼行后解析；
     * 仅扫尾窗会漏掉 Anthropic {@code message_start} 里的 prompt usage。
     */
    public static final class SseUsageState {
        /** 跨 chunk 的半行原始字节缓存（整行才 UTF-8 decode·防多字节跨块乱码·评审缺口4） */
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private Usage headUsage;
        private Usage tailUsage = Usage.ZERO;

        public Usage usage() {
            return mergeHeadAndTail(tailUsage, headUsage);
        }
    }

    /** 非流式：整段 JSON 响应取 usage（OpenAI usage / Anthropic usage / Gemini usageMetadata 自适应） */
    public static Usage fromJsonBody(byte[] body) {
        if (body == null || body.length == 0) {
            return Usage.ZERO;
        }
        try {
            JsonNode root = JsonUtils.readTree(body);
            if (root.has("usageMetadata")) {        // Gemini
                return fromGeminiMeta(root.get("usageMetadata"));
            }
            return fromUsageNode(root.get("usage"));   // OpenAI / Anthropic
        } catch (Exception e) {
            return Usage.ZERO;
        }
    }

    /** 流式：扫描 SSE 文本尾部最后一个 data 行的 usage */
    public static Usage fromSseTail(String tail) {
        if (tail == null || tail.isBlank()) {
            return Usage.ZERO;
        }
        String[] lines = tail.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                JsonNode node = JsonUtils.readTree(payload);
                JsonNode usage = node.get("usage");
                if (usage != null && !usage.isNull()) {
                    return fromUsageNode(usage);
                }
            } catch (Exception ignore) {
                // 非完整 JSON 行，继续向上找
            }
        }
        return Usage.ZERO;
    }

    /**
     * Anthropic 流式头部 usage 捕获（评审 H4）：Anthropic 流的 prompt(input_tokens) 在头部 message_start、
     * completion(output_tokens) 在尾部 message_delta，{@link #fromSseTail} 只读尾窗会漏计 prompt。
     * 本方法从流头单独捕获 prompt 侧。
     *
     * @return prompt 侧 Usage；本块非 message_start 或无 usage 时返回 {@code null}
     */
    public static Usage anthropicHeadUsage(String chunk) {
        if (chunk == null || !chunk.contains("message_start")) {
            return null;
        }
        for (String line : chunk.split("\n")) {
            String l = line.trim();
            if (!l.startsWith("data:")) {
                continue;
            }
            String payload = l.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                JsonNode node = JsonUtils.readTree(payload);
                if (!"message_start".equals(node.path("type").asText())) {
                    continue;
                }
                JsonNode usage = node.path("message").path("usage");
                if (usage.isMissingNode() || usage.isNull()) {
                    continue;
                }
                Usage u = fromUsageNode(usage);
                if (u.promptTokens() > 0) {
                    return u;
                }
            } catch (Exception ignore) {
                // 非完整 JSON 行
            }
        }
        return null;
    }

    /**
     * 流式头尾合并（评审 H4）：尾窗取 completion，头部 {@code head} 补 prompt（Anthropic 头尾分离场景）。
     * OpenAI 流式尾块已含完整 usage（head=null 或 tail.prompt&gt;0），原样返回尾窗结果。
     */
    public static Usage fromSseTailWithHead(String tail, Usage head) {
        return mergeHeadAndTail(fromSseTail(tail), head);
    }

    /** 流式：按字符串 chunk 增量喂入（内部转字节处理）。 */
    public static void feedSse(String chunk, SseUsageState state) {
        if (chunk == null || chunk.isEmpty() || state == null) {
            return;
        }
        feedSseBytes(chunk.getBytes(StandardCharsets.UTF_8), state);
    }

    /**
     * 流式：按原始字节 chunk 增量喂入，跨 chunk 拼行后解析。
     * <p>{@code \n}/{@code \r} 行分隔符为单字节 ASCII，字节层切行安全；多字节 UTF-8 字符只落行内部，
     * 整行字节凑齐再 decode 杜绝跨 DataBuffer 边界乱码（评审缺口4）。{@code \r\n} 产生的空行经 {@link #feedSseLine} 忽略。
     */
    public static void feedSseBytes(byte[] chunk, SseUsageState state) {
        if (chunk == null || chunk.length == 0 || state == null) {
            return;
        }
        for (byte b : chunk) {
            if (b == '\n' || b == '\r') {
                feedSseLine(new String(state.lineBuffer.toByteArray(), StandardCharsets.UTF_8), state);
                state.lineBuffer.reset();
            } else {
                state.lineBuffer.write(b);
            }
        }
    }

    /** 流末取最终 usage；若最后一行没有换行，也会尝试解析残留行。 */
    public static Usage finishSse(SseUsageState state) {
        if (state == null) {
            return Usage.ZERO;
        }
        if (state.lineBuffer.size() > 0) {
            feedSseLine(new String(state.lineBuffer.toByteArray(), StandardCharsets.UTF_8), state);
            state.lineBuffer.reset();
        }
        return state.usage();
    }

    private static void feedSseLine(String rawLine, SseUsageState state) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        try {
            JsonNode node = JsonUtils.readTree(payload);
            if ("message_start".equals(node.path("type").asText())) {
                JsonNode usage = node.path("message").path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    Usage parsed = fromUsageNode(usage);
                    if (parsed.promptTokens() > 0) {
                        state.headUsage = parsed;
                    }
                }
            }
            JsonNode usage = node.get("usage");
            if (usage != null && !usage.isNull()) {
                state.tailUsage = fromUsageNode(usage);
            }
            JsonNode geminiMeta = node.get("usageMetadata");
            if (geminiMeta != null && !geminiMeta.isNull()) {
                state.tailUsage = fromGeminiMeta(geminiMeta);
            }
        } catch (Exception ignore) {
            // 残缺/非 JSON data 行不参与 usage 统计。
        }
    }

    private static Usage mergeHeadAndTail(Usage tailUsage, Usage head) {
        Usage tail = tailUsage == null ? Usage.ZERO : tailUsage;
        if (head != null && tail.promptTokens() == 0) {
            int prompt = head.promptTokens();
            int completion = tail.completionTokens();
            return new Usage(prompt, completion, prompt + completion, head.cachedTokens(), head.cacheCreationTokens());
        }
        if (tail.isZero() && head != null) {
            return head;
        }
        return tail;
    }

    private static Usage fromUsageNode(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return Usage.ZERO;
        }
        // OpenAI: prompt_tokens/completion_tokens；Anthropic: input_tokens/output_tokens（自适应）
        // 注：跨厂商 token 口径归一即在此处一次完成，是<b>唯一权威</b>——
        // 原 billing.TokenNormalizer 与此重复且无人调用，已删（若接线会与下方 cache 补算双重计数）。
        int prompt = usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(0));
        int completion = usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(0));
        int total = usage.path("total_tokens").asInt(prompt + completion);
        // cached：OpenAI prompt_tokens_details.cached_tokens；Anthropic cache_read_input_tokens
        int cached = usage.path("prompt_tokens_details").path("cached_tokens")
                .asInt(usage.path("cache_read_input_tokens").asInt(0));
        // creation：Anthropic cache_creation_input_tokens（缓存创建写入·计费按 writeCachedRate）
        int creation = usage.path("cache_creation_input_tokens").asInt(0);
        // Anthropic：input_tokens 不含 cache_read 也不含 cache_creation，二者都要补进 prompt 口径（评审 MEDIUM·防漏计）
        if (!usage.has("prompt_tokens") && (usage.has("cache_read_input_tokens") || usage.has("cache_creation_input_tokens"))) {
            prompt += cached + creation;
            total = prompt + completion;
        }
        return new Usage(prompt, completion, total, cached, creation);
    }

    /** Gemini usageMetadata: promptTokenCount/candidatesTokenCount/totalTokenCount/cachedContentTokenCount */
    private static Usage fromGeminiMeta(JsonNode meta) {
        if (meta == null || meta.isNull()) {
            return Usage.ZERO;
        }
        int prompt = meta.path("promptTokenCount").asInt(0);
        int completion = meta.path("candidatesTokenCount").asInt(0);
        int total = meta.path("totalTokenCount").asInt(prompt + completion);
        int cached = meta.path("cachedContentTokenCount").asInt(0);
        return new Usage(prompt, completion, total, cached);
    }
}
