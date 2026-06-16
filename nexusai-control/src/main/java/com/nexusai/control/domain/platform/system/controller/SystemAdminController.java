package com.nexusai.control.domain.platform.system.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.control.domain.platform.system.service.SystemMaintenanceService;
import com.nexusai.control.domain.platform.system.vo.PurgeResultVo;
import com.nexusai.control.domain.platform.system.vo.SystemVersionVo;
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

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 系统设置 admin（/admin/system）：
 * 配置 KV 全量/写/删 + 版本信息 + 配置导出/导入 + 清空数据。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/system")
@RequiredArgsConstructor
public class SystemAdminController {

    private final SystemConfigService configService;
    private final SystemMaintenanceService maintenance;

    @GetMapping("/configs")
    public Mono<R<Map<String, Object>>> listConfigs() {
        return blocking(configService::listAll);
    }

    @PutMapping("/configs/{key}")
    public Mono<R<Void>> putConfig(@PathVariable String key, @RequestBody Map<String, Object> body) {
        return blocking(() -> {
            configService.put(key, body.get("value"),
                    body.get("description") == null ? null : String.valueOf(body.get("description")));
            return null;
        });
    }

    /** 批量写（section 保存一次提交多键）。 */
    @PutMapping("/configs")
    public Mono<R<Void>> putConfigs(@RequestBody Map<String, Object> values) {
        return blocking(() -> {
            values.forEach((k, v) -> configService.put(k, v, null));
            return null;
        });
    }

    @DeleteMapping("/configs/{key}")
    public Mono<R<Void>> deleteConfig(@PathVariable String key) {
        return blocking(() -> {
            configService.delete(key);
            return null;
        });
    }

    @GetMapping("/version")
    public Mono<R<SystemVersionVo>> version() {
        return blocking(maintenance::version);
    }

    @GetMapping("/config/export")
    public Mono<R<Map<String, Object>>> exportConfig() {
        return blocking(maintenance::exportConfig);
    }

    @PostMapping("/config/import")
    public Mono<R<Map<String, Object>>> importConfig(@RequestBody Map<String, Object> payload,
                                                     @RequestParam(defaultValue = "skip") String mergeMode) {
        return blocking(() -> maintenance.importConfig(payload, mergeMode));
    }

    @PostMapping("/purge/{target}")
    public Mono<R<PurgeResultVo>> purge(@PathVariable String target) {
        return blocking(() -> maintenance.purge(target));
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
