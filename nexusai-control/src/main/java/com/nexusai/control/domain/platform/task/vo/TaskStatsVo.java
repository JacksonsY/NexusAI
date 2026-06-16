package com.nexusai.control.domain.platform.task.vo;

import java.util.Map;

/**
 * 异步任务状态聚合（顶部统计卡：总数/处理中/已完成/已失败）。
 * <p>组件名/类型与前端 {@code NexusAI.TaskStats} 严格对齐，序列化 JSON 字节级一致；
 * {@code byStatus} 为按 status 字符串动态分组的计数（对应前端 {@code Record<string, number|string>}）。
 *
 * @author nexusai
 */
public record TaskStatsVo(long total, long running, long succeeded, long failed,
                          Map<String, Long> byStatus) {
}
