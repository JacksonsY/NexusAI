package com.nexusai.control.domain.routing.model.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.common.model.R;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.api.contract.ConfigChangePublisher;
import com.nexusai.control.domain.routing.model.service.ModelRoutingService;
import com.nexusai.control.domain.routing.model.service.ModelSummaryService;
import com.nexusai.control.domain.routing.model.service.ModelSyncService;
import com.nexusai.control.domain.routing.model.service.ModelsDevCatalogService;
import com.nexusai.control.domain.routing.model.vo.AssignChannelsRequest;
import com.nexusai.control.domain.routing.model.vo.ChannelAssignResultVo;
import com.nexusai.control.domain.routing.model.vo.ModelBatchDeleteRequest;
import com.nexusai.control.domain.routing.model.vo.ModelChannelLinkVo;
import com.nexusai.control.domain.routing.model.vo.ModelRoutingVo;
import com.nexusai.control.domain.routing.model.vo.ModelRowVo;
import com.nexusai.control.domain.routing.model.vo.ModelSyncPreviewVo;
import com.nexusai.control.domain.routing.model.vo.ModelSyncResultVo;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.po.NaiModel;
import com.nexusai.router.model.ModelAliasResolver;
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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模型目录运营（/admin/models·含缺失模型检测）：
 * 分页列表带三统计聚合 / CRUD（含别名正则校验）/ 启停 / 批量删除 / 渠道关联对账 / 链路预览 /
 * models.dev 目录代理。结构化模型价格真相在本地 nai_model，控制面直接编辑后由数据面读取。
 * <p>写库（create/update/delete/batchDelete/assignChannels/sync）后广播配置变更：使数据面
 * {@code ModelAliasResolver}（status/alias）、{@code ModelPriceService}（价格）、{@code RouteCache}（渠道关联）
 * 本地缓存即时失效（本节点 + 跨实例），不广播则停用模型仍被路由、别名/价格改动滞后一个 TTL 周期。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/models")
@RequiredArgsConstructor
public class ModelAdminController {

    private final NaiModelMapper modelMapper;
    private final ModelSummaryService summaryService;
    private final ModelRoutingService routingService;
    private final ModelsDevCatalogService catalogService;
    private final ModelSyncService syncService;
    private final ConfigChangePublisher configChangePublisher;

    /** 模型分页列表（每行 = model + 渠道计数 active/total + 近 30 天调用次数）。 */
    @GetMapping
    public Mono<R<PageResult<ModelRowVo>>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "20") long pageSize,
                                                @RequestParam(required = false) String search,
                                                @RequestParam(required = false) Integer status) {
        PageQuery pq = PageQuery.of(pageNum, pageSize);
        return Mono.fromCallable(() -> R.ok(
                        summaryService.page(pq.getPageNum(), pq.getPageSize(), search, status)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 单模型详情（含统计·详情抽屉）。 */
    @GetMapping("/{id}")
    public Mono<R<ModelRowVo>> detail(@PathVariable Long id) {
        return Mono.fromCallable(() -> R.ok(summaryService.detail(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 缺失模型检测：路由表已登记但目录未建 → 计费口径缺口。 */
    @GetMapping("/missing")
    public Mono<R<List<String>>> missing() {
        return Mono.fromCallable(() -> R.ok(summaryService.missingModels()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 同步预览：缺失模型 + 本地 sync_official=1 模型的字段冲突 diff。 */
    @GetMapping("/sync/preview")
    public Mono<R<ModelSyncPreviewVo>> syncPreview() {
        return Mono.fromCallable(() -> R.ok(syncService.preview()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 执行同步：勾选建缺失模型 + 勾选字段覆盖。 */
    @PostMapping("/sync")
    public Mono<R<ModelSyncResultVo>> syncUpstream(@RequestBody SyncRequest req) {
        return Mono.fromCallable(() -> {
            ModelSyncResultVo result = syncService.sync(req.createKeys(), req.overwrite());
            configChangePublisher.publish();
            return R.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 同步请求体：createKeys=要新建的缺失模型；overwrite=modelKey→覆盖字段名集合。 */
    public record SyncRequest(List<String> createKeys, Map<String, List<String>> overwrite) {
    }

    /** models.dev 目录代理（创建对话框左侧目录树·R 包 JsonNode 适配前端统一拆包）。 */
    @GetMapping("/external")
    public Mono<R<JsonNode>> external() {
        return Mono.fromCallable(() -> R.ok(JsonUtils.readTree(catalogService.catalogJson())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<R<Long>> create(@RequestBody NaiModel model) {
        return Mono.fromCallable(() -> {
            validateAliasPatterns(model.getAliasPatterns());
            model.setId(null);
            if (model.getStatus() == null) {
                model.setStatus(1);
            }
            modelMapper.insert(model);
            configChangePublisher.publish();
            return R.ok(model.getId());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody NaiModel model) {
        return Mono.fromCallable(() -> {
            validateAliasPatterns(model.getAliasPatterns());
            model.setId(id);
            modelMapper.updateById(model);
            configChangePublisher.publish();
            return R.<Void>ok(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            modelMapper.deleteById(id);
            configChangePublisher.publish();
            return R.<Void>ok(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 批量删除（批量管理对话框）。 */
    @PostMapping("/batch-delete")
    public Mono<R<Integer>> batchDelete(@RequestBody ModelBatchDeleteRequest body) {
        return Mono.fromCallable(() -> {
            int n = summaryService.batchDelete(body.ids());
            configChangePublisher.publish();
            return R.ok(n);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 模型的关联渠道列表（详情抽屉）。 */
    @GetMapping("/{modelKey}/channels")
    public Mono<R<List<ModelChannelLinkVo>>> channels(@PathVariable String modelKey) {
        return Mono.fromCallable(() -> R.ok(summaryService.channelsOf(modelKey)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 渠道关联声明式对账（选中集合即目标态·缺补多删）。 */
    @PutMapping("/{modelKey}/channels")
    public Mono<R<ChannelAssignResultVo>> assignChannels(@PathVariable String modelKey,
                                                         @RequestBody AssignChannelsRequest body) {
        return Mono.fromCallable(() -> {
            ChannelAssignResultVo result = summaryService.assignChannels(modelKey, body.channelIds());
            configChangePublisher.publish();
            return R.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 链路预览（详情抽屉「链路控制」Tab·两级调度真实顺序）。 */
    @GetMapping("/{modelKey}/routing")
    public Mono<R<ModelRoutingVo>> routing(@PathVariable String modelKey) {
        return Mono.fromCallable(() -> R.ok(routingService.preview(modelKey)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 别名正则校验（JSON 数组 ≤{@link ModelAliasResolver#MAX_PATTERNS_PER_MODEL} 条·逐条可编译）。 */
    private static void validateAliasPatterns(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        List<String> patterns;
        try {
            patterns = JsonUtils.parse(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("alias_patterns 须为 JSON 字符串数组");
        }
        if (patterns.size() > ModelAliasResolver.MAX_PATTERNS_PER_MODEL) {
            throw new IllegalArgumentException("别名规则最多 " + ModelAliasResolver.MAX_PATTERNS_PER_MODEL + " 条");
        }
        for (String p : patterns) {
            try {
                Pattern.compile(p);
            } catch (Exception e) {
                throw new IllegalArgumentException("别名正则非法: " + p);
            }
        }
    }
}
