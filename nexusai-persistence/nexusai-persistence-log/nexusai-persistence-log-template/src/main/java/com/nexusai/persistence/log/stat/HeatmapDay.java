package com.nexusai.persistence.log.stat;

import java.math.BigDecimal;

/**
 * 活跃度热图单日聚合（365 天日历风格热图）：
 * 仅返回有数据的日期，零请求日由上层补齐。
 *
 * @param date        日期（yyyy-MM-dd）
 * @param requests    当日请求数
 * @param totalTokens 当日 token 合计
 * @param costUsd     当日扣减 USD 合计
 * @author nexusai
 */
public record HeatmapDay(String date, long requests, long totalTokens, BigDecimal costUsd) {
}
