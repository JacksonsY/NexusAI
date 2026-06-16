package com.nexusai.control.domain.routing.channel.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexusai.common.model.R;
import com.nexusai.control.api.contract.ConfigChangePublisher;
import com.nexusai.control.domain.routing.channel.service.ChannelBalanceService;
import com.nexusai.control.domain.routing.channel.service.ChannelSummaryService;
import com.nexusai.control.domain.routing.channel.service.ChannelTestService;
import com.nexusai.control.domain.routing.channel.service.ChannelUpstreamModelService;
import com.nexusai.control.domain.routing.channel.vo.ChannelBalanceVo;
import com.nexusai.control.domain.routing.channel.vo.ChannelBatchDeleteRequest;
import com.nexusai.control.domain.routing.channel.vo.ChannelBatchStatusRequest;
import com.nexusai.control.domain.routing.channel.vo.ChannelPriorityPatch;
import com.nexusai.control.domain.routing.channel.vo.ChannelSummaryVo;
import com.nexusai.control.domain.routing.channel.vo.ChannelTestResultVo;
import com.nexusai.control.domain.routing.channel.vo.ChannelUpstreamModelsVo;
import com.nexusai.control.domain.routing.channel.vo.RegisterUpstreamModelsRequest;
import com.nexusai.control.domain.routing.credential.service.CredentialAdminService;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
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
 * 渠道运营 CRUD + 测速探活（技术控制台；/admin/channels·受 AdminTokenWebFilter 保护）。
 * <p>渠道是适配的<b>配置面</b>：protocol(选 providerKey)/base_url/modelMapping/paramOverride/headerOverride/credential
 * 这些扁平字段即"渠道间差异"的唯一受控覆盖手段（撤规则市场后不再经模板生成器，直接编辑）。
 * 协议家族/私有信封/特殊鉴权属<b>代码面</b>（Provider + ChatTransformer SPI），不在此配置。
 * <p>create/update/delete 后经 {@link ConfigChangePublisher} 广播配置变更（本节点 + 跨实例 RTopic），
 * 清理各网关节点路由/健康等本地缓存，保证渠道直编在全集群即时生效，不靠 TTL 兜底。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/channels")
@RequiredArgsConstructor
public class ChannelAdminController {

    private final NaiChannelMapper channelMapper;
    private final ChannelTestService channelTestService;
    private final ChannelSummaryService channelSummaryService;
    private final ChannelBalanceService channelBalanceService;
    private final CredentialAdminService credentialService;
    private final ChannelUpstreamModelService upstreamModelService;
    private final NaiAbilityMapper abilityMapper;
    private final ConfigChangePublisher configChangePublisher;

