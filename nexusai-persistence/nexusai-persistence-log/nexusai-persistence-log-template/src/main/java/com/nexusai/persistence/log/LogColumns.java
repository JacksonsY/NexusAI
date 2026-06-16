package com.nexusai.persistence.log;

import java.util.Set;

/**
 * 日志聚合维度列名白名单（SQL 注入防御纵深）。
 * <p>{@link RequestLogStore} 的 {@code statsByDimension/perfByDimension/perfDaily/healthByDimension/
 * healthTimeline/perfTimeline/aggregateBy} 等方法把 {@code column} 直接拼进 SQL（GROUP BY/SELECT 子句·
 * 无法参数化列名）。调用方虽已用固定映射保证白名单，但 SPI 为 public——本类在 driver 层做强制校验兜底，
 * 即便未来某调用方漏校验也不让任意串进入 SQL。
 *
 * @author nexusai
 */
public final class LogColumns {

    /** 允许作为聚合/分组维度的物理列名（nai_request_log 实际列）。 */
    private static final Set<String> ALLOWED = Set.of(
            "channel_id", "model", "key_id", "project_code", "api_format", "workspace_id");

    private LogColumns() {
    }

    /** 校验列名在白名单内，否则抛异常（拒绝任意串拼入 SQL）。返回原列名以便链式使用。 */
    public static String require(String column) {
        if (column == null || !ALLOWED.contains(column)) {
            throw new IllegalArgumentException("非法的聚合维度列名: " + column);
        }
        return column;
    }

    /** 同 {@link #require}，但允许 null（用于可选的 subColumn）。 */
    public static String requireNullable(String column) {
        return column == null ? null : require(column);
    }
}
