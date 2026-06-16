package com.nexusai.control.domain.platform.ops.vo;

/**
 * 延迟直方图单桶（固定 6 桶补零）。
 * <p>组件名/类型与前端 {@code NexusAI.OpsLatencyBucket} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record OpsLatencyBucketVo(String bucket, long count) {
}
