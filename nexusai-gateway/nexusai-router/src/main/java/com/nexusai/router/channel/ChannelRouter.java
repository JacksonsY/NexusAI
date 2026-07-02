package com.nexusai.router.channel;

import com.nexusai.common.enums.ChannelStatus;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.router.cache.RouteCache;
import com.nexusai.router.config.SchedulingSettings;
import com.nexusai.router.health.ChannelHealthManager;
import com.nexusai.router.algo.ConsistentHashRingCache;
import com.nexusai.router.algo.WeightedPicker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 第一级调度：按 (group,model) 查 nai_ability → 最高优先级分组内按<b>调度策略</b>选渠道。
 * ability/channel 经 {@link RouteCache} 缓存；渠道级熔断 + exclude 过滤支持故障转移。
 * <p>策略与优先级层由 {@link SchedulingSettings}（优先级管理对话框）驱动：
 * <ul>
 *   <li>优先级层：{@code provider}（提供商优先·第一级按渠道 priority·默认）/
 *       {@code key}（Key 优先·第一级按路由表 priority，Key priority 在第二级选号打分生效）；</li>
 *   <li>缓存亲和 {@code cache_affinity}：同 affinity 键稳定粘渠道（利用上游 Prompt Cache·故障自动换）；</li>
 *   <li>负载均衡 {@code load_balance}：组内 weight 加权随机；</li>
 *   <li>固定顺序 {@code fixed_order}：组内 weight 降序严格取首个。</li>
 * </ul>
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class ChannelRouter {

    /** cache_affinity 渠道级一致性哈希虚拟节点数（与 Key 级锚定 nexusai.pool.anchor.vnodes 默认同量级）。 */
    private static final int AFFINITY_VNODES = 160;

    private final RouteCache routeCache;
    private final ChannelHealthManager channelHealth;
    private final SchedulingSettings schedulingSettings;
    /** cache_affinity 渠道环缓存：候选集稳定则复用同一环·免每请求重建（配置/健康变化经签名自然失效）。 */
    private final ConsistentHashRingCache<NaiAbility> affinityRingCache =
            new ConsistentHashRingCache<>(a -> String.valueOf(a.getChannelId()), AFFINITY_VNODES);

    public NaiChannel selectChannel(String group, String model, String affinity,
                                    Set<Long> excludeChannelIds) {
        long now = System.currentTimeMillis();
        List<NaiAbility> abilities = routeCache.abilities(group, model).stream()
                .filter(a -> excludeChannelIds == null || !excludeChannelIds.contains(a.getChannelId()))
                .filter(a -> channelHealth.schedulable(a.getChannelId(), now))
                .toList();
        if (abilities.isEmpty()) {
            throw GatewayException.of(GatewayErrorCode.NO_CHANNEL_AVAILABLE,
                    "无可用渠道: group=" + group + ", model=" + model);
        }
        SchedulingSettings.Policy policy = schedulingSettings.resolve(model);
        boolean byChannelPriority = SchedulingSettings.PRIORITY_PROVIDER.equals(policy.priorityMode());
        int maxPriority = abilities.stream().mapToInt(a -> priorityOf(a, byChannelPriority)).max().orElse(0);
        List<NaiAbility> top = abilities.stream()
                .filter(a -> priorityOf(a, byChannelPriority) == maxPriority)
                .toList();
        NaiAbility chosen = pickByStrategy(top, affinity, policy.schedulingMode());

        NaiChannel channel = routeCache.channel(chosen.getChannelId()).orElse(null);
        if (channel == null || channel.getStatus() == null || channel.getStatus() != ChannelStatus.ENABLED.code()) {
            throw GatewayException.of(GatewayErrorCode.NO_CHANNEL_AVAILABLE,
                    "渠道不存在或已停用: channelId=" + chosen.getChannelId());
        }
        return channel;
    }

    /** 同优先级组内按调度策略选一（cache_affinity 无亲和键时退化为负载均衡）。 */
    private NaiAbility pickByStrategy(List<NaiAbility> top, String affinity, String mode) {
        if (SchedulingSettings.MODE_FIXED_ORDER.equals(mode)) {
            return top.stream()
                    .max(Comparator.comparingInt((NaiAbility a) -> a.getWeight() == null ? 0 : a.getWeight())
                            .thenComparing(a -> a.getChannelId() == null ? 0L : -a.getChannelId()))
                    .orElse(top.get(0));
        }
        if (SchedulingSettings.MODE_CACHE_AFFINITY.equals(mode) && affinity != null && !affinity.isBlank()) {
            // 🔴 一致性哈希环（同 Key 级①锚定口径）替代取模：渠道增删/熔断只重映射约 1/N，取模则几乎全洗牌
            // → 缓存亲和大面积漂移、上游 prompt cache 全失效并踩踏单一存活渠道。环按候选集签名缓存复用·免每请求重建。
            NaiAbility hit = affinityRingCache.ring(top).route(affinity);
            return hit != null ? hit : top.get(0);
        }
        return WeightedPicker.pick(top, a -> a.getWeight() == null ? 0 : a.getWeight());
    }

    /** 优先级取值：ability 层取路由表 priority；channel 层取渠道 priority（缺失回退 ability）。 */
    private int priorityOf(NaiAbility a, boolean byChannelPriority) {
        if (byChannelPriority) {
            NaiChannel channel = routeCache.channel(a.getChannelId()).orElse(null);
            if (channel != null && channel.getPriority() != null) {
                return channel.getPriority();
            }
        }
        return a.getPriority() == null ? 0 : a.getPriority();
    }
}
