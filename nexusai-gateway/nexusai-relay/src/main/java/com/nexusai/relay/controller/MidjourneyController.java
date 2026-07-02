package com.nexusai.relay.controller;

import com.nexusai.common.constant.GatewayConstants;
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
 * Midjourney 代理协议入站（{@code /mj/*}·16 动作）：提交 → 持久化任务 → 查询，复用 {@link TaskRelayService}
 * （鉴权/pre-charge 冻结/持久化）+ {@code nai_task}。<b>上游 MJ-proxy 提交与轮询是 TaskAdapter SPI 的 per-provider 集成点</b>
 * （TaskPoller 驱动·未接真上游前任务停在 SUBMITTED）。响应用 MJ-proxy 信封 {@code {code,result,description}}。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class MidjourneyController {

    private static final String MODALITY = "midjourney";

    private final TaskRelayService taskRelay;

    /** 提交类动作 catch-all：imagine/describe/blend/change/simple-change/action/modal/shorten/edits/video/upload-discord-images。 */
    @PostMapping("/mj/submit/{action}")
    public Mono<Map<String, Object>> submit(@PathVariable String action, ServerWebExchange exchange) {
        return submitTask(exchange, action);
    }

    /** InsightFace 换脸（独立路径·MJ Plus）。 */
    @PostMapping("/mj/insight-face/swap")
    public Mono<Map<String, Object>> swapFace(ServerWebExchange exchange) {
        return submitTask(exchange, "swap-face");
    }

    /** 单任务查询。 */
    @GetMapping("/mj/task/{id}/fetch")
    public Mono<Map<String, Object>> fetch(@PathVariable String id, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> taskDto(taskRelay.query(taskRelay.authenticate(auth(exchange)), id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 任务图种（image-seed·当前任务模型未存 seed → 回 MJ 信封占位·待上游 adapter 补）。 */
    @GetMapping("/mj/task/{id}/image-seed")
    public Mono<Map<String, Object>> imageSeed(@PathVariable String id, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            taskRelay.query(taskRelay.authenticate(auth(exchange)), id);   // 鉴权+作用域校验
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", 1);
            m.put("description", "success");
            m.put("result", "");   // seed 由上游 adapter 回填
            return m;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 按条件批量查询（list-by-condition·body {ids:[...]}）。 */
    @PostMapping("/mj/task/list-by-condition")
    public Mono<List<Map<String, Object>>> listByCondition(ServerWebExchange exchange) {
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(auth(exchange));
            List<Map<String, Object>> out = new ArrayList<>();
            for (String id : TaskHttp.ids(b)) {
                try {
                    out.add(taskDto(taskRelay.query(token, id)));
                } catch (RuntimeException ignore) {
                    // 跨项目/不存在的 id 跳过（不泄露存在性）
                }
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Map<String, Object>> submitTask(ServerWebExchange exchange, String action) {
        return TaskHttp.body(exchange).flatMap(b -> Mono.fromCallable(() -> {
            NaiToken token = taskRelay.authenticate(auth(exchange));
            NaiTask task = taskRelay.submit(token, MODALITY, action, MODALITY, b, TaskHttp.clientIp(exchange));
            return submitEnvelope(task);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // ===== 信封 =====

    private static Map<String, Object> submitEnvelope(NaiTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 1);
        m.put("description", "Submit success");
        m.put("result", task.getTaskId());
        m.put("properties", new LinkedHashMap<>());
        return m;
    }

    private static Map<String, Object> taskDto(NaiTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getTaskId());
        m.put("action", t.getAction() == null ? null : t.getAction().toUpperCase());
        m.put("status", mjStatus(t.getStatus()));
        m.put("progress", t.getProgress() == null ? "0%" : t.getProgress());
        m.put("imageUrl", t.getResultUrl());
        m.put("failReason", t.getFailReason());
        m.put("description", "");
        return m;
    }

    /** nexusai 任务态 → MJ-proxy 任务态。 */
    private static String mjStatus(String status) {
        if (status == null) {
            return "NOT_START";
        }
        return switch (status) {
            case "SUBMITTED" -> "SUBMITTED";
            case "IN_PROGRESS" -> "IN_PROGRESS";
            case "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILURE";
            default -> status;
        };
    }

    /** MJ 取密钥：Authorization Bearer → mj-api-secret 头（MJ-proxy 客户端惯用）。 */
    private static String auth(ServerWebExchange exchange) {
        String a = exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER);
        if (a != null && !a.isBlank()) {
            return a;
        }
        String secret = exchange.getRequest().getHeaders().getFirst("mj-api-secret");
        return (secret != null && !secret.isBlank()) ? GatewayConstants.BEARER_PREFIX + secret : a;
    }
}
