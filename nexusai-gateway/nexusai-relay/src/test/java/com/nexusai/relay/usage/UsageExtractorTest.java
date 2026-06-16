package com.nexusai.relay.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageExtractorTest {

    @Test
    void statefulAnthropicUsageSurvivesChunkSplit() {
        UsageExtractor.SseUsageState state = new UsageExtractor.SseUsageState();

        UsageExtractor.feedSse("event: message_start\n", state);
        UsageExtractor.feedSse("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12,"
                + "\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2", state);
        UsageExtractor.feedSse("}}}\n\n", state);
        UsageExtractor.feedSse("""
                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":5}}

                """, state);

        Usage usage = UsageExtractor.finishSse(state);

        assertThat(usage.promptTokens()).isEqualTo(17);
        assertThat(usage.completionTokens()).isEqualTo(5);
        assertThat(usage.totalTokens()).isEqualTo(22);
        assertThat(usage.cachedTokens()).isEqualTo(3);
        assertThat(usage.cacheCreationTokens()).isEqualTo(2);
    }

    @Test
    void openAiTailUsageStillWinsWhenPresent() {
        UsageExtractor.SseUsageState state = new UsageExtractor.SseUsageState();

        UsageExtractor.feedSse("""
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14,"prompt_tokens_details":{"cached_tokens":6}}}
                """, state);

        Usage usage = UsageExtractor.finishSse(state);

        assertThat(usage.promptTokens()).isEqualTo(10);
        assertThat(usage.completionTokens()).isEqualTo(4);
        assertThat(usage.totalTokens()).isEqualTo(14);
        assertThat(usage.cachedTokens()).isEqualTo(6);
    }
}
