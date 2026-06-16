package com.nexusai.protocol.transform;

import com.nexusai.common.enums.ApiFormat;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式跨协议转码补 cache token（#6）单测：Anthropic 流头 cache_read/cache_creation 折进 prompt 口径并回吐 cached_tokens，
 * 杜绝 Anthropic→OpenAI 流式转码漏计缓存 token 的资金口径偏差。
 */
class OpenAiSseConverterTest {

    @Test
    void anthropicStreamFoldsCacheTokensIntoPromptAndEmitsCached() {
        OpenAiSseConverter.State st = new OpenAiSseConverter.State();
        String start = "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-3-5-sonnet\","
                + "\"usage\":{\"input_tokens\":400,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":20}}}\n";
        OpenAiSseConverter.feed(ApiFormat.ANTHROPIC, start, st);

        assertThat(st.promptTokens).as("input + cache_read + cache_creation 折进 prompt 口径（与 UsageExtractor 一致）").isEqualTo(520);
        assertThat(st.cachedTokens).isEqualTo(100);
        assertThat(st.cacheCreationTokens).isEqualTo(20);

        String delta = "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":50},\"delta\":{\"stop_reason\":\"end_turn\"}}\n";
        OpenAiSseConverter.feed(ApiFormat.ANTHROPIC, delta, st);
        assertThat(st.completionTokens).isEqualTo(50);

        String finish = OpenAiSseConverter.finish(st);
        assertThat(finish).contains("\"prompt_tokens\":520");
        assertThat(finish).contains("\"completion_tokens\":50");
        assertThat(finish).contains("\"cached_tokens\":100");   // 缓存命中回吐客户端
        assertThat(finish).contains("[DONE]");
    }

    @Test
    void geminiStreamCapturesCachedContentTokens() {
        OpenAiSseConverter.State st = new OpenAiSseConverter.State();
        String chunk = "data: {\"modelVersion\":\"gemini-2.0\",\"usageMetadata\":{\"promptTokenCount\":300,"
                + "\"candidatesTokenCount\":80,\"cachedContentTokenCount\":120},"
                + "\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n";
        OpenAiSseConverter.feed(ApiFormat.GEMINI, chunk, st);

        assertThat(st.promptTokens).as("Gemini prompt 已含 cached·不二次折叠").isEqualTo(300);
        assertThat(st.cachedTokens).isEqualTo(120);
        assertThat(st.completionTokens).isEqualTo(80);
    }

    /** 缺口3：Anthropic thinking_delta → OpenAI delta.reasoning_content（思维链不丢弃）。 */
    @Test
    void anthropicThinkingDeltaMapsToReasoningContent() {
        OpenAiSseConverter.State st = new OpenAiSseConverter.State();
        String think = "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"让我想想\"}}\n";
        String out = OpenAiSseConverter.feed(ApiFormat.ANTHROPIC, think, st);
        assertThat(out).contains("\"reasoning_content\":\"让我想想\"");
        assertThat(out).doesNotContain("\"content\":");   // reasoning 不混入正文 content

        // signature_delta 无 OpenAI 对应：忽略不产出
        String sig = "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc\"}}\n";
        assertThat(OpenAiSseConverter.feed(ApiFormat.ANTHROPIC, sig, st)).isEmpty();
    }

    /** 缺口4：多字节 UTF-8 字符在任意字节切点跨 chunk 都不乱码（整行字节凑齐才 decode）。 */
    @Test
    void multiByteUtf8SplitAcrossChunksNoCorruption() {
        String line = "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好世界🌍\"}}\n";
        byte[] all = line.getBytes(StandardCharsets.UTF_8);
        // 穷举每个字节切点切两段分别喂入：无论切点是否落在多字节字符中间，都不得乱码
        for (int split = 1; split < all.length; split++) {
            OpenAiSseConverter.State st = new OpenAiSseConverter.State();
            String out = OpenAiSseConverter.feedBytes(ApiFormat.ANTHROPIC, Arrays.copyOfRange(all, 0, split), st)
                    + OpenAiSseConverter.feedBytes(ApiFormat.ANTHROPIC, Arrays.copyOfRange(all, split, all.length), st);
            assertThat(out).as("split=" + split).contains("你好世界🌍").doesNotContain("�");
        }
    }
}
