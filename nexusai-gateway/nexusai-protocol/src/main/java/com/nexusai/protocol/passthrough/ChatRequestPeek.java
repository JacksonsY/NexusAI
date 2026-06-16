package com.nexusai.protocol.passthrough;

/**
 * 透传直采的"最小解析"结果：
 * 只取路由/计量必需的 model、stream、maxTokens，不全量反序列化请求体。
 *
 * @param model         目标模型（路由 + 计量必需）
 * @param stream        是否流式
 * @param includeUsage  请求是否已带 stream_options.include_usage
 * @param maxTokens     声明的最大补全 token（freeze 预扣上界；0=未声明）
 * @author nexusai
 */
public record ChatRequestPeek(String model, boolean stream, boolean includeUsage, int maxTokens) {
}
