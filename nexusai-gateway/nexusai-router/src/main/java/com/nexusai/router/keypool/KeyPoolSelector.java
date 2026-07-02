package com.nexusai.router.keypool;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.limit.ConcurrencySlot;
import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.router.affinity.ConsistentHashAnchor;
import com.nexusai.router.affinity.StickyAffinity;
import com.nexusai.router.algo.TopKWeightedOrder;
import com.nexusai.router.cache.RouteCache;
import com.nexusai.router.health.KeyHealthManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 第二级调度：渠道内渠道密钥四层选号：
 * ① 锚定（{@link ConsistentHashAnchor} 一致性哈希把亲和键确定性映射到某 Key·冷启动即同 Key）→
 * ② 粘性（{@link StickyAffinity} 上次成功 Key 短记忆·更具体优先）→
 * ③ 加权 TopK（{@link KeyPoolScorer} 多因子打分 + {@link TopKWeightedOrder} 纯算法排序）→
 * ④ 并发槽（{@link ConcurrencySlot} ZSET+Lua 原子占用）。
 * <p>过滤：状态启用（缓存已滤）+ 不在 exclude（容错换 Key）+ {@link KeyHealthManager} 熔断惰性复活（半开单飞探测·防复活即被并发打爆）。
 * 全耗尽 → 显式拒绝（不静默兜底）。
 * <p>层序说明：②粘性命中即用（反映真实最近成功 = prompt cache 实际所在）；冷启动/粘性失效时 ①锚定给确定性首选，
 * 二者都只是「首选」，槽满/劣化都会落到 ③④ 兜底，绝不卡死。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeyPoolSelector {

    /** 加权随机的候选头部规模 */
    private static final int TOP_K = 3;

    /** ①锚定置顶的负载安全闸：锚定 Key 打分须 ≥ 最高分 × 此比例才置顶，否则让位负载安全（回退打分序）。 */
    public static final double ANCHOR_MIN_SCORE_RATIO = 0.5;

    private final RouteCache routeCache;
    private final KeyHealthManager healthManager;
    private final KeyPoolScorer scorer;
    private final StickyAffinity stickyAffinity;
    private final ConsistentHashAnchor consistentHashAnchor;
    private final ConcurrencySlot concurrencySlot;

    /** 选号结果：选中 Key + 已占用的并发槽令牌。 */
    public record Selection(NaiChannelKey key, String slotToken) {
    }

    public Selection select(Long channelId, String affinity, Set<Long> excludeKeyIds) {
        long now = System.currentTimeMillis();
        List<NaiChannelKey> candidates = routeCache.keys(channelId).stream()
                .filter(k -> excludeKeyIds == null || !excludeKeyIds.contains(k.getId()))
                // 熔断惰性复活 + 半开单飞：熔断刚到期的 Key 只放一个探测请求入候选，其余并发请求视为不可用（防探测风暴）。
                // tryAcquireForDispatch 有副作用（CAS 抢探测名额），故仅数据面选号调用，不与控制面纯读 schedulable 混用。
                .filter(k -> healthManager.tryAcquireForDispatch(k.getId(), now))
                .toList();
        if (candidates.isEmpty()) {
            throw GatewayException.of(GatewayErrorCode.NO_KEY_AVAILABLE,
                    "渠道内无可用 Key（渠道密钥耗尽/全熔断）: channelId=" + channelId);
        }

        // ② 粘性：优先复用上次成功 Key（最具体的 prompt cache 信号，仍在候选 + 槽可占）
        Long sticky = stickyAffinity.get(channelId, affinity);
        if (sticky != null) {
            NaiChannelKey hit = candidates.stream().filter(k -> k.getId().equals(sticky)).findFirst().orElse(null);
            if (hit != null) {
                String token = tryOccupy(hit);
                if (token != null) {
                    stickyAffinity.recordHit();
                    stickyAffinity.put(channelId, affinity, hit.getId());   // 滚动 TTL + 复用计数
                    return new Selection(hit, token);
                }
                stickyAffinity.evictForSwitch(channelId, affinity);   // 劣化逃逸（槽满换号）
            } else {
                stickyAffinity.evictForInvalidation(channelId, affinity);   // 粘性 Key 被熔断/下线滤出候选
            }
        }

        // ① 锚定：一致性哈希把亲和键确定性映射到候选某 Key（粘性冷/失效时仍同 Key·命中 prompt cache）
        Long anchorId = consistentHashAnchor.anchor(candidates, affinity);

        // ③ 加权 TopK：多因子打分 → 纯算法排序（头部加权随机定主选 + ①锚定负载安全置顶）
        List<NaiChannelKey> ordered = TopKWeightedOrder.order(candidates,
                k -> scorer.score(k, healthManager.stat(k.getId())), TOP_K,
                anchorId == null ? null : k -> anchorId.equals(k.getId()), ANCHOR_MIN_SCORE_RATIO);

        // ④ 并发槽：按序尝试占用，首个成功者中选
        for (NaiChannelKey k : ordered) {
            String token = tryOccupy(k);
            if (token != null) {
                stickyAffinity.put(channelId, affinity, k.getId());
                return new Selection(k, token);
            }
        }
        // 全部槽满 = 并发饱和
        throw GatewayException.of(GatewayErrorCode.RATE_LIMITED,
                "渠道内 Key 并发槽均已占满: channelId=" + channelId);
    }

    private String tryOccupy(NaiChannelKey k) {
        int max = k.getMaxConcurrency() == null ? 0 : k.getMaxConcurrency();
        String token = max <= 0 ? "" : UUID.randomUUID().toString();
        return concurrencySlot.tryOccupy(k.getId(), max, token) ? token : null;
    }
}
