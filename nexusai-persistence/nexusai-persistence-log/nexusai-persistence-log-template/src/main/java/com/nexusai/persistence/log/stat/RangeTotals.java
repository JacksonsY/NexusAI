package com.nexusai.persistence.log.stat;

/**
 * 区间总量 + 成功数（Dashboard 环比计数与成功率口径）。
 * <p>口径：{@code total} = 区间内全部请求（含失败），{@code success} = http_status &lt; 400 的条数。
 *
 * @param total   区间内总请求数
 * @param success 区间内成功数（http_status &lt; 400）
 * @author nexusai
 */
public record RangeTotals(long total, long success) {

    public static final RangeTotals EMPTY = new RangeTotals(0L, 0L);
}
