package com.nexusai.control.domain.platform.ops.vo;

/**
 * 错误分类分布单项（error_code / HTTP_xxx 分组）。
 * <p>组件名/类型与前端 {@code NexusAI.OpsErrorCategory} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record OpsErrorCategoryVo(String category, long count) {
}
