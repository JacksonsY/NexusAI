package com.nexusai.relay.usage;

/**
 * 用量（归一）：prompt/completion/total + cached（缓存读命中）+ cacheCreation（缓存创建写入·Anthropic
 * cache_creation_input_tokens 口径·OpenAI 无此概念恒 0）。结构化计费用。
 *
 * @author nexusai
 */
public record Usage(int promptTokens, int completionTokens, int totalTokens, int cachedTokens,
                    int cacheCreationTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0, 0);

    public Usage(int promptTokens, int completionTokens, int totalTokens, int cachedTokens) {
        this(promptTokens, completionTokens, totalTokens, cachedTokens, 0);
    }

    public boolean isZero() {
        return totalTokens == 0 && promptTokens == 0 && completionTokens == 0
                && cachedTokens == 0 && cacheCreationTokens == 0;
    }
}
