package com.nexusai.relay.filter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.protocol.passthrough.ChatRequestPeek;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.router.model.ModelAliasResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;

/**
 * ②.5 模型别名归一（peek 之后、模型权限之前）：把请求模型名经 {@link ModelAliasResolver} 归一到目录
 * modelKey，并<b>同步改写请求体</b>——后续模型权限、freeze 计费、路由 ability、日志统计全部使用归一名。
 * 体不改写会导致出站 rewriteModelIfNeeded 在「无渠道映射」时跳过、把原始别名发给上游。
 * 停用模型在 resolver 内 403 截停。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class ModelAliasFilter implements RelayFilter {

    private final ModelAliasResolver aliasResolver;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        ChatRequestPeek peek = ctx.getPeek();
        if (peek == null || peek.model() == null || peek.model().isBlank()) {
            return;
        }
        String normalized = aliasResolver.resolve(peek.model());
        if (normalized == null || normalized.equals(peek.model())) {
            return;
        }
        ctx.setPeek(new ChatRequestPeek(normalized, peek.stream(), peek.includeUsage(), peek.maxTokens()));
        ObjectNode root = (ObjectNode) JsonUtils.readTree(ctx.getBody());
        if (root != null) {
            root.put("model", normalized);
            ctx.setBody(JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public int getOrder() {
        return ORDER_MODEL_ALIAS;
    }
}
