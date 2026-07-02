package com.nexusai.relay.controller;

import com.nexusai.persistence.po.NaiTask;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.task.TaskHttp;
import com.nexusai.relay.task.TaskRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用视频生成入站（{@code /v1/video/generations} + OpenAI Sora 风格 {@code /v1/videos}）：提交 → 持久化任务 → 查询/下载。
 * 复用 {@link TaskRelayService}（鉴权/pre-charge 冻结/持久化·modality=video）+ {@code nai_task}。
 * <b>上游视频 API 提交/轮询/转存是 TaskAdapter SPI 集成点</b>（per-provider·TaskPoller 驱动·可灵/即梦走各自原生入口见 Kling/Jimeng Controller）。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class VideoController {

    private static final String MODALITY = "video";

    private final TaskRelayService taskRelay;

    /** 视频生成提交（/v1/video/generations）。 */
    @PostMapping("/v1/video/generations")
    public Mono<Map<String, Object>> generations(ServerWebExchange exchange) {
        return submit(exchange, "generations");
    }

    /** 视频生成查询。 */
    @GetMapping("/v1/video/generations/{taskId}")
    public Mono<Map<String, Object>> generationsFetch(@PathVariable String taskId, ServerWebExchange exchange) {
        return fetch(exchange, taskId);
    }

    /** OpenAI Sora 风格提交（/v1/videos）。 */
    @PostMapping("/v1/videos")
    public Mono<Map<String, Object>> videos(ServerWebExchange exchange) {
        return submit(exchange, "create");
    }

    /** OpenAI Sora 风格查询。 */
    @GetMapping("/v1/videos/{taskId}")
    public Mono<Map<String, Object>> videosFetch(@PathVariable String taskId, ServerWebExchange exchange) {
        return fetch(exchange, taskId);
    }

    /** 视频 remix（基于已有视频再生成）。 */
    @PostMapping("/v1/videos/{videoId}/remix")
    public Mono<Map<String, Object>> remix(@PathVariable String videoId, ServerWebExchange exchange) {
        return submit(exchange, "remix");
    }

    /** 视频内容下载（302 跳转到结果 URL·未就绪/无 URL → 404）。 */
    @GetMapping("/v1/videos/{taskId}/content")
    public Mono<Void> content(@PathVariable String taskId, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> taskRelay.query(taskRelay.authenticate(TaskHttp.authHeader(exchange)), taskId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(t -> {
                    if (t.getResultUrl() == null || t.getResultUrl().isBlank()) {
                        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                        return exchange.getResponse().setComplete();
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                    exchange.getResponse().getHeaders().setLocation(URI.create(t.getResultUrl()));
                    return exchange.getResponse().setComplete();
                });
    }

    // ===== 内部 =====

    private Mono<Map<String, Object>> submit(ServerWebExchange exchange, String action) {
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(TaskHttp.authHeader(exchange));
            String model = TaskHttp.modelOf(b, MODALITY);
            NaiTask task = taskRelay.submit(token, MODALITY, action, model, b, TaskHttp.clientIp(exchange));
            return envelope(task, model);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Map<String, Object>> fetch(ServerWebExchange exchange, String taskId) {
        return Mono.fromCallable(() -> {
            NaiTask t = taskRelay.query(taskRelay.authenticate(TaskHttp.authHeader(exchange)), taskId);
            return envelope(t, t.getModel());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> envelope(NaiTask t, String model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getTaskId());
        m.put("object", "video");
        m.put("model", model);
        m.put("status", videoStatus(t.getStatus()));
        m.put("progress", t.getProgress());
        m.put("url", t.getResultUrl());
        m.put("error", t.getFailReason());
        return m;
    }

    /** nexusai 任务态 → 视频 API 任务态。 */
    private static String videoStatus(String status) {
        if (status == null) {
            return "queued";
        }
        return switch (status) {
            case "SUBMITTED" -> "queued";
            case "IN_PROGRESS" -> "in_progress";
            case "SUCCESS" -> "completed";
            case "FAILED" -> "failed";
            default -> status.toLowerCase();
        };
    }
}
