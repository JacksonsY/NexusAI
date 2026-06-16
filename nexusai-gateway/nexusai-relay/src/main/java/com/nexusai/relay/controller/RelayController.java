package com.nexusai.relay.controller;

import com.nexusai.relay.RelayOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容转发入口。透传直采：原体/原路径转发上游，
 * 同步/流式由请求体 stream 决定（RelayOrchestrator 内分流）。
 * <p>除 OpenAI 风格端点外，另开 <b>Anthropic 原生入站 {@code /v1/messages}</b>：
 * Anthropic 客户端可直连本网关——路由到 Anthropic 渠道为原生透传（含流式）；路由到异协议渠道
 * 且渠道 {@code setting.canonical=true} 时经 Canonical 互转（非流式）。inboundFormat 由 path 识别为 ANTHROPIC，
 * RequestPeekFilter 对其不注入 OpenAI 专有 include_usage、QuotaGate 已将 /messages 视为 chat 计费。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class RelayController {

    private final RelayOrchestrator orchestrator;

    @PostMapping("/v1/chat/completions")
    public Mono<Void> chatCompletions(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/chat/completions");
    }

    /** Anthropic 原生入站（Messages API）：使 Claude SDK / Anthropic 客户端可直连本网关（真 N×N 入站）。 */
    @PostMapping("/v1/messages")
    public Mono<Void> anthropicMessages(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/messages");
    }

    @PostMapping("/v1/embeddings")
    public Mono<Void> embeddings(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/embeddings");
    }

    @PostMapping("/v1/images/generations")
    public Mono<Void> imageGenerations(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/images/generations");
    }

    @PostMapping("/v1/rerank")
    public Mono<Void> rerank(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/rerank");
    }

    // ===== OpenAI 兼容族·JSON 体透传端点（上游路径自动跟随入站路径） =====

    /** 旧版文本补全（legacy completions·提示词单串）。 */
    @PostMapping("/v1/completions")
    public Mono<Void> completions(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/completions");
    }

    /** 内容审核（moderations·按输入文本计费）。 */
    @PostMapping("/v1/moderations")
    public Mono<Void> moderations(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/moderations");
    }

    /** 旧版文本编辑（legacy edits）。 */
    @PostMapping("/v1/edits")
    public Mono<Void> edits(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/edits");
    }

    /** OpenAI Responses API（input 串而非 messages·usage 取法同 chat）。 */
    @PostMapping("/v1/responses")
    public Mono<Void> responses(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/responses");
    }

    /** Responses 紧凑变体（previous_response_id 续接·上游路径自动跟随）。 */
    @PostMapping("/v1/responses/compact")
    public Mono<Void> responsesCompact(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/responses/compact");
    }

    /** 语音合成 TTS（audio/speech·上游返回二进制音频流·writeBack 原样透传）。 */
    @PostMapping("/v1/audio/speech")
    public Mono<Void> audioSpeech(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/audio/speech");
    }

    // ===== multipart/form-data 文件上传端点（model 在表单字段·原体含 boundary 透传上游） =====

    /** 语音转写 Whisper（multipart·file+model 表单）。 */
    @PostMapping("/v1/audio/transcriptions")
    public Mono<Void> audioTranscriptions(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/audio/transcriptions");
    }

    /** 语音翻译 Whisper（multipart·file+model 表单）。 */
    @PostMapping("/v1/audio/translations")
    public Mono<Void> audioTranslations(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/audio/translations");
    }

    /** 图像编辑（multipart·image+mask+prompt+model 表单）。 */
    @PostMapping("/v1/images/edits")
    public Mono<Void> imageEdits(ServerWebExchange exchange) {
        return orchestrator.relay(exchange, "/v1/images/edits");
    }
}
