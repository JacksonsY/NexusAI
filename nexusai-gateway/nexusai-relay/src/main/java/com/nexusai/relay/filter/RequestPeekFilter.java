package com.nexusai.relay.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.protocol.passthrough.RequestPeeker;
import com.nexusai.relay.context.RelayContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * ② 最小解析：强制注入 stream_options.include_usage（防 token 旁路）+ 窥取 model/stream → 填 peek/body。
 * <p>透传直采，不做完整反序列化（MVP）。
 *
 * @author nexusai
 */
@Component
public class RequestPeekFilter implements RelayFilter {

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        byte[] body = ctx.getBody();
        // Gemini 原生入站：模型在 URL path（体不含 model）；不注入 OpenAI 专有 include_usage·原体透传
        if (RequestPeeker.isGeminiInbound(ctx.getPath())) {
            JsonNode root = (body == null || body.length == 0) ? null : JsonUtils.readTree(body);
            ctx.setPeek(RequestPeeker.peekGemini(ctx.getPath(), root));
            return;
        }
        // multipart/form-data 入站（音频转写/翻译、图像编辑）：model 在表单字段·原体二进制透传·不做 JSON 改写
        if (ctx.isMultipart()) {
            ctx.setPeek(RequestPeeker.peekMultipart(body));
            return;
        }
        // 仅 OpenAI 风格 chat completions 强制 include_usage；Anthropic /v1/messages 等原生体不注入 OpenAI 专有字段
        if (isOpenAiChat(ctx.getPath())) {
            // 单次解析完成「注入 include_usage + 窥取」，避免每请求两次 readTree 全量解析
            RequestPeeker.PeekedBody pb = RequestPeeker.enforceAndPeek(body);
            ctx.setBody(pb.body());
            ctx.setPeek(pb.peek());
            return;
        }
        ctx.setPeek(RequestPeeker.peek(JsonUtils.readTree(body)));
        ctx.setBody(body);
    }

    private static boolean isOpenAiChat(String path) {
        return path != null && path.endsWith("/chat/completions");
    }

    @Override
    public int getOrder() {
        return ORDER_PEEK;
    }
}
