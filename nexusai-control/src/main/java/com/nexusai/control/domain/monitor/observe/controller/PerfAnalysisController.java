package com.nexusai.control.domain.monitor.observe.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.monitor.observe.service.PerfAnalysisService;
import com.nexusai.persistence.log.stat.PercentileDay;
import com.nexusai.persistence.log.stat.PerfFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 性能分析（性能分析页·性能统计 + 实时监控区）：
 * 实时运行状态（前端 10s 轮询）/ 渠道性能聚合（筛选 + 分位 + 趋势曲线）/ 按日分位时序 / 错误分布与趋势。
 * <p>控制器只钳参 + 反应式适配；聚合合成下沉 {@link PerfAnalysisService}。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/observe/perf")
@RequiredArgsConstructor
public class PerfAnalysisController {

    private final PerfAnalysisService perfService;

    /** 实时运行状态：健康分/错误计数/并发指标/并发槽/集群节点/降级切换/最近错误/熔断与建议。 */
    @GetMapping("/live")
    public Mono<R<Map<String, Object>>> live() {
        return Mono.fromCallable(() -> R.ok(perfService.live())).subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道性能聚合：筛选（渠道/模型/能力/流式/慢阈值）+ summary + 每渠道行（P90/P99）+ 趋势曲线。 */
    @GetMapping("/providers")
    public Mono<R<Map<String, Object>>> providers(@RequestParam(required = false) Long channelId,
                                                  @RequestParam(required = false) String model,
                                                  @RequestParam(required = false) String modality,
                                                  @RequestParam(required = false) Boolean stream,
                                                  @RequestParam(defaultValue = "24") int lookbackHours,
                                                  @RequestParam(defaultValue = "10000") int slowThresholdMs,
                                                  @RequestParam(defaultValue = "8") int limit) {
        int hours = clamp(lookbackHours, 1, 24 * 30);
        int slowMs = clamp(slowThresholdMs, 1, 600_000);
        int topN = clamp(limit, 1, 50);
        PerfFilter filter = new PerfFilter(channelId, model, modality, stream);
        return Mono.fromCallable(() -> R.ok(perfService.providers(filter, hours, slowMs, topN)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 按日延迟分位时序（响应 + 首字 P50/P90/P99）。 */
    @GetMapping("/percentiles")
    public Mono<R<List<PercentileDay>>> percentiles(@RequestParam(defaultValue = "7") int days) {
        int window = clamp(days, 1, 30);
        return Mono.fromCallable(() -> R.ok(perfService.percentiles(window)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 错误分类分布 + 按日错误趋势。 */
    @GetMapping("/errors")
    public Mono<R<Map<String, Object>>> errors(@RequestParam(defaultValue = "7") int days) {
        int window = clamp(days, 1, 30);
        return Mono.fromCallable(() -> R.ok(perfService.errors(window)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
