package com.nexusai.control.domain.monitor.observe.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.monitor.observe.service.HealthMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 健康监控（健康监控页·端点健康时间线）：
 * 协议端点 / 模型 / 渠道三维度健康时间线，回溯窗口 1-72 小时（默认 6）。
 * <p>控制器只钳参 + 反应式适配；聚合合成下沉 {@link HealthMonitorService}。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/observe/health")
@RequiredArgsConstructor
public class HealthMonitorController {

    private final HealthMonitorService healthService;

    /** 协议端点健康监控：按渠道协议族聚合（成功率 + 100 段时间线 + 渠道/凭证数）。 */
    @GetMapping("/protocols")
    public Mono<R<Map<String, Object>>> protocols(@RequestParam(defaultValue = "6") int lookbackHours) {
        int hours = clamp(lookbackHours, 1, 72);
        return Mono.fromCallable(() -> R.ok(healthService.protocols(hours)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 模型健康监控：按模型聚合（可用率/时延/TTFT + 60 段时间线），请求量 Top modelLimit。 */
    @GetMapping("/models")
    public Mono<R<Map<String, Object>>> models(@RequestParam(defaultValue = "6") int lookbackHours,
                                               @RequestParam(defaultValue = "12") int modelLimit) {
        int hours = clamp(lookbackHours, 1, 72);
        int limit = clamp(modelLimit, 1, 50);
        return Mono.fromCallable(() -> R.ok(healthService.models(hours, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道健康监控：按渠道聚合 + 下挂模型明细（故障排前），每渠道模型 Top modelLimit。 */
    @GetMapping("/channels")
    public Mono<R<Map<String, Object>>> channels(@RequestParam(defaultValue = "6") int lookbackHours,
                                                 @RequestParam(defaultValue = "12") int modelLimit) {
        int hours = clamp(lookbackHours, 1, 72);
        int limit = clamp(modelLimit, 1, 50);
        return Mono.fromCallable(() -> R.ok(healthService.channels(hours, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 性能详情钻取：dim∈{model,channel}·key=模型名/渠道id，
     * 逐桶 TPS/延迟/TTFT/成功率曲线（48 段）+ 汇总 + 对端维度分解表（分渠道/分模型）。
     */
    @GetMapping("/series")
    public Mono<R<Map<String, Object>>> series(@RequestParam String dim,
                                               @RequestParam String key,
                                               @RequestParam(defaultValue = "24") int lookbackHours) {
        if (!"model".equals(dim) && !"channel".equals(dim)) {
            return Mono.just(R.fail("dim 仅支持 model/channel"));
        }
        if (key == null || key.isBlank()) {
            return Mono.just(R.fail("key 不能为空"));
        }
        int hours = clamp(lookbackHours, 1, 72);
        return Mono.fromCallable(() -> R.ok(healthService.series(dim, key, hours)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
