package com.nexusai.persistence.log.stat;

/**
 * 按 HTTP 状态码分组计数。
 *
 * @param status HTTP 状态码（可为 null=未记录）
 * @param count  条数
 * @author nexusai
 */
public record StatusCount(Integer status, long count) {
}
