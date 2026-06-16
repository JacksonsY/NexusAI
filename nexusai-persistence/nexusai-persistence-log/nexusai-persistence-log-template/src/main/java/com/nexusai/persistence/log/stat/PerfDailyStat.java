package com.nexusai.persistence.log.stat;

/**
 * 日粒度维度性能时序（Dashboard「最佳模型/渠道性能」多曲线图）：
 * 日期（{@code yyyy-MM-dd}）+ 分组键 + 输出 token 总量 + 平均 TTFT（无首字样本记 0）+ 请求量 + 吞吐（tokens/second）。
 * <p>吞吐/TTFT 口径与 {@link PerfStat} 完全一致（同一组 SQL 片段 {@link PerfSql}），只是多按天分桶。
 *
 * @author nexusai
 */
public record PerfDailyStat(String date, String key, long tokensCount, long avgTtftMs,
                            long requestCount, double throughput) {
}
