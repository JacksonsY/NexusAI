package com.nexusai.protocol.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口2：Canonical 承载 tool 调用历史 + 多模态块的跨协议互转单测。
 * 覆盖多轮工具对话配对（OpenAI→Anthropic 配对/交替/无悬空）、视觉请求（image_url dataURI→Anthropic source）、
 * 纯文本回归（of/text 行为不变）、Gemini 工具往返与合成 id。
 */
class CanonicalToolMultimodalTest {

    private final CanonicalConverter converter = new CanonicalConverter();

    /** OpenAI 多轮工具请求 → Canonical → Anthropic：tool_use/tool_result 配对、role 交替、无悬空。 */
    @Test
    void openAiMultiTurnToolRequestToAnthropicPairsAndAlternates() {
        String openai = """
                {"model":"gpt-4o","messages":[
                  {"role":"user","content":"东京天气如何？"},
                  {"role":"assistant","content":null,"tool_calls":[
                     {"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Tokyo\\"}"}}]},
                  {"role":"tool","tool_call_id":"call_abc","content":"晴 22°C"},
                  {"role":"user","content":"谢谢"}
                ]}""";
        byte[] out = converter.convertRequest(openai.getBytes(StandardCharsets.UTF_8), ApiFormat.OPENAI, ApiFormat.ANTHROPIC);
        JsonNode root = JsonUtils.readTree(out);
        JsonNode msgs = root.path("messages");

        // 严格交替：user(text) → assistant(tool_use) → user(tool_result + "谢谢" 合并)
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0).path("role").asText()).isEqualTo("user");
        assertThat(msgs.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(msgs.get(2).path("role").asText()).isEqualTo("user");
        assertAlternating(msgs);

        // assistant 的 tool_use 块
        JsonNode toolUse = msgs.get(1).path("content").get(0);
        assertThat(toolUse.path("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.path("id").asText()).isEqualTo("call_abc");
        assertThat(toolUse.path("name").asText()).isEqualTo("get_weather");
        assertThat(toolUse.path("input").path("city").asText()).isEqualTo("Tokyo");

        // 紧随的 user 携带配对 tool_result（合并后第一块），其后是 "谢谢" 文本块
        JsonNode mergedUser = msgs.get(2).path("content");
        JsonNode toolResult = mergedUser.get(0);
        assertThat(toolResult.path("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo("call_abc");
        assertThat(toolResult.path("content").asText()).isEqualTo("晴 22°C");
        assertThat(mergedUser.get(1).path("type").asText()).isEqualTo("text");
        assertThat(mergedUser.get(1).path("text").asText()).isEqualTo("谢谢");

        // 无悬空：每个 tool_use 都有对应 tool_result
        assertNoDanglingToolUse(root);
    }

    /** OpenAI 视觉请求（content 数组带 image_url dataURI）→ Anthropic：image source.media_type+data 正确。 */
    @Test
    void openAiVisionRequestToAnthropicImageSource() {
        String openai = """
                {"model":"gpt-4o","messages":[
                  {"role":"user","content":[
                     {"type":"text","text":"这是什么？"},
                     {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,/9j/ABCD"}}]}
                ]}""";
        byte[] out = converter.convertRequest(openai.getBytes(StandardCharsets.UTF_8), ApiFormat.OPENAI, ApiFormat.ANTHROPIC);
        JsonNode content = JsonUtils.readTree(out).path("messages").get(0).path("content");

        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("这是什么？");
        JsonNode image = content.get(1);
        assertThat(image.path("type").asText()).isEqualTo("image");
        assertThat(image.path("source").path("type").asText()).isEqualTo("base64");
        assertThat(image.path("source").path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(image.path("source").path("data").asText()).isEqualTo("/9j/ABCD");
    }

    /** Anthropic 视觉请求 → OpenAI：image source → image_url dataURI 还原。 */
    @Test
    void anthropicVisionRequestToOpenAiDataUri() {
        String anthropic = """
                {"model":"claude-3-5-sonnet","max_tokens":1024,"messages":[
                  {"role":"user","content":[
                     {"type":"text","text":"描述图片"},
                     {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo"}}]}
                ]}""";
        byte[] out = converter.convertRequest(anthropic.getBytes(StandardCharsets.UTF_8), ApiFormat.ANTHROPIC, ApiFormat.OPENAI);
        JsonNode content = JsonUtils.readTree(out).path("messages").get(0).path("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("text").asText()).isEqualTo("描述图片");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,iVBORw0KGgo");
    }

    /** 纯文本回归：of(role,String) + text() 行为不变（旧路径不破）。 */
    @Test
    void pureTextRegressionUnchanged() {
        String openai = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}";
        // OpenAI → Anthropic：仍是单 text 块
        byte[] toAnthropic = converter.convertRequest(openai.getBytes(StandardCharsets.UTF_8), ApiFormat.OPENAI, ApiFormat.ANTHROPIC);
        JsonNode ant = JsonUtils.readTree(toAnthropic);
        assertThat(ant.path("messages").get(0).path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(ant.path("messages").get(0).path("content").get(0).path("text").asText()).isEqualTo("你好");

        // OpenAI → Gemini → 回 OpenAI：纯文本 content 字符串（非数组）
        byte[] toGemini = converter.convertRequest(openai.getBytes(StandardCharsets.UTF_8), ApiFormat.OPENAI, ApiFormat.GEMINI);
        JsonNode gem = JsonUtils.readTree(toGemini);
        assertThat(gem.path("contents").get(0).path("parts").get(0).path("text").asText()).isEqualTo("你好");
        byte[] backOpenAi = converter.convertRequest(toGemini, ApiFormat.GEMINI, ApiFormat.OPENAI);
        assertThat(JsonUtils.readTree(backOpenAi).path("messages").get(0).path("content").asText()).isEqualTo("你好");
    }

    /** 悬空 tool_use（无对应结果）被丢弃，不致 Anthropic 400。 */
    @Test
    void danglingToolUseIsDropped() {
        String openai = """
                {"model":"gpt-4o","messages":[
                  {"role":"user","content":"调用两个工具"},
                  {"role":"assistant","content":null,"tool_calls":[
                     {"id":"call_1","type":"function","function":{"name":"f1","arguments":"{}"}},
                     {"id":"call_2","type":"function","function":{"name":"f2","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call_1","content":"r1"}
                ]}""";
        byte[] out = converter.convertRequest(openai.getBytes(StandardCharsets.UTF_8), ApiFormat.OPENAI, ApiFormat.ANTHROPIC);
        JsonNode root = JsonUtils.readTree(out);

        // call_2 悬空被丢，仅保留 call_1 的 tool_use + tool_result
        long toolUseCount = countBlocks(root, "tool_use");
        long toolResultCount = countBlocks(root, "tool_result");
        assertThat(toolUseCount).isEqualTo(1);
        assertThat(toolResultCount).isEqualTo(1);
        assertNoDanglingToolUse(root);
    }

    /** Gemini 工具往返：functionCall→canonical（合成 call_N）→ Gemini functionCall 名称保持。 */
    @Test
    void geminiToolCallRoundTripSynthesizesId() {
        String gemini = """
                {"contents":[
                  {"role":"user","parts":[{"text":"天气"}]},
                  {"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"Tokyo"}}}]},
                  {"role":"user","parts":[{"functionResponse":{"name":"get_weather","response":{"result":"晴"}}}]}
                ]}""";
        // Gemini → OpenAI：assistant.tool_calls + role=tool
        byte[] toOpenAi = converter.convertRequest(gemini.getBytes(StandardCharsets.UTF_8), ApiFormat.GEMINI, ApiFormat.OPENAI);
        JsonNode oa = JsonUtils.readTree(toOpenAi);
        JsonNode asst = oa.path("messages").get(1);
        assertThat(asst.path("role").asText()).isEqualTo("assistant");
        JsonNode call = asst.path("tool_calls").get(0);
        assertThat(call.path("id").asText()).startsWith("call_");
        assertThat(call.path("function").path("name").asText()).isEqualTo("get_weather");
        JsonNode toolMsg = oa.path("messages").get(2);
        assertThat(toolMsg.path("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.path("tool_call_id").asText()).startsWith("call_");
        assertThat(toolMsg.path("content").asText()).isEqualTo("晴");
    }

    /** OpenAI 多轮工具 → Gemini：functionCall + functionResponse 且 functionResponse.name 经 id 解析回填。 */
    @Test
    void openAiToolToGeminiResolvesFunctionResponseName() {
        String openai = """
                {"model":"gpt-4o","messages":[
                  {"role":"user","content":"天气"},
                  {"role":"assistant","content":null,"tool_calls":[
                     {"id":"call_x","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Tokyo\\"}"}}]},
                  {"role":"tool","tool_call_id":"call_x","content":"晴"}
                ]}""";
        byte[] out = converter.convertRequest(openai.getBytes(StandardCharsets.UTF_8), ApiFormat.OPENAI, ApiFormat.GEMINI);
        JsonNode contents = JsonUtils.readTree(out).path("contents");
        JsonNode fc = contents.get(1).path("parts").get(0).path("functionCall");
        assertThat(fc.path("name").asText()).isEqualTo("get_weather");
        JsonNode fr = contents.get(2).path("parts").get(0).path("functionResponse");
        // functionResponse.name 必须是函数名（经 toolCallId→name 索引回填），不是裸 call id
        assertThat(fr.path("name").asText()).isEqualTo("get_weather");
        assertThat(fr.path("response").path("result").asText()).isEqualTo("晴");
    }

    // —— 断言/统计辅助 ——

    /** 断言：每个 assistant tool_use 的 id 都能在紧随其后的 user tool_result 中找到（无悬空）。 */
    private static void assertNoDanglingToolUse(JsonNode anthropicRoot) {
        JsonNode msgs = anthropicRoot.path("messages");
        for (int i = 0; i < msgs.size(); i++) {
            JsonNode m = msgs.get(i);
            if (!"assistant".equals(m.path("role").asText())) {
                continue;
            }
            for (JsonNode block : m.path("content")) {
                if (!"tool_use".equals(block.path("type").asText())) {
                    continue;
                }
                String id = block.path("id").asText();
                JsonNode next = i + 1 < msgs.size() ? msgs.get(i + 1) : JsonUtils.mapper().createObjectNode();
                boolean matched = false;
                if ("user".equals(next.path("role").asText())) {
                    for (JsonNode rb : next.path("content")) {
                        if ("tool_result".equals(rb.path("type").asText())
                                && id.equals(rb.path("tool_use_id").asText())) {
                            matched = true;
                            break;
                        }
                    }
                }
                assertThat(matched).as("tool_use %s 须由紧随 user 的 tool_result 应答", id).isTrue();
            }
        }
    }

    /** 断言：Anthropic messages 的 user/assistant 角色严格交替（无相邻同角色）。 */
    private static void assertAlternating(JsonNode msgs) {
        String prev = null;
        for (JsonNode m : msgs) {
            String role = m.path("role").asText();
            assertThat(role).as("相邻消息角色不得相同（须交替）").isNotEqualTo(prev);
            prev = role;
        }
    }

    private static long countBlocks(JsonNode anthropicRoot, String type) {
        long count = 0;
        for (JsonNode m : anthropicRoot.path("messages")) {
            for (JsonNode block : m.path("content")) {
                if (type.equals(block.path("type").asText())) {
                    count++;
                }
            }
        }
        return count;
    }
}
