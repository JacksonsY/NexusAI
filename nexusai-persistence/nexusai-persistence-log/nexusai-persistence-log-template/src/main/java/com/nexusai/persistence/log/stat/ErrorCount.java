package com.nexusai.persistence.log.stat;

/**
 * 错误分类计数行（性能分析页）：
 * 分类键 = {@code error_code} 非空取之，否则 {@code HTTP_<status>}；仅统计 {@code http_status >= 400} 行。
 *
 * @author nexusai
 */
public record ErrorCount(String category, long count) {
}
