package com.nexusai.relay.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.util.JsonUtils;
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

    private TaskHttp() {
    }

    /** 取 Authorization 头（sk- Bearer）。 */
    public static String authHeader(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER);
    }

    /** 响应式收全请求体为字符串（空体回 {}）。 */
    public static Mono<String> body(ServerWebExchange exchange) {
        return exchange.getRequest().getBody()
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .collectList()
                .map(parts -> String.join("", parts))
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
