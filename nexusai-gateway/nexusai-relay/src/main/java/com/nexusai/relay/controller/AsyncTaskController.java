package com.nexusai.relay.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.po.NaiTask;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.task.TaskRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步任务入口（MJ/视频）：提交=鉴权→pre-charge 冻结 USD→持久化任务（{@link TaskRelayService}）；
 * 查询=鉴权→返回任务态。上游 submit/fetch 协议适配 + 轮询驱动（{@code TaskPoller}）为 per-provider 集成点。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class AsyncTaskController {

    private final TaskRelayService taskRelay;

    @PostMapping("/v1/tasks/submit")
    public Mono<Void> submit(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(AsyncTaskController::toUtf8)
                .defaultIfEmpty("{}")
                .flatMap(body -> Mono.fromCallable(() -> doSubmit(exchange, body))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(map -> writeJson(exchange, 200, map))
                .onErrorResume(ex -> writeError(exchange, ex));
    }

    @GetMapping("/v1/tasks/{taskId}")
    public Mono<Void> query(ServerWebExchange exchange, @PathVariable String taskId) {
        return Mono.fromCallable(() -> {
                    NaiToken token = taskRelay.authenticate(
                            exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER));
                    NaiTask t = taskRelay.query(token, taskId);
                    return taskView(t);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(map -> writeJson(exchange, 200, map))
                .onErrorResume(ex -> writeError(exchange, ex));
    }

    private Map<String, Object> doSubmit(ServerWebExchange exchange, String body) {
        NaiToken token = taskRelay.authenticate(
                exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER));
        JsonNode root = JsonUtils.readTree(body.getBytes(StandardCharsets.UTF_8));
        String model = root.path("model").asText("");
        String modality = root.path("modality").asText("midjourney");
        String action = root.path("action").asText(null);

        return taskView(taskRelay.submit(token, modality, action, model, body));
    }

    private static Map<String, Object> taskView(NaiTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.getTaskId());
        m.put("status", t.getStatus());
        m.put("progress", t.getProgress());
        m.put("model", t.getModel());
        m.put("modality", t.getModality());
        m.put("frozenUsd", t.getFrozenUsd());
        m.put("resultUrl", t.getResultUrl());
        m.put("failReason", t.getFailReason());
        return m;
    }

    private static String toUtf8(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        DataBufferUtils.release(buf);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, int status, Map<String, Object> body) {
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private Mono<Void> writeError(ServerWebExchange exchange, Throwable ex) {
        GatewayErrorCode ec = (ex instanceof GatewayException ge) ? ge.getErrorCode() : GatewayErrorCode.INTERNAL_ERROR;
        Map<String, Object> err = Map.of("error", Map.of(
                "message", ex.getMessage() == null ? ec.getMessage() : ex.getMessage(),
                "type", ec.getCode(), "code", ec.getCode()));
        return writeJson(exchange, ec.getHttpStatus(), new LinkedHashMap<>(err));
    }
}
