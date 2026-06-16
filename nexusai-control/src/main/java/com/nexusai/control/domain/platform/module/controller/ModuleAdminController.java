package com.nexusai.control.domain.platform.module.controller;

import com.nexusai.control.domain.platform.module.service.ModuleService;
import com.nexusai.common.model.R;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 模块管理 admin（/admin/modules）：
 * 状态列表 / 开关 / 卡片自定义顺序（拖拽排序）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/modules")
@RequiredArgsConstructor
public class ModuleAdminController {

    private final ModuleService moduleService;

    @GetMapping("/status")
    public Mono<R<List<Map<String, Object>>>> status() {
        return blocking(moduleService::status);
    }

    @PutMapping("/status/{name}/enabled")
    public Mono<R<Void>> setEnabled(@PathVariable String name, @RequestBody Map<String, Object> body) {
        return blocking(() -> {
            moduleService.setEnabled(name, Boolean.TRUE.equals(body.get("enabled")));
            return null;
        });
    }

    /** 模块设置（schema+当前值+运行指标·设置抽屉数据源）。 */
    @GetMapping("/{name}/settings")
    public Mono<R<Map<String, Object>>> settings(@PathVariable String name) {
        return blocking(() -> moduleService.settings(name));
    }

    /** 保存模块参数（写 KV·5s 热生效）。 */
    @PutMapping("/{name}/settings")
    public Mono<R<Void>> putSettings(@PathVariable String name, @RequestBody Map<String, Object> values) {
        return blocking(() -> {
            moduleService.putSettings(name, values);
            return null;
        });
    }

    /** 模块动作（important_notification=发送测试通知 / config_backup=立即备份）。 */
    @PostMapping("/{name}/action")
    public Mono<R<Map<String, Object>>> action(@PathVariable String name) {
        return blocking(() -> moduleService.action(name));
    }

    @GetMapping("/order")
    public Mono<R<List<String>>> getOrder() {
        return blocking(moduleService::getOrder);
    }

    @PutMapping("/order")
    public Mono<R<Void>> setOrder(@RequestBody List<String> order) {
        return blocking(() -> {
            moduleService.setOrder(order);
            return null;
        });
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
