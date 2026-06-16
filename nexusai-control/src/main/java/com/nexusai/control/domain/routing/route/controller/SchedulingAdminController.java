package com.nexusai.control.domain.routing.route.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.routing.route.service.RoutingGroupAdminService;
import com.nexusai.control.domain.routing.route.vo.RoutingPolicyVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 调度配置快捷端点（优先级管理对话框/模型路由 Tab 在用）：读写<b>系统默认策略</b>的 defaultPolicy
 * （存储已迁移到 nai_routing_group·与调度策略页同一真理源）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/routing/scheduling")
@RequiredArgsConstructor
public class SchedulingAdminController {

    private final RoutingGroupAdminService service;

    /** 读当前生效的调度配置（系统默认策略 defaultPolicy·无策略时为内置默认）。 */
    @GetMapping
    public Mono<R<RoutingPolicyVo>> get() {
        return Mono.fromCallable(() -> R.ok(service.effectiveDefaultPolicy()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 更新系统默认策略的 defaultPolicy（无系统默认策略时自动创建）。 */
    @PutMapping
    public Mono<R<Void>> update(@RequestBody RoutingPolicyVo body) {
        return Mono.fromCallable(() -> {
            service.updateDefaultPolicy(body.schedulingMode(), body.priorityMode());
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
