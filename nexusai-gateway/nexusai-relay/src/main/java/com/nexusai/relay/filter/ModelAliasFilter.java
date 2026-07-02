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
        // 🔴 multipart（音频/图编）体是二进制 multipart·非 JSON：跳过体改写（JsonUtils.readTree 对非 JSON 抛异常 → 500·
        // 原 `root != null` 判空是死代码）。出站 multipart 走原体透传·模型经 peek 归一供权限/计费/路由即可。
        if (ctx.isMultipart()) {
            return;
        }
        try {
            ObjectNode root = (ObjectNode) JsonUtils.readTree(ctx.getBody());
            root.put("model", normalized);
            ctx.setBody(JsonUtils.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
            // 体非 JSON（非预期·如 Gemini 原生体模型在 URL）：保持原体·不因别名改写中断主链
        }
    }

    @Override
    public int getOrder() {
        return ORDER_MODEL_ALIAS;
    }
}
