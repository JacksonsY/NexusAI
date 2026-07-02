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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suno 代理协议入站（{@code /suno/*}·任务提交-查询）：复用 {@link TaskRelayService}（鉴权/冻结/持久化）+ {@code nai_task}。
 * <b>上游 Suno API 提交与轮询是 TaskAdapter SPI 的 per-provider 集成点</b>（未接真上游前任务停在 SUBMITTED）。
 * 响应用 Suno-API 信封 {@code {code:"success",data,message}}。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class SunoController {

    private static final String MODALITY = "suno";

    private final TaskRelayService taskRelay;

    /** 提交（generate/continue_generate/...·action 在 path）。 */
    @PostMapping("/suno/submit/{action}")
    public Mono<Map<String, Object>> submit(@PathVariable String action, ServerWebExchange exchange) {
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(TaskHttp.authHeader(exchange));
            NaiTask task = taskRelay.submit(token, MODALITY, action, MODALITY, b, TaskHttp.clientIp(exchange));
            Map<String, Object> m = ok();
            m.put("data", task.getTaskId());
            return m;
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 批量查询（body {ids:[...]}）。 */
    @PostMapping("/suno/fetch")
    public Mono<Map<String, Object>> fetch(ServerWebExchange exchange) {
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(TaskHttp.authHeader(exchange));
            List<Map<String, Object>> data = new ArrayList<>();
            for (String id : TaskHttp.ids(b)) {
                try {
                    data.add(taskDto(taskRelay.query(token, id)));
                } catch (RuntimeException ignore) {
                    // 跨项目/不存在跳过
                }
            }
            Map<String, Object> m = ok();
            m.put("data", data);
            return m;
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 单任务查询。 */
    @GetMapping("/suno/fetch/{id}")
    public Mono<Map<String, Object>> fetchById(@PathVariable String id, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            NaiTask t = taskRelay.query(taskRelay.authenticate(TaskHttp.authHeader(exchange)), id);
            Map<String, Object> m = ok();
            m.put("data", taskDto(t));
            return m;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "success");
        m.put("message", "");
        return m;
    }

    private static Map<String, Object> taskDto(NaiTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", t.getTaskId());
        m.put("action", t.getAction());
        m.put("status", t.getStatus());
        m.put("progress", t.getProgress());
        m.put("data", t.getResultUrl());
        m.put("fail_reason", t.getFailReason());
        return m;
    }
}
