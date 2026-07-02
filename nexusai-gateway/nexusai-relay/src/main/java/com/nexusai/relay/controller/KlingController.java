package com.nexusai.relay.controller;

import com.nexusai.persistence.po.NaiTask;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.task.TaskHttp;
import com.nexusai.relay.task.TaskRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可灵 Kling 视频原生入站（{@code /kling/v1/videos/*}）：文生视频/图生视频提交 + 查询。
 * 复用 {@link TaskRelayService}（鉴权/冻结/持久化·modality=video）+ {@code nai_task}；响应用 Kling 原生信封
 * {@code {code,message,data:{task_id,task_status}}}。<b>上游可灵 API 的请求转换+提交+轮询是 TaskAdapter SPI 集成点</b>。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class KlingController {

    private static final String MODALITY = "video";
    private static final String DEFAULT_MODEL = "kling-v1";

    private final TaskRelayService taskRelay;

    @PostMapping("/kling/v1/videos/text2video")
    public Mono<Map<String, Object>> text2video(ServerWebExchange exchange) {
        return submit(exchange, "text2video");
    }

    @PostMapping("/kling/v1/videos/image2video")
    public Mono<Map<String, Object>> image2video(ServerWebExchange exchange) {
        return submit(exchange, "image2video");
    }

    @GetMapping("/kling/v1/videos/text2video/{taskId}")
    public Mono<Map<String, Object>> text2videoFetch(@PathVariable String taskId, ServerWebExchange exchange) {
        return fetch(exchange, taskId);
    }

    @GetMapping("/kling/v1/videos/image2video/{taskId}")
    public Mono<Map<String, Object>> image2videoFetch(@PathVariable String taskId, ServerWebExchange exchange) {
        return fetch(exchange, taskId);
    }

    private Mono<Map<String, Object>> submit(ServerWebExchange exchange, String action) {
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(TaskHttp.authHeader(exchange));
            String model = TaskHttp.modelOf(b, DEFAULT_MODEL);
            NaiTask task = taskRelay.submit(token, MODALITY, action, model, b, TaskHttp.clientIp(exchange));
            return envelope(task);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Map<String, Object>> fetch(ServerWebExchange exchange, String taskId) {
        return Mono.fromCallable(() -> envelope(taskRelay.query(taskRelay.authenticate(TaskHttp.authHeader(exchange)), taskId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> envelope(NaiTask t) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", t.getTaskId());
        data.put("task_status", klingStatus(t.getStatus()));
        data.put("task_status_msg", t.getFailReason());
        if (t.getResultUrl() != null) {
            data.put("task_result", Map.of("videos", List.of(Map.of("url", t.getResultUrl()))));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 0);
        m.put("message", "SUCCEED");
        m.put("request_id", t.getTaskId());
        m.put("data", data);
        return m;
    }

    private static String klingStatus(String status) {
        if (status == null) {
            return "submitted";
        }
        return switch (status) {
            case "SUBMITTED" -> "submitted";
            case "IN_PROGRESS" -> "processing";
            case "SUCCESS" -> "succeed";
            case "FAILED" -> "failed";
            default -> status.toLowerCase();
        };
    }
}
