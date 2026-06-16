package com.nexusai.relay.controller;

import com.nexusai.relay.RelayOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gemini 原生入站（{@code /v1beta/models/*path}）：使 Gemini SDK / Google GenAI 客户端可直连本网关。
 * <p>路径形如 {@code /v1beta/models/{model}:{action}}（generateContent / streamGenerateContent / embedContent /
 * batchEmbedContents）——<b>模型在 URL path 而非 body</b>，由 {@code RequestPeekFilter} 经 {@code RequestPeeker.peekGemini}
 * 从 path 解析模型/流式；鉴权经 {@code ApiKeyAuthFilter} 回退 {@code x-goog-api-key}/{@code ?key=}。
 * <p>路由到 Gemini 协议渠道为原生透传（上游路径自动跟随·model_mapping 改写时同步改写 path）；
 * 路由到异协议渠道的 Canonical 互转（Gemini→OpenAI/Anthropic）暂随 transform SPI 能力（二期完善）。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class GeminiInboundController {

    private final RelayOrchestrator orchestrator;

    /** Gemini 原生入站 catch-all（{model}:{action} 在 path·含 {@code :} 与流式动作）。 */
    @PostMapping("/v1beta/models/{*tail}")
    public Mono<Void> geminiModels(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, exchange.getRequest().getPath().value());
    }
}
