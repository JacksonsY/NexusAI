package com.nexusai.protocol.canonical;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical 工具调用（中枢中立）：历史 assistant 消息发起的一次工具调用，承载多轮工具对话的请求侧。
 * {@code arguments} 用 {@link JsonNode} 对象保存，序列化时各家自适应——OpenAI {@code function.arguments}
 * 为 JSON 字符串、Anthropic {@code tool_use.input} 为对象、Gemini {@code functionCall.args} 为对象。
 *
 * @param id        调用 id（OpenAI/Anthropic 原生有；Gemini 无故合成 {@code call_N}）
 * @param name      工具/函数名
 * @param arguments 调用参数（JSON 对象节点）
 * @author nexusai
 */
public record CanonicalToolCall(String id, String name, JsonNode arguments) {
}
