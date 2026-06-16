package com.nexusai.persistence.log;

import java.util.List;

/**
 * 请求日志分页结果（存储无关）。
 *
 * @param records  当前页记录
 * @param total    总条数
 * @param pageNum  当前页码
 * @param pageSize 每页条数
 * @author nexusai
 */
public record RequestLogPage(List<RequestLogRecord> records, long total, long pageNum, long pageSize) {
}
