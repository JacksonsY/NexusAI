package com.nexusai.router.algo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一致性哈希环按候选集签名缓存（热路径复用·免每请求重建 virtualNodes×N 虚节点 TreeMap）。
 * <p>面向 cache_affinity/锚定主策略：候选集在配置事件间稳定 → 命中复用同一 {@link ConsistentHashRing}；
 * 健康态使候选子集变化时签名随之变化 → 自然新建（无需显式失效），旧子集条目由有界 Caffeine
 * （maximumSize + expireAfterAccess）LRU/空闲淘汰，防按「候选子集组合」无界增长。
 * <p>环由「候选集 + 虚节点数」唯一确定且与插入顺序无关（{@link ConsistentHashRing} 内部按哈希入 TreeMap），
 * 故签名取<b>排序后</b>的节点键拼接：同一集合不同顺序命中同一环，最大化复用。keyOf 生成的节点键为纯数字 id
 * （无分隔符），以 {@code ,} 拼接无歧义。签名计算（映射+排序+拼接）远廉于重建环，是净收益。
 *
 * @param <T> 物理节点类型
 * @author nexusai
 */
public final class ConsistentHashRingCache<T> {

    /** 缓存环数上限（候选子集组合·健康子集变化会衍生新签名·空闲淘汰之外的硬兜底防无界）。 */
    private static final int MAX_RINGS = 256;
    /** 空闲淘汰：候选子集不再出现（如健康恢复后旧子集）即空闲淘汰，防无界堆积。 */
    private static final Duration IDLE_TTL = Duration.ofMinutes(10);

    private final Function<T, String> keyOf;
    private final int virtualNodes;
    private final Cache<String, ConsistentHashRing<T>> cache = Caffeine.newBuilder()
            .maximumSize(MAX_RINGS)
            .expireAfterAccess(IDLE_TTL)
            .build();

    /**
     * @param keyOf        物理节点 → 稳定字符串键（如 id）
     * @param virtualNodes 每物理节点的虚拟节点数（越大分布越均匀，典型 100~200）
     */
    public ConsistentHashRingCache(Function<T, String> keyOf, int virtualNodes) {
        this.keyOf = keyOf;
        this.virtualNodes = Math.max(1, virtualNodes);
    }

    /** 取（无则原子构建并缓存）候选集对应的一致性哈希环。 */
    public ConsistentHashRing<T> ring(List<T> nodes) {
        return cache.get(signature(nodes), sig -> new ConsistentHashRing<>(nodes, keyOf, virtualNodes));
    }

    /** 候选集签名：排序后的节点键以 {@code ,} 拼接（顺序无关·唯一标识集合）。 */
    private String signature(List<T> nodes) {
        return nodes.stream().map(keyOf).sorted().collect(Collectors.joining(","));
    }
}
