package com.nexusai.relay.controller;

import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.po.NaiTask;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.task.TaskHttp;
import com.nexusai.relay.task.TaskRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 即梦 Jimeng 视频原生入站（{@code /jimeng/}·火山引擎 CVSync2Async 官方格式）：
 * 同一 {@code POST /jimeng/} 端点按查询参数 {@code ?Action=} 区分——{@code CVSync2AsyncSubmitTask}=提交、
 * {@code CVSync2AsyncGetResult}=查结果（task_id 在体内）。复用 {@link TaskRelayService}（鉴权/冻结/持久化·modality=video）+ {@code nai_task}。
 * <b>上游火山引擎签名(SigV4 风)+提交+轮询是 TaskAdapter SPI 集成点</b>；响应用火山 CV 信封 {@code {code:10000,data:{task_id}}}。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class JimengController {

    private static final String MODALITY = "video";

    private final TaskRelayService taskRelay;

    /** 火山 CVSync2Async：?Action=CVSync2AsyncSubmitTask 提交 / CVSync2AsyncGetResult 查结果。 */
    @PostMapping("/jimeng/**")
    public Mono<Map<String, Object>> jimeng(ServerWebExchange exchange) {
        String action = exchange.getRequest().getQueryParams().getFirst("Action");
        boolean isFetch = action != null && action.toLowerCase().contains("getresult");
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(TaskHttp.authHeader(exchange));
            if (isFetch) {
                String taskId = reqField(b, "task_id");
                return envelope(taskRelay.query(token, taskId));
            }
            String model = firstNonBlank(reqField(b, "req_key"), "jimeng");
            NaiTask task = taskRelay.submit(token, MODALITY, action == null ? "submit" : action, model, b, TaskHttp.clientIp(exchange));
            return envelope(task);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private static Map<String, Object> envelope(NaiTask t) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", t.getTaskId());
        data.put("status", jimengStatus(t.getStatus()));
        if (t.getResultUrl() != null) {
            data.put("video_url", t.getResultUrl());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 10000);
        m.put("message", "Success");
        m.put("request_id", t.getTaskId());
        m.put("data", data);
        return m;
    }

    private static String jimengStatus(String status) {
        if (status == null) {
            return "in_queue";
        }
        return switch (status) {
            case "SUBMITTED" -> "in_queue";
            case "IN_PROGRESS" -> "generating";
            case "SUCCESS" -> "done";
            case "FAILED" -> "failed";
            default -> status.toLowerCase();
        };
    }

    private static String reqField(String body, String field) {
        try {
            return JsonUtils.readTree(body).path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
