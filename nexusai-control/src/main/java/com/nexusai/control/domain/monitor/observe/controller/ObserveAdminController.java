package com.nexusai.control.domain.monitor.observe.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.monitor.observe.service.ObserveStatService;
import com.nexusai.control.domain.monitor.observe.vo.DailyPointVo;
import com.nexusai.control.domain.monitor.observe.vo.DashboardStatsVo;
import com.nexusai.control.domain.monitor.observe.vo.DimensionStatVo;
import com.nexusai.control.domain.monitor.observe.vo.PerfDailyPointVo;
import com.nexusai.control.domain.monitor.observe.vo.PerfStatVo;
import com.nexusai.control.domain.monitor.observe.vo.RequestDetailVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 可观测增强（运维台「可观测」二期端点）：错误率/状态码分布、请求贯穿钻取、日志存量概况、告警计算。
 * <p>控制器只编排 + 反应式适配（阻塞调用经 boundedElastic 包裹）；统计聚合/告警阈值算法下沉 {@link ObserveStatService}。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/observe")
@RequiredArgsConstructor
public class ObserveAdminController {

    private final ObserveStatService statService;


    /** Dashboard 运维总览（去钱化）：累计量/环比计数/成功率环比/Token 用量/渠道成功率排行，一次拉齐。 */
    @GetMapping("/dashboard")
    public Mono<R<DashboardStatsVo>> dashboard() {
        return Mono.fromCallable(() -> R.ok(statService.dashboard())).subscribeOn(Schedulers.boundedElastic());
    }

    /** Dashboard 维度分析（去钱化）：dim∈{channel,model,key}·window∈{allTime,month,week,day}·按请求量取 Top limit（请求量 + token 三态）。 */
    @GetMapping("/dashboard/dimension")
    public Mono<R<List<DimensionStatVo>>> dimension(@RequestParam String dim,
                                                    @RequestParam(defaultValue = "allTime") String window,
                                                    @RequestParam(defaultValue = "10") int limit) {
        return Mono.fromCallable(() -> R.ok(statService.dimensionStats(dim, window, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Dashboard 性能最快榜：dim∈{model,channel}·窗口聚合吞吐 + 置信度排序取 Top limit。 */
    @GetMapping("/dashboard/performance")
    public Mono<R<List<PerfStatVo>>> performance(@RequestParam(defaultValue = "model") String dim,
                                                 @RequestParam(defaultValue = "month") String window,
                                                 @RequestParam(defaultValue = "5") int limit) {
        return Mono.fromCallable(() -> R.ok(statService.performance(dim, window, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Dashboard 性能时序：dim∈{model,channel}·近 days 天逐日吞吐/TTFT/请求量。 */
    @GetMapping("/dashboard/performance/daily")
    public Mono<R<List<PerfDailyPointVo>>> performanceDaily(@RequestParam(defaultValue = "model") String dim,
                                                            @RequestParam(defaultValue = "30") int days) {
        return Mono.fromCallable(() -> R.ok(statService.performanceDaily(dim, days)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Dashboard「每日请求概览」面积图：近 days 天按天聚合请求量 + token（去钱化·请求量/Token 双轴）。 */
    @GetMapping("/dashboard/daily")
    public Mono<R<List<DailyPointVo>>> daily(@RequestParam(defaultValue = "14") int days) {
        return Mono.fromCallable(() -> R.ok(statService.dailySeries(days))).subscribeOn(Schedulers.boundedElastic());
    }


    /** 单次请求贯穿钻取：请求日志 + 同 requestId 用量明细。 */
    @GetMapping("/request/{requestId}")
    public Mono<R<RequestDetailVo>> requestDetail(@PathVariable String requestId) {
        return Mono.fromCallable(() -> R.ok(statService.requestDetail(requestId))).subscribeOn(Schedulers.boundedElastic());
    }



    /** 故障转移/重试事件审计（H7）：传 requestId 串单请求失败链，否则近 hours 小时倒序最近 limit 条。 */
    @GetMapping("/failovers")
    public Mono<R<List<?>>> failovers(@RequestParam(required = false) String requestId,
                                      @RequestParam(defaultValue = "24") int hours,
                                      @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(() -> R.<List<?>>ok(statService.failovers(requestId, hours, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
