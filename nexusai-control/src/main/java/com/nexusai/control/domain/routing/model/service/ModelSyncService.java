package com.nexusai.control.domain.routing.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.routing.model.vo.ModelSyncConflictVo;
import com.nexusai.control.domain.routing.model.vo.ModelSyncMetaVo;
import com.nexusai.control.domain.routing.model.vo.ModelSyncPreviewVo;
import com.nexusai.control.domain.routing.model.vo.ModelSyncResultVo;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.po.NaiModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模型上游元数据同步（数据源 models.dev）：
 * 上新模型不必手填——<b>preview</b> 算出「缺失模型」（路由已登记但目录未建·上游有元数据）与「字段冲突」
 * （本地 {@code sync_official=1} 模型 vs 上游：别名/上下文/输出上限/三费率逐字段 diff），
 * <b>sync</b> 按勾选建缺失模型 + 覆盖差异字段。{@code sync_official=0} 的模型本地锁定不参与。
 * <p>不选聚合型元数据源：同名模型 2500+ 重复（多聚合商各报一份）且描述为模板文案；
 * models.dev 按 provider 维度归一，同名条目按「官方 provider 优先 → cost 完整优先」择优。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class ModelSyncService {

    /** models.dev 官方一方 provider（择优时优先于聚合商/云转售条目） */
    private static final Set<String> OFFICIAL_PROVIDERS = Set.of(
            "anthropic", "openai", "google", "deepseek", "xai", "mistral", "alibaba",
            "moonshotai", "zhipuai", "minimax", "cohere", "meta", "amazon-bedrock", "azure");

    /** sync_official=0 表示本地锁定·不参与上游覆盖 */
    private static final int SYNC_OFFICIAL_LOCKED = 0;
    private static final int OFFICIAL_PROVIDER_SCORE = 100;
    private static final int COST_INPUT_SCORE = 10;
    private static final int COST_OUTPUT_SCORE = 10;
    private static final int COST_CACHE_READ_SCORE = 1;

    private final NaiModelMapper modelMapper;
    private final ModelSummaryService summaryService;
    private final ModelsDevCatalogService catalogService;

    /** 预览：缺失模型 + 字段冲突 diff。 */
    public ModelSyncPreviewVo preview() {
        Map<String, ModelSyncMetaVo> upstream = upstreamIndex();

        // 缺失模型（missing ∩ 上游存在）
        List<ModelSyncMetaVo> missing = new ArrayList<>();
        for (String name : summaryService.missingModels()) {
            ModelSyncMetaVo meta = upstream.get(name);
            if (meta != null) {
                missing.add(meta);
            }
        }

        // 字段冲突（仅 sync_official=1 的本地模型）
        List<ModelSyncConflictVo> conflicts = new ArrayList<>();
        for (NaiModel local : modelMapper.selectList(null)) {
            if (local.getSyncOfficial() != null && local.getSyncOfficial() == SYNC_OFFICIAL_LOCKED) {
                continue;
            }
            ModelSyncMetaVo up = upstream.get(local.getModelKey());
            if (up == null) {
                continue;
            }
            List<ModelSyncConflictVo.FieldDiff> fields = new ArrayList<>();
            diff(fields, "modelAlias", local.getModelAlias(), up.modelAlias());
            diff(fields, "contextLength", local.getContextLength(), up.contextLength());
            diff(fields, "maxOutputTokens", local.getMaxOutputTokens(), up.maxOutputTokens());
            diffRate(fields, "inputRate", local.getInputRate(), up.inputRate());
            diffRate(fields, "outputRate", local.getOutputRate(), up.outputRate());
            diffRate(fields, "cachedInputRate", local.getCachedInputRate(), up.cachedInputRate());
            if (!fields.isEmpty()) {
                conflicts.add(new ModelSyncConflictVo(local.getModelKey(), up.providerId(), fields));
            }
        }

        return new ModelSyncPreviewVo(missing, conflicts);
    }

    /**
     * 执行同步：createKeys=勾选要新建的缺失模型；overwrite={modelKey→勾选覆盖的字段名集合}。
     * 返回 {created, updated, skipped}。
     */
    public ModelSyncResultVo sync(List<String> createKeys, Map<String, List<String>> overwrite) {
        Map<String, ModelSyncMetaVo> upstream = upstreamIndex();
        int created = 0;
        int updated = 0;
        List<String> skipped = new ArrayList<>();

        if (createKeys != null) {
            for (String key : createKeys) {
                ModelSyncMetaVo meta = upstream.get(key);
                if (meta == null || exists(key)) {
                    skipped.add(key);
                    continue;
                }
                NaiModel model = new NaiModel();
                model.setModelKey(meta.modelKey());
                model.setModelAlias(meta.modelAlias());
                model.setModality(meta.modality());
                model.setContextLength(meta.contextLength());
                model.setMaxOutputTokens(meta.maxOutputTokens());
                model.setInputRate(meta.inputRate());
                model.setOutputRate(meta.outputRate());
                model.setCachedInputRate(meta.cachedInputRate());
                model.setStatus(1);
                model.setSyncOfficial(1);
                modelMapper.insert(model);
                created++;
            }
        }

        if (overwrite != null) {
            for (Map.Entry<String, List<String>> entry : overwrite.entrySet()) {
                ModelSyncMetaVo up = upstream.get(entry.getKey());
                NaiModel local = modelMapper.selectOne(
                        new LambdaQueryWrapper<NaiModel>().eq(NaiModel::getModelKey, entry.getKey()));
                if (up == null || local == null
                        || (local.getSyncOfficial() != null && local.getSyncOfficial() == SYNC_OFFICIAL_LOCKED)) {
                    skipped.add(entry.getKey());
                    continue;
                }
                List<String> fields = entry.getValue() == null ? List.of() : entry.getValue();
                boolean dirty = false;
                if (fields.contains("modelAlias")) {
                    local.setModelAlias(up.modelAlias());
                    dirty = true;
                }
                if (fields.contains("contextLength")) {
                    local.setContextLength(up.contextLength());
                    dirty = true;
                }
                if (fields.contains("maxOutputTokens")) {
                    local.setMaxOutputTokens(up.maxOutputTokens());
                    dirty = true;
                }
                if (fields.contains("inputRate")) {
                    local.setInputRate(up.inputRate());
                    dirty = true;
                }
                if (fields.contains("outputRate")) {
                    local.setOutputRate(up.outputRate());
                    dirty = true;
                }
                if (fields.contains("cachedInputRate")) {
                    local.setCachedInputRate(up.cachedInputRate());
                    dirty = true;
                }
                if (dirty) {
                    modelMapper.updateById(local);
                    updated++;
                }
            }
        }

        return new ModelSyncResultVo(created, updated, skipped);
    }

    // ===== 上游索引 =====

    /** 解析 models.dev 目录 → modelId 维度择优索引（官方 provider 优先 → cost 完整优先 → 先到先得）。 */
    private Map<String, ModelSyncMetaVo> upstreamIndex() {
        Map<String, ModelSyncMetaVo> index = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        JsonNode root = JsonUtils.readTree(catalogService.catalogJson());
        if (root == null || !root.isObject()) {
            return index;
        }
        for (Iterator<Map.Entry<String, JsonNode>> providers = root.fields(); providers.hasNext(); ) {
            Map.Entry<String, JsonNode> providerEntry = providers.next();
            String providerId = providerEntry.getKey();
            JsonNode models = providerEntry.getValue().path("models");
            if (!models.isObject()) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = models.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> modelEntry = it.next();
                JsonNode m = modelEntry.getValue();
                if (m.path("deprecated").asBoolean(false)) {
                    continue;
                }
                String modelId = modelEntry.getKey();
                int score = score(providerId, m);
                Integer best = rank.get(modelId);
                if (best != null && best >= score) {
                    continue;
                }
                rank.put(modelId, score);
                index.put(modelId, toMeta(modelId, providerId, m));
            }
        }
        return index;
    }

    private static int score(String providerId, JsonNode m) {
        int score = 0;
        if (OFFICIAL_PROVIDERS.contains(providerId)) {
            score += OFFICIAL_PROVIDER_SCORE;
        }
        JsonNode cost = m.path("cost");
        if (cost.hasNonNull("input")) {
            score += COST_INPUT_SCORE;
        }
        if (cost.hasNonNull("output")) {
            score += COST_OUTPUT_SCORE;
        }
        if (cost.hasNonNull("cache_read")) {
            score += COST_CACHE_READ_SCORE;
        }
        return score;
    }

    private static ModelSyncMetaVo toMeta(String modelId, String providerId, JsonNode m) {
        JsonNode cost = m.path("cost");
        JsonNode limit = m.path("limit");
        boolean embedding = modelId.toLowerCase().contains("embedding")
                || m.path("name").asText("").toLowerCase().contains("embedding");
        boolean imageOut = false;
        for (JsonNode o : m.path("output")) {
            if ("image".equals(o.asText())) {
                imageOut = true;
            }
        }
        return new ModelSyncMetaVo(
                modelId,
                m.path("name").asText(modelId),
                providerId,
                decimal(cost.get("input")),
                decimal(cost.get("output")),
                decimal(cost.get("cache_read")),
                intOrNull(limit.get("context")),
                intOrNull(limit.get("output")),
                embedding ? "EMBEDDING" : imageOut ? "IMAGE" : "CHAT");
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.decimalValue();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.intValue();
    }

    private boolean exists(String modelKey) {
        return modelMapper.selectCount(
                new LambdaQueryWrapper<NaiModel>().eq(NaiModel::getModelKey, modelKey)) > 0;
    }

    private static void diff(List<ModelSyncConflictVo.FieldDiff> fields, String name, Object local, Object up) {
        if (up == null || Objects.equals(local, up)) {
            return;
        }
        fields.add(new ModelSyncConflictVo.FieldDiff(name, local, up));
    }

    private static void diffRate(List<ModelSyncConflictVo.FieldDiff> fields, String name,
                                 BigDecimal local, BigDecimal up) {
        if (up == null || (local != null && local.compareTo(up) == 0)) {
            return;
        }
        fields.add(new ModelSyncConflictVo.FieldDiff(name, local, up));
    }
}
