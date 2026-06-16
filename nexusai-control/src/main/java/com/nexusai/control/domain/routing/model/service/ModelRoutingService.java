package com.nexusai.control.domain.routing.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.routing.model.vo.ModelRoutingChannelVo;
import com.nexusai.control.domain.routing.model.vo.ModelRoutingGroupVo;
import com.nexusai.control.domain.routing.model.vo.ModelRoutingKeyVo;
import com.nexusai.control.domain.routing.model.vo.ModelRoutingVo;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.router.config.SchedulingSettings;
import com.nexusai.router.health.ChannelHealthManager;
import com.nexusai.router.health.KeyHealthManager;
import com.nexusai.router.health.RoutingHealthStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型链路预览（详情抽屉「链路控制」Tab）：
 * 给定模型，按<b>协议家族分组</b>还原两级调度真实顺序——第一级渠道（priority 降序·priorityMode 决定取
 * 渠道 priority 还是路由表 priority）→ 第二级渠道内 Key 池（priority/weight + 运行时健康分/熔断）。
 * 全部为真实调度输入（DB 配置 + {@link KeyHealthManager} 内存态），非演示数据。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRoutingService {

    /** 无运行时健康快照时的默认健康分（满分） */
    private static final int DEFAULT_HEALTH_SCORE = 100;
    /** 协议未知（渠道缺失或未配置 protocol）时的分组键 */
    private static final String UNKNOWN_PROTOCOL = "UNKNOWN";
    /** 渠道启用状态值 */
    private static final int STATUS_ENABLED = 1;
    /** 凭证指纹：短于此长度直接全掩码·保留尾部位数 */
    private static final int MASK_MIN_LENGTH = 8;
    private static final int MASK_SUFFIX_LENGTH = 4;
    private static final String MASK_PREFIX = "***";

    private final NaiAbilityMapper abilityMapper;
    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper channelKeyMapper;
    private final SchedulingSettings schedulingSettings;
    private final ChannelHealthManager channelHealth;
    private final KeyHealthManager keyHealth;

    /** 链路预览：按协议家族分组的两级调度真实顺序（第一级选渠道 → 第二级渠道密钥池）。 */
    public ModelRoutingVo preview(String modelKey) {
        long now = System.currentTimeMillis();
        boolean byChannelPriority = SchedulingSettings.PRIORITY_PROVIDER.equals(schedulingSettings.priorityMode());

        List<NaiAbility> abilities = abilityMapper.selectList(
                new LambdaQueryWrapper<NaiAbility>().eq(NaiAbility::getModel, modelKey));

        Map<Long, NaiChannel> channels = new HashMap<>();
        if (!abilities.isEmpty()) {
            for (NaiChannel channel : channelMapper.selectBatchIds(
                    abilities.stream().map(NaiAbility::getChannelId).distinct().toList())) {
                channels.put(channel.getId(), channel);
            }
        }
        Map<Long, List<NaiChannelKey>> keysByChannel = new HashMap<>();
        if (!channels.isEmpty()) {
            for (NaiChannelKey key : channelKeyMapper.selectList(new LambdaQueryWrapper<NaiChannelKey>()
                    .in(NaiChannelKey::getChannelId, channels.keySet()).orderByAsc(NaiChannelKey::getId))) {
                keysByChannel.computeIfAbsent(key.getChannelId(), k -> new ArrayList<>()).add(key);
            }
        }

        // 渠道行（含调度排序键）→ 按协议家族分组
        record ChannelEntry(NaiAbility ability, NaiChannel channel, int sortPriority) {
        }
        Map<String, List<ChannelEntry>> byProtocol = new LinkedHashMap<>();
        for (NaiAbility ability : abilities) {
            NaiChannel channel = channels.get(ability.getChannelId());
            int priority = byChannelPriority && channel != null && channel.getPriority() != null
                    ? channel.getPriority()
                    : (ability.getPriority() == null ? 0 : ability.getPriority());
            String protocol = channel == null || channel.getProtocol() == null
                    ? UNKNOWN_PROTOCOL : channel.getProtocol();
            byProtocol.computeIfAbsent(protocol, k -> new ArrayList<>())
                    .add(new ChannelEntry(ability, channel, priority));
        }

        List<ModelRoutingGroupVo> groups = new ArrayList<>();
        for (Map.Entry<String, List<ChannelEntry>> entry : byProtocol.entrySet()) {
            List<ChannelEntry> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ChannelEntry::sortPriority).reversed()
                            .thenComparing(e -> e.channel() == null || e.channel().getWeight() == null
                                    ? 0 : -e.channel().getWeight()))
                    .toList();

            long totalKeys = 0;
            long activeKeys = 0;
            long activeChannels = 0;
            List<ModelRoutingChannelVo> channelRows = new ArrayList<>(sorted.size());
            for (ChannelEntry ce : sorted) {
                NaiChannel channel = ce.channel();
                Long channelId = ce.ability().getChannelId();
                boolean channelEnabled = channel != null && channel.getStatus() != null
                        && channel.getStatus() == STATUS_ENABLED;
                boolean abilityEnabled = Boolean.TRUE.equals(ce.ability().getEnabled());
                if (channelEnabled && abilityEnabled) {
                    activeChannels++;
                }

                List<ModelRoutingKeyVo> keyRows = new ArrayList<>();
                for (NaiChannelKey key : keysByChannel.getOrDefault(channelId, List.of())) {
                    totalKeys++;
                    boolean keyEnabled = key.getStatus() != null && key.getStatus() == STATUS_ENABLED;
                    boolean schedulable = keyEnabled && keyHealth.schedulable(key.getId(), now);
                    if (schedulable) {
                        activeKeys++;
                    }
                    RoutingHealthStat stat = keyHealth.peek(key.getId());
                    keyRows.add(new ModelRoutingKeyVo(
                            key.getId(),
                            mask(key.getCredential()),
                            key.getStatus(),
                            key.getPriority(),
                            key.getWeight(),
                            stat == null ? DEFAULT_HEALTH_SCORE : stat.healthScore(),
                            stat == null ? 0L : Math.round(stat.latencyEwmaMs()),
                            schedulable,
                            stat == null ? 0L : stat.blockedUntil()));
                }
                // Key 优先模式下池内按 priority 降序展示（与 KeyPoolSelector 同向）
                keyRows.sort(Comparator.comparingInt(
                        r -> r.priority() == null ? 0 : -r.priority()));

                RoutingHealthStat channelStat = channelHealth.peek(channelId);
                channelRows.add(new ModelRoutingChannelVo(
                        channelId,
                        channel == null ? "渠道#" + channelId : channel.getName(),
                        channel == null ? null : channel.getBaseUrl(),
                        ce.sortPriority(),
                        channel == null || channel.getWeight() == null ? 0 : channel.getWeight(),
                        channel == null ? 0 : channel.getStatus(),
                        abilityEnabled,
                        resolveTargetModel(channel, modelKey),
                        channelStat == null ? DEFAULT_HEALTH_SCORE : channelStat.healthScore(),
                        channelEnabled && channelHealth.schedulable(channelId, now),
                        keyRows));
            }

            groups.add(new ModelRoutingGroupVo(
                    entry.getKey(), sorted.size(), activeChannels, totalKeys, activeKeys, channelRows));
        }

        return new ModelRoutingVo(
                schedulingSettings.schedulingMode(), schedulingSettings.priorityMode(), now, groups);
    }

    /** 凭证指纹（与 CredentialAdminService 同口径：仅回尾 4 位）。 */
    private static String mask(String cipher) {
        if (cipher == null || cipher.length() < MASK_MIN_LENGTH) {
            return MASK_PREFIX;
        }
        return MASK_PREFIX + cipher.substring(cipher.length() - MASK_SUFFIX_LENGTH);
    }

    /** 渠道出站 model_mapping 解析（展示该渠道实际转发的上游模型名）。 */
    private static String resolveTargetModel(NaiChannel channel, String model) {
        if (channel == null || channel.getModelMapping() == null || channel.getModelMapping().isBlank()) {
            return model;
        }
        try {
            Map<String, String> mapping = JsonUtils.parse(channel.getModelMapping(),
                    new TypeReference<Map<String, String>>() {
                    });
            return mapping.getOrDefault(model, model);
        } catch (Exception e) {
            // 渠道 model_mapping 配置非法 JSON：展示口径降级回原模型名（不阻断链路预览渲染）
            log.debug("渠道 {} model_mapping 解析失败，目标模型回退为原名: {}",
                    channel.getId(), e.getMessage());
            return model;
        }
    }
}
