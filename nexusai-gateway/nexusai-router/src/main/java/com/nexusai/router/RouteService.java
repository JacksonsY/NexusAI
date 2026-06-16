package com.nexusai.router;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.limit.ConcurrencySlot;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.router.channel.ChannelRouter;
import com.nexusai.router.keypool.KeyPoolSelector;
import com.nexusai.router.keypool.KeyPoolSelector.Selection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 两级调度编排：先选渠道（ChannelRouter）→ 渠道内选号（KeyPoolSelector 四层）→ model_mapping 解析上游真实模型名。
 * 支持 exclude 集合（故障转移换渠道/换 Key）+ 会话亲和（粘性 prompt cache）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

    private static final int MODEL_MAPPING_CACHE_MAX_SIZE = 1024;
    private static final TypeReference<Map<String, String>> MODEL_MAPPING_TYPE = new TypeReference<>() {
    };

    private final ChannelRouter channelRouter;
    private final KeyPoolSelector keyPoolSelector;
    private final ConcurrencySlot concurrencySlot;
    private final Cache<ModelMappingCacheKey, Map<String, String>> modelMappingCache = Caffeine.newBuilder()
            .maximumSize(MODEL_MAPPING_CACHE_MAX_SIZE)
            .build();

    private record ModelMappingCacheKey(Long channelId, String json) {
    }

    public RouteResult route(String group, String model, String affinity,
                             Set<Long> excludeChannelIds, Set<Long> excludeKeyIds) {
        // 🔴 选中渠道的 Key 全耗尽/全满（NO_KEY_AVAILABLE/RATE_LIMITED）时，排除该渠道改选下一优先级渠道——
        // 而非直接终态失败：否则高优渠道 Key 饱和会让本可服务的低优渠道整批闲置（可用性缺陷）。
        // 候选渠道耗尽时 selectChannel 自身抛 NO_CHANNEL_AVAILABLE 终态，循环必然收敛。
        Set<Long> exChannels = excludeChannelIds == null ? new HashSet<>() : new HashSet<>(excludeChannelIds);
        while (true) {
            NaiChannel channel = channelRouter.selectChannel(group, model, affinity, exChannels);
            try {
                Selection sel = keyPoolSelector.select(channel.getId(), affinity, excludeKeyIds);
                String targetModel = resolveModel(channel, model);
                if (log.isDebugEnabled()) {
                    log.debug("route: model={} -> channel={}({}) key={} targetModel={}",
                            model, channel.getName(), channel.getId(), sel.key().getId(), targetModel);
                }
                return new RouteResult(channel, sel.key(), targetModel, sel.slotToken());
            } catch (GatewayException e) {
                if (e.getErrorCode() == GatewayErrorCode.NO_KEY_AVAILABLE
                        || e.getErrorCode() == GatewayErrorCode.RATE_LIMITED) {
                    exChannels.add(channel.getId());
                    continue;
                }
                throw e;
            }
        }
    }

    /** 释放选号时占用的并发槽（与 {@link #route} 的占槽对称·无槽 token 为 no-op）。 */
    public void releaseSlot(RouteResult route) {
        if (route != null && route.slotToken() != null && !route.slotToken().isEmpty()) {
            concurrencySlot.release(route.key().getId(), route.slotToken());
        }
    }

    /** 档② model_mapping：渠道内模型名重映射。 */
    private String resolveModel(NaiChannel channel, String model) {
        String json = channel.getModelMapping();
        if (json == null || json.isBlank()) {
            return model;
        }
        Map<String, String> mapping = modelMappingCache.get(new ModelMappingCacheKey(channel.getId(), json),
                key -> parseModelMapping(channel, json));
        return mapping.getOrDefault(model, model);
    }

    private Map<String, String> parseModelMapping(NaiChannel channel, String json) {
        try {
            Map<String, String> mapping = JsonUtils.parse(json, MODEL_MAPPING_TYPE);
            if (mapping == null || mapping.isEmpty()) {
                return Map.of();
            }
            Map<String, String> cleaned = new HashMap<>(mapping.size());
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    cleaned.put(entry.getKey(), entry.getValue());
                }
            }
            return Map.copyOf(cleaned);
        } catch (Exception e) {
            log.warn("model_mapping 解析失败 channelId={}, 原样使用入站模型名", channel.getId());
            return Map.of();
        }
    }

    /** 配置变更后清空解析缓存；RouteCache 会同时刷新渠道实体。 */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.hits(ConfigChangedEvent.Scope.CONFIG)) {
            modelMappingCache.invalidateAll();
        }
    }
}
