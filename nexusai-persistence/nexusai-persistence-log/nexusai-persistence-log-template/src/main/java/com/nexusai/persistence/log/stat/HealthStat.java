package com.nexusai.persistence.log.stat;

import java.time.LocalDateTime;

/**
 * 健康监控维度聚合行（健康监控页·用量分解 + 吞吐口径）：
 * 按维度（协议经渠道合并 / 模型 / 渠道，可带二级维度渠道×模型）统计请求量、成功量、
 * 平均时延、平均 TTFT、吞吐计数器（成功行输出 tokens/生成耗时）、关联渠道/凭证数与最近事件时间。
 * <p>成功口径全站统一 {@code http_status < 400}；avgTtftMs 仅 {@code ttft_ms > 0} 样本（对齐 {@link PerfSql#AVG_TTFT}）；
 * tokensCount/genMsSum 仅成功行（TPS = tokens × 1000 / genMs）。
 *
 * @param key          一级维度键（channel_id 串或 model 名）
 * @param subKey       二级维度键（渠道×模型查询时为 model 名，单维查询为 null）
 * @param total        请求总量
 * @param success      成功量（http_status &lt; 400）
 * @param avgDurationMs 平均全程时延 ms（含失败行·无样本为 null）
 * @param avgTtftMs    平均首包 ms（仅 ttft&gt;0 样本，无样本为 null）
 * @param tokensCount  成功行输出 token 合计（TPS 分子）
 * @param genMsSum     成功行生成耗时合计 ms（TPS 分母·流式去 TTFT 段）
 * @param channelCount 关联渠道数（distinct channel_id·模型维度=该模型跑过几个渠道）
 * @param keyCount     关联上游凭证数（distinct key_id）
 * @param lastEventAt  最近一次请求时间
 * @author nexusai
 */
public record HealthStat(String key, String subKey, long total, long success,
                         Long avgDurationMs, Long avgTtftMs,
                         long tokensCount, long genMsSum,
                         long channelCount, long keyCount, LocalDateTime lastEventAt) {

    /** 吞吐（tokens/second·分母 0 → 0）。 */
    public double throughput() {
        return genMsSum > 0 ? tokensCount * 1000.0 / genMsSum : 0D;
    }
}
