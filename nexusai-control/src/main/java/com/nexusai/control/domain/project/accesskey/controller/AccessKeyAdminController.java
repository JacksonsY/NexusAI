package com.nexusai.control.domain.project.accesskey.controller;

import com.nexusai.common.model.PageResult;
import com.nexusai.common.model.R;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyCreateRequest;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyCreateResponse;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyListItem;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyQuotaAdjustRequest;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyRotateResponse;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyUpdateRequest;
import com.nexusai.control.domain.project.accesskey.service.AccessKeyAdminService;
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

/**
 * 独立密钥（nai_token·standalone=1）管理（/admin/access-keys·受 AdminTokenWebFilter 保护）。
 * <p>外部业务面同步 Key 走 Key 同步端点 {@code /admin/keys}（KeySyncController）；本控制器是控制台独立签发的
 * 独立 Key 能力：CRUD + 轮转 + 额度调整 + 余额查询（明文只返回一次）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/access-keys")
@RequiredArgsConstructor
public class AccessKeyAdminController {

    private final AccessKeyAdminService accessKeyAdminService;

    @GetMapping
    public Mono<R<PageResult<AccessKeyListItem>>> list(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String projectCode,
                                              @RequestParam(required = false) Boolean standalone) {
        return Mono.fromCallable(() -> R.ok(accessKeyAdminService.list(pageNum, pageSize, projectCode, standalone)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<R<AccessKeyCreateResponse>> create(@RequestBody AccessKeyCreateRequest request) {
        return Mono.fromCallable(() -> R.ok(accessKeyAdminService.create(request))).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody AccessKeyUpdateRequest request) {
        return Mono.fromCallable(() -> {
            accessKeyAdminService.update(id, request);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/rotate")
    public Mono<R<AccessKeyRotateResponse>> rotate(@PathVariable Long id) {
        return Mono.fromCallable(() -> R.ok(accessKeyAdminService.rotate(id))).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}/status")
    public Mono<R<Void>> setStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Mono.fromCallable(() -> {
            accessKeyAdminService.setStatus(id, status);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            accessKeyAdminService.delete(id);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 额度调整：调整该 Key 子限额 quotaUsd（body {quotaUsd}·USD·null/≤0=不设上限·仅受项目池约束）。 */
    @PostMapping("/{id}/quota")
    public Mono<R<Void>> adjustQuota(@PathVariable Long id, @RequestBody AccessKeyQuotaAdjustRequest body) {
        return Mono.fromCallable(() -> {
            accessKeyAdminService.adjustQuota(id, body == null ? null : body.quotaUsd());
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 查该 Key 所属项目池/工作区实时可用额度（额度抽屉只读展示）。 */
    @GetMapping("/{id}/balance")
    public Mono<R<Map<String, Object>>> balance(@PathVariable Long id) {
        return Mono.fromCallable(() -> R.ok(accessKeyAdminService.queryBalance(id))).subscribeOn(Schedulers.boundedElastic());
    }
}
