package com.nexusai.relay.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.ClientIp;
import com.nexusai.common.util.JsonUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 异步任务入站 HTTP 小工具（MJ/Suno/Video/Kling/Jimeng 控制器共用·消除重复口径）：响应式读体、取 Authorization、
 * 从体取 model、取 ids 列表。
 *
 * @author nexusai
 */
public final class TaskHttp {

    /** 请求体聚合硬上限（64MB·与 {@code RelayOrchestrator} 同口径）：防未鉴权大体打爆堆内存（DoS）。 */
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    private TaskHttp() {
    }

    /** 取 Authorization 头（sk- Bearer）。 */
    public static String authHeader(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER);
    }

    /** 解析真实客户端 IP（经 {@link ClientIp}·仅信任可信代理 XFF·与 relay 主链 gate 同口径·防伪造绕过 IP 白名单）。 */
    public static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        var addr = exchange.getRequest().getRemoteAddress();
        String remote = addr == null || addr.getAddress() == null ? "" : addr.getAddress().getHostAddress();
        return ClientIp.resolve(remote, xff);
    }

    /** 响应式收全请求体为字符串（带上限·空体回 {}·超限 413）。 */
    public static Mono<String> body(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BODY_BYTES)
                .onErrorMap(DataBufferLimitException.class,
                        e -> GatewayException.of(GatewayErrorCode.PAYLOAD_TOO_LARGE, "请求体超过上限 " + MAX_BODY_BYTES + " 字节"))
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);   // 显式释放聚合 buffer（原 collectList 路径未释放·潜在泄漏）
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .defaultIfEmpty("{}");
    }

    /** 从 JSON 体取 {@code model}（缺省/解析失败回退给定默认值·用于计价与路由）。 */
    public static String modelOf(String body, String fallback) {
        try {
            JsonNode m = JsonUtils.readTree(body).path("model");
            if (m.isTextual() && !m.asText().isBlank()) {
                return m.asText();
            }
        } catch (Exception ignore) {
            // 体非 JSON / 缺字段 → 回退
        }
        return fallback;
    }

    /** 从 JSON 体取 {@code ids} 字符串数组（批量查询用·非法回空）。 */
    public static List<String> ids(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode node = JsonUtils.readTree(body).path("ids");
            if (node.isArray()) {
                node.forEach(n -> ids.add(n.asText()));
            }
        } catch (Exception ignore) {
            // 体非法 → 空
        }
        return ids;
    }
}
