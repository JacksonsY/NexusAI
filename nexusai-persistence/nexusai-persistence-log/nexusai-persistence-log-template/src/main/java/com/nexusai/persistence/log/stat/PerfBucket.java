package com.nexusai.persistence.log.stat;

/**
 * 性能时间线分桶聚合行（健康详情钻取·七计数器逐桶曲线）：
 * 把 [since, until] 均分 segments 段，按单维度（{@code column = value} 过滤·key 为 null）或
 * 按渠道分组（性能分析页多曲线·key=channel_id 串）聚合每段的
 * 量/成功/延迟和/TTFT 和+样本数/成功行输出 tokens/成功行生成耗时——Java 端算派生值
 * （avgTps / avgLatency / avgTtft / successRate）。
 *
 * @param key         分组键（渠道分组时序为 channel_id 串·单维过滤查询为 null）
 * @param segmentIdx  段号 [0, segments)（落在 until 边界的事件钳到最后一段）
 * @param total       该段请求总量
 * @param success     该段成功量（http_status &lt; 400）
 * @param latencySum  该段全程时延合计 ms（含失败行）
 * @param ttftSum     该段 TTFT 合计 ms（仅 ttft&gt;0 行）
 * @param ttftCount   该段 TTFT 样本数
 * @param tokensCount 该段成功行输出 token 合计（TPS 分子）
 * @param genMsSum    该段成功行生成耗时合计 ms（TPS 分母·流式去 TTFT 段）
 * @author nexusai
 */
public record PerfBucket(String key, int segmentIdx, long total, long success,
                         long latencySum, long ttftSum, long ttftCount,
                         long tokensCount, long genMsSum) {
}
