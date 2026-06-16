package com.nexusai.control.domain.platform.module.vo;

/**
 * ChatTransformer SPI 描述符（代码式适配·只读·协议家族=代码的可视化）。
 * <p>组件名/类型与前端 {@code NexusAI.Modules.transformers} 元素（{transformer,format}）严格对齐，
 * 序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record TransformerDescriptorVo(String transformer, String format) {
}
