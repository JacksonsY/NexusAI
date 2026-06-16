package com.nexusai.control.domain.platform.runtime.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.platform.runtime.service.RuntimeViewService;
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
 * 运行时快照（运维监控页并发卡 + 告警指标数据源）：暴露渠道密钥四层选号的运行时态——
 * Key/渠道 健康熔断态、打分、并发槽水位。运行时态在内存（单实例·真多实例下沉 Redis 留后续）。
 * <p>控制器只编排 + 反应式适配；快照组装/打分下沉 {@link RuntimeViewService}。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/runtime")
@RequiredArgsConstructor
public class RuntimeAdminController {

    private final RuntimeViewService viewService;

    @GetMapping("/keys")
    public Mono<R<List<Map<String, Object>>>> keys(@RequestParam(required = false) Long channelId) {
        return Mono.fromCallable(() -> R.ok(viewService.keySnapshots(channelId))).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/channels")
    public Mono<R<List<Map<String, Object>>>> channels() {
        return Mono.fromCallable(() -> R.ok(viewService.channelSnapshots())).subscribeOn(Schedulers.boundedElastic());
    }
}
