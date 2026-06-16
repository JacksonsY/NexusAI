package com.nexusai.relay.filter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.protocol.passthrough.ChatRequestPeek;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.runtime.ModuleToggles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;

/**
 * ②.3 模型后缀参数（peek 之后、别名归一之前）：
 * 模型名 {@code gpt-4o#temperature=0.7,top_p=0.9} → 剥离后缀得真实模型名 + 指令注入请求体顶层参数
 * （数字/布尔/字符串自动判型；不覆盖请求体已显式携带的同名参数）。
 * 模块开关 {@code module.model_directives.enabled}（默认关·5s 热生效）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelDirectiveFilter implements RelayFilter {

    private static final char SEPARATOR = '#';

    private final ModuleToggles moduleToggles;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        ChatRequestPeek peek = ctx.getPeek();
        if (peek == null || peek.model() == null) {
            return;
        }
        int sep = peek.model().indexOf(SEPARATOR);
        if (sep <= 0) {
            return;
        }
        if (!moduleToggles.enabled("model_directives", false)) {
            return;
        }
        String realModel = peek.model().substring(0, sep);
        String directives = peek.model().substring(sep + 1);

        ObjectNode root = (ObjectNode) JsonUtils.readTree(ctx.getBody());
        if (root == null) {
            return;
        }
        root.put("model", realModel);
        for (String pair : directives.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty() || root.has(key)) {
                continue;   // 请求体显式参数优先，指令不覆盖
            }
            putTyped(root, key, value);
        }
        ctx.setBody(JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8));
        ctx.setPeek(new ChatRequestPeek(realModel, peek.stream(), peek.includeUsage(), peek.maxTokens()));
        log.debug("模型后缀参数生效: {} -> {} [{}]", peek.model(), realModel, directives);
    }

    private static void putTyped(ObjectNode root, String key, String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            root.put(key, Boolean.parseBoolean(value));
            return;
        }
        try {
            if (value.contains(".")) {
                root.put(key, Double.parseDouble(value));
            } else {
                root.put(key, Long.parseLong(value));
            }
        } catch (NumberFormatException e) {
            root.put(key, value);
        }
    }

    @Override
    public int getOrder() {
        return ORDER_PEEK + 3;
    }
}
