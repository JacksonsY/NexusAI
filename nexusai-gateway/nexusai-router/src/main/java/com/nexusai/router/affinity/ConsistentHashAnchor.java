package com.nexusai.router.affinity;

import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.router.algo.ConsistentHashRingCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 渠道密钥「①锚定」（四层选号第一层）：把会话亲和键经一致性哈希
 * （{@link ConsistentHashRingCache}）<b>确定性</b>映射到候选 Key——同会话冷启动即锚定同一 Key（命中上游 prompt cache）免预热；
 * 池内 Key 增删只重哈希约 1/N（vs 取模几乎全洗牌）。环按候选集签名缓存复用·免每请求重建。
 *
 * <p>锚定仅给出「首选」：该 Key 槽满/熔断/劣化时，调用方仍按 ②粘性 / ③打分 / ④并发槽兜底，不会卡死。
 * {@code nexusai.pool.anchor.strategy=none} 关闭 → 回退纯打分（行为同改造前）。
 * 单实例语义即足；多实例下各自构环但哈希一致 → 同亲和键跨实例锚同一 Key（无需共享状态）。
 *
 * @author nexusai
 */
@Component
public class ConsistentHashAnchor {

    /** 一致性哈希锚定策略标识。 */
    private static final String CONSISTENT_HASH = "consistent-hash";

    private final boolean enabled;
    private final ConsistentHashRingCache<NaiChannelKey> ringCache;

    public ConsistentHashAnchor(
            @Value("${nexusai.pool.anchor.strategy:consistent-hash}") String strategy,
            @Value("${nexusai.pool.anchor.vnodes:160}") int virtualNodes) {
        this.enabled = CONSISTENT_HASH.equalsIgnoreCase(strategy == null ? "" : strategy.trim());
        this.ringCache = new ConsistentHashRingCache<>(k -> String.valueOf(k.getId()), virtualNodes);
    }

    /**
     * 锚定：亲和键 → 候选内某 Key 的 id。未启用 / 无亲和键 / 候选空 → {@code null}（调用方回退打分）。
     */
    public Long anchor(List<NaiChannelKey> candidates, String affinity) {
        if (!enabled || affinity == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        NaiChannelKey hit = ringCache.ring(candidates).route(affinity);
        return hit == null ? null : hit.getId();
    }
}
