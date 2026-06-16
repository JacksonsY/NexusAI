package com.nexusai.control.domain.routing.credential.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.api.contract.ConfigChangePublisher;
import com.nexusai.control.domain.routing.credential.service.CredentialAdminService;
import com.nexusai.persistence.po.NaiChannelKey;
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

import java.util.List;

/**
 * 渠道密钥 Key 运营 CRUD（/admin/channel-keys·受 AdminTokenWebFilter 保护）。
 * <p>控制器只编排 + 反应式适配；凭证 AES-256-GCM 加密/列表脱敏/重加密判定下沉 {@link CredentialAdminService}。
 * <p>create/update/delete 后广播配置变更：渠道密钥（启停/轮转/删除/并发上限/权重）属 RouteCache key-pool 缓存数据，
 * 不广播则停用/轮转的 Key 仍被用到最长一个 key-pool TTL（~300s），跨实例更久。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/channel-keys")
@RequiredArgsConstructor
public class ChannelKeyAdminController {

    private final CredentialAdminService credentialService;
    private final ConfigChangePublisher configChangePublisher;

    @GetMapping
    public Mono<R<List<NaiChannelKey>>> list(@RequestParam(required = false) Long channelId) {
        return Mono.fromCallable(() -> R.ok(credentialService.list(channelId))).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<R<Long>> create(@RequestBody NaiChannelKey key) {
        return Mono.fromCallable(() -> {
            Long id = credentialService.create(key);
            configChangePublisher.publish();
            return R.ok(id);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody NaiChannelKey key) {
        return Mono.fromCallable(() -> {
            credentialService.update(id, key);
            configChangePublisher.publish();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            credentialService.delete(id);
            configChangePublisher.publish();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
