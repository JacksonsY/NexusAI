package com.nexusai.control.domain.platform.ops.controller;

import com.nexusai.control.domain.platform.ops.service.OpsAlertService;
import com.nexusai.control.domain.platform.ops.service.OpsDashboardService;
import com.nexusai.control.domain.platform.ops.vo.AlertEventPageVo;
import com.nexusai.control.domain.platform.ops.vo.AlertRuleTestResultVo;
import com.nexusai.control.domain.platform.ops.vo.OpsAlertSettingsVo;
import com.nexusai.control.domain.platform.ops.vo.OpsErrorCategoryVo;
import com.nexusai.control.domain.platform.ops.vo.OpsLatencyBucketVo;
import com.nexusai.control.domain.platform.ops.vo.OpsRealtimeTrafficVo;
import com.nexusai.control.domain.platform.ops.vo.OpsThresholdsVo;
import com.nexusai.control.domain.platform.ops.vo.OpsTrendVo;
import com.nexusai.common.model.R;
import com.nexusai.persistence.po.NaiAlertRule;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 运维监控 admin（/admin/ops）：
 * 合并快照/三趋势/延迟直方图/错误分布 + 告警规则 CRUD/测试 + 告警事件 + 阈值设置。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/ops")
@RequiredArgsConstructor
public class OpsAdminController {

    private final OpsDashboardService dashboardService;
    private final OpsAlertService alertService;

    // ---------- 仪表盘 ----------

    @GetMapping("/overview")
    public Mono<R<Map<String, Object>>> overview(@RequestParam(defaultValue = "60") int rangeMinutes,
                                                 @RequestParam(required = false) String startTime,
                                                 @RequestParam(required = false) String endTime,
                                                 @RequestParam(required = false) Long channelId) {
        var range = resolveRange(rangeMinutes, startTime, endTime);
        return blocking(() -> dashboardService.overview(range[0], range[1], channelId));
    }

    @GetMapping("/trend")
    public Mono<R<OpsTrendVo>> trend(@RequestParam(defaultValue = "60") int rangeMinutes,
                                     @RequestParam(defaultValue = "48") int segments,
                                     @RequestParam(required = false) String startTime,
                                     @RequestParam(required = false) String endTime,
                                     @RequestParam(required = false) Long channelId) {
        var range = resolveRange(rangeMinutes, startTime, endTime);
        int segs = Math.max(6, Math.min(segments, 120));
        return blocking(() -> dashboardService.trend(range[0], range[1], segs, channelId));
    }

    @GetMapping("/latency-histogram")
    public Mono<R<List<OpsLatencyBucketVo>>> latencyHistogram(@RequestParam(defaultValue = "60") int rangeMinutes,
                                                              @RequestParam(required = false) String startTime,
                                                              @RequestParam(required = false) String endTime,
                                                              @RequestParam(required = false) Long channelId) {
        var range = resolveRange(rangeMinutes, startTime, endTime);
        return blocking(() -> dashboardService.latencyHistogram(range[0], range[1], channelId));
    }

    @GetMapping("/error-distribution")
    public Mono<R<List<OpsErrorCategoryVo>>> errorDistribution(@RequestParam(defaultValue = "60") int rangeMinutes,
                                                               @RequestParam(required = false) String startTime,
                                                               @RequestParam(required = false) String endTime,
                                                               @RequestParam(required = false) Long channelId) {
        var range = resolveRange(rangeMinutes, startTime, endTime);
        return blocking(() -> dashboardService.errorDistribution(range[0], range[1], channelId));
    }

    /** 实时流量窗口（窗口可切 60/300/1800/3600 秒）。 */
    @GetMapping("/realtime-traffic")
    public Mono<R<OpsRealtimeTrafficVo>> realtimeTraffic(@RequestParam(defaultValue = "60") int windowSeconds,
                                                         @RequestParam(required = false) Long channelId) {
        return blocking(() -> dashboardService.realtimeTraffic(windowSeconds, channelId));
    }

    // ---------- 阈值设置 ----------

    @GetMapping("/thresholds")
    public Mono<R<OpsThresholdsVo>> thresholds() {
        return blocking(dashboardService::thresholds);
    }

    @PutMapping("/thresholds")
    public Mono<R<OpsThresholdsVo>> saveThresholds(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            dashboardService.saveThresholds(body);
            return dashboardService.thresholds();
        });
    }

    // ---------- 告警评估设置 ----------

    @GetMapping("/alert-settings")
    public Mono<R<OpsAlertSettingsVo>> alertSettings() {
        return blocking(dashboardService::alertSettings);
    }

    @PutMapping("/alert-settings")
    public Mono<R<OpsAlertSettingsVo>> saveAlertSettings(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            dashboardService.saveAlertSettings(body);
            return dashboardService.alertSettings();
        });
    }

    // ---------- 告警规则 ----------

    @GetMapping("/alert-rules")
    public Mono<R<List<NaiAlertRule>>> rules() {
        return blocking(alertService::rules);
    }

    @GetMapping("/alert-rules/metric-types")
    public Mono<R<List<String>>> metricTypes() {
        return blocking(() -> OpsAlertService.METRIC_TYPES.stream().sorted().toList());
    }

    @PostMapping("/alert-rules")
    public Mono<R<NaiAlertRule>> createRule(@RequestBody Map<String, Object> body) {
        return blocking(() -> alertService.saveRule(null, body));
    }

    @PutMapping("/alert-rules/{id}")
    public Mono<R<NaiAlertRule>> updateRule(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return blocking(() -> alertService.saveRule(id, body));
    }

    @DeleteMapping("/alert-rules/{id}")
    public Mono<R<Void>> deleteRule(@PathVariable long id) {
        return blocking(() -> {
            alertService.deleteRule(id);
            return null;
        });
    }

    @PostMapping("/alert-rules/test")
    public Mono<R<AlertRuleTestResultVo>> testRule(@RequestBody Map<String, Object> body) {
        return blocking(() -> alertService.testRule(body));
    }

    // ---------- 告警事件 ----------

    @GetMapping("/alert-events")
    public Mono<R<AlertEventPageVo>> events(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String severity,
                                            @RequestParam(required = false) Integer hours,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        return blocking(() -> alertService.events(status, severity, hours, page, pageSize));
    }

    @PutMapping("/alert-events/{id}/status")
    public Mono<R<Void>> updateEventStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return blocking(() -> {
            alertService.updateEventStatus(id, String.valueOf(body.get("status")));
            return null;
        });
    }

    /**
     * 解析时间范围（自定义时间范围）：startTime/endTime（ISO-8601）同时给出时优先；
     * 否则按 rangeMinutes 回看（钳 5 分钟 ~ 30 天）。
     */
    private static LocalDateTime[] resolveRange(int rangeMinutes, String startTime, String endTime) {
        if (startTime != null && !startTime.isBlank() && endTime != null && !endTime.isBlank()) {
            LocalDateTime start = LocalDateTime.parse(startTime);
            LocalDateTime end = LocalDateTime.parse(endTime);
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("endTime 必须晚于 startTime");
            }
            return new LocalDateTime[]{start, end};
        }
        int range = Math.max(5, Math.min(rangeMinutes, 30 * 24 * 60));
        LocalDateTime now = LocalDateTime.now();
        return new LocalDateTime[]{now.minusMinutes(range), now};
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
