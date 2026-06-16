package com.nexusai.control.domain.governance.audit.controller;

import com.nexusai.control.domain.governance.audit.service.AuditLogService;
import com.nexusai.control.domain.governance.audit.vo.AuditLogPageVo;
import com.nexusai.common.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 审计日志 admin（/admin/audit-logs）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AuditAdminController {

    private final AuditLogService auditLogService;

    @GetMapping
    public Mono<R<AuditLogPageVo>> page(@RequestParam(required = false) String actor,
                                        @RequestParam(required = false) String eventType,
                                        @RequestParam(defaultValue = "7") int days,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        return blocking(() -> auditLogService.page(actor, eventType, days, page, pageSize));
    }

    @GetMapping("/event-types")
    public Mono<R<List<String>>> eventTypes() {
        return blocking(auditLogService::eventTypes);
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
