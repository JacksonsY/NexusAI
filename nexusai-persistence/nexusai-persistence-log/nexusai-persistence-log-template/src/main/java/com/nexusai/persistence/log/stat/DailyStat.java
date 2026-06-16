package com.nexusai.persistence.log.stat;

/**
 * 日粒度时序（Dashboard「每日请求概览」面积图·去钱化）：日期（{@code yyyy-MM-dd}）+ 请求量/成功数 + token 三态。
 * <p>跨方言日期截断：mysql {@code date_format}、pg {@code to_char}、clickhouse {@code formatDateTime}。
 *
 * @author nexusai
 */
public record DailyStat(String date, long total, long success,
                        long inputTokens, long outputTokens, long cachedTokens) {
}
