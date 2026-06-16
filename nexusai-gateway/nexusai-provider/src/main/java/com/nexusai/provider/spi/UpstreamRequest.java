package com.nexusai.provider.spi;

import java.util.Map;

/**
 * Provider 适配输出：上游请求描述符（由 relay 用 Reactor Netty WebClient 实际执行）。
 * provider 不持 HTTP 客户端——只产描述符，保持纯净、可测、易切传输。
 *
 * @param method  HTTP 方法
 * @param url     完整上游 URL
 * @param headers 上游请求头（含已换好的鉴权头）
 * @param body    上游请求体（透传/改写后）
 * @author nexusai
 */
public record UpstreamRequest(String method, String url, Map<String, String> headers, byte[] body) {
}
