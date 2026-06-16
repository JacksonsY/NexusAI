package com.nexusai.control.domain.routing.route.controller;

import com.nexusai.control.domain.routing.route.service.RoutingGroupAdminService;
import com.nexusai.common.model.R;
import com.nexusai.persistence.po.NaiRoutingGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 调度策略分组端点（调度策略页）：
 * 列表 / 创建 / 更新 / 删除 / 设系统默认，写入即对选路生效（本实例即时·其余实例 ≤30s）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/routing/groups")
@RequiredArgsConstructor
public class RoutingGroupAdminController {

    private final RoutingGroupAdminService service;

    @GetMapping
    public Mono<R<List<NaiRoutingGroup>>> list() {
        return Mono.fromCallable(() -> R.ok(service.list())).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<R<Long>> create(@RequestBody NaiRoutingGroup body) {
        return Mono.fromCallable(() -> R.ok(service.create(body))).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody NaiRoutingGroup body) {
        return Mono.fromCallable(() -> {
            service.update(id, body);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            service.delete(id);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 设为系统默认（全局唯一·运行时选路依据）。 */
    @PutMapping("/{id}/default")
    public Mono<R<Void>> setDefault(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            service.setSystemDefault(id);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
