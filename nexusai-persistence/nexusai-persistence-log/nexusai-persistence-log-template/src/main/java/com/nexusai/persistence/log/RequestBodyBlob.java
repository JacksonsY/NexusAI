package com.nexusai.persistence.log;

import java.time.LocalDateTime;

/**
 * 请求/响应正文载体（{@link RequestBodyStore#load} 返回）。正文已解压为原始字节（driver 内部负责 gzip 编解码）。
 *
 * @param requestId    关联日志主表
 * @param requestBody  请求正文原文（可空）
 * @param responseBody 响应正文原文（可空）
 * @param createDt     落库时间
 * @author nexusai
 */
public record RequestBodyBlob(String requestId, byte[] requestBody, byte[] responseBody, LocalDateTime createDt) {
}