    @GetMapping
    public Mono<R<List<NaiChannel>>> list() {
        return Mono.fromCallable(() -> R.ok(channelMapper.selectList(null)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道汇总（提供商页）：配置 + 凭证/模型水位 + 近 24h 流量 + 运行时健康，一次拉齐。 */
    @GetMapping("/summary")
    public Mono<R<List<ChannelSummaryVo>>> summary() {
        return Mono.fromCallable(() -> R.ok(channelSummaryService.summaries()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<R<Long>> create(@RequestBody NaiChannel channel) {
        return Mono.fromCallable(() -> {
            credentialService.ensureEncryptionReadyIfChanging(channel.getCredential());
            channel.setId(null);
            channelMapper.insert(channel);
            // 渠道=「URL + Key 一体填写」（无渠道密钥概念）：明文 Key 加密 upsert 到首条凭证
            credentialService.upsertForChannel(channel.getId(), channel.getCredential());
            publishConfigChanged();
            return R.ok(channel.getId());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody NaiChannel channel) {
        return Mono.fromCallable(() -> {
            credentialService.ensureEncryptionReadyIfChanging(channel.getCredential());
            channel.setId(id);
            channelMapper.updateById(channel);
            credentialService.upsertForChannel(id, channel.getCredential());
            publishConfigChanged();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 批量更新渠道优先级（优先级管理对话框·拖拽排序一次保存·一次配置失效）。 */
    @PutMapping("/priorities")
    public Mono<R<Void>> updatePriorities(@RequestBody List<ChannelPriorityPatch> items) {
        return Mono.fromCallable(() -> {
            for (ChannelPriorityPatch item : items) {
                if (item.id() == null || item.priority() == null) {
                    continue;
                }
                NaiChannel patch = new NaiChannel();
                patch.setId(item.id());
                patch.setPriority(item.priority());
                channelMapper.updateById(patch);
            }
            publishConfigChanged();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            channelMapper.deleteById(id);
            publishConfigChanged();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 批量启停：一次更新 + 一次配置失效。 */
    @PutMapping("/batch-status")
    public Mono<R<Void>> batchStatus(@RequestBody ChannelBatchStatusRequest req) {
        return Mono.fromCallable(() -> {
            List<Long> ids = req.ids();
            Integer status = req.status();
            if (ids != null && status != null) {
                for (Long id : ids) {
                    NaiChannel patch = new NaiChannel();
                    patch.setId(id);
                    patch.setStatus(status);
                    channelMapper.updateById(patch);
                }
                publishConfigChanged();
            }
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 批量删除：连带删凭证与路由索引。 */
    @PostMapping("/batch-delete")
    public Mono<R<Integer>> batchDelete(@RequestBody ChannelBatchDeleteRequest req) {
        return Mono.fromCallable(() -> {
            List<Long> ids = req.ids() == null ? List.of() : req.ids();
            int n = 0;
            for (Long id : ids) {
                n += channelMapper.deleteById(id);
                credentialService.deleteByChannel(id);
                abilityMapper.delete(Wrappers.lambdaQuery(NaiAbility.class)
                        .eq(NaiAbility::getChannelId, id));
            }
            if (n > 0) {
                publishConfigChanged();
            }
            return R.ok(n);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道复制：复制配置 + 渠道密钥密文凭证 + 路由索引；新渠道默认停用待确认。 */
    @PostMapping("/{id}/copy")
    public Mono<R<Long>> copy(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            NaiChannel src = channelMapper.selectById(id);
            if (src == null) {
                return R.<Long>fail("渠道不存在");
            }
            src.setId(null);
            src.setName(src.getName() + "-副本");
            src.setStatus(0);
            src.setSuccessCount(0L);
            src.setErrorCount(0L);
            src.setHealthScore(100);
            src.setRateLimitResetAt(0L);
            src.setOverloadUntil(0L);
            src.setTempUnschedulableUntil(0L);
            channelMapper.insert(src);
            credentialService.copyChannelKeys(id, src.getId());
            for (NaiAbility a : abilityMapper.selectList(
                    Wrappers.lambdaQuery(NaiAbility.class)
                            .eq(NaiAbility::getChannelId, id))) {
                a.setChannelId(src.getId());
                abilityMapper.insert(a);
            }
            publishConfigChanged();
            return R.ok(src.getId());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 从上游拉取模型列表：GET {baseUrl}/models·与已注册 ability 对照。 */
    @GetMapping("/{id}/upstream-models")
    public Mono<R<ChannelUpstreamModelsVo>> upstreamModels(@PathVariable Long id) {
        return Mono.fromCallable(() -> R.ok(upstreamModelService.fetch(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 批量注册勾选的上游模型为本渠道 ability（default 组·已存在跳过）。 */
    @PostMapping("/{id}/upstream-models/register")
    public Mono<R<Integer>> registerUpstreamModels(@PathVariable Long id,
                                                   @RequestBody RegisterUpstreamModelsRequest req) {
        return Mono.fromCallable(() -> {
            int created = upstreamModelService.register(id, req.models() == null ? List.of() : req.models());
            if (created > 0) {
                publishConfigChanged();
            }
            return R.ok(created);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道测速探活（G3）：探上游连通性 + 鉴权 + 延迟（不计费）。 */
    @PostMapping("/{id}/test")
    public Mono<R<ChannelTestResultVo>> test(@PathVariable Long id) {
        return Mono.fromCallable(() -> R.ok(channelTestService.test(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道余额监控（提供商页余额列）：查上游 OpenAI 兼容计费端点（结果缓存 5 分钟·force 绕过）。 */
    @GetMapping("/{id}/balance")
    public Mono<R<ChannelBalanceVo>> balance(@PathVariable Long id,
                                             @RequestParam(defaultValue = "false") boolean force) {
        return Mono.fromCallable(() -> R.ok(channelBalanceService.balance(id, force)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 触发配置缓存失效（本节点 + 跨实例广播）；失败不阻塞渠道写入（下次 TTL/手动刷新生效）。 */
    private void publishConfigChanged() {
        configChangePublisher.publish();
    }
}
