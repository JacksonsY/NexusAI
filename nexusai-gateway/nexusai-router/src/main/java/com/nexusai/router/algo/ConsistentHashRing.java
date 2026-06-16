package com.nexusai.router.algo;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 一致性哈希环（TreeMap + 虚拟节点，哈希函数用 FNV-1a 64bit 免 MessageDigest 开销）：把对象键稳定映射到环上顺时针最近的物理节点。
 *
 * <p>用途：渠道密钥「①锚定」——会话亲和键 → 渠道内某 Key 的<b>确定性</b>映射，
 * 同会话冷启动即落同 Key（命中上游 prompt cache），且池内增删 Key 只重哈希约 1/N（vs 取模几乎全洗牌）。
 * 每次 select 按当前候选 Key 即时构环；候选规模小（数个~数十），构环开销可忽略。
 * 无状态、不可变；FNV-1a 跨进程一致，多实例同亲和键锚同一 Key。
 *
 * @param <T> 物理节点类型
 * @author nexusai
 */
public final class ConsistentHashRing<T> {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final SortedMap<Long, T> ring = new TreeMap<>();

    /**
     * @param nodes        物理节点集合
     * @param keyOf        物理节点 → 稳定字符串键（如 keyId）
     * @param virtualNodes 每物理节点的虚拟节点数（越大分布越均匀，典型 100~200）
     */
    public ConsistentHashRing(Collection<T> nodes, Function<T, String> keyOf, int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes 必须 >= 1: " + virtualNodes);
        }
        for (T node : nodes) {
            String base = keyOf.apply(node);
            for (int i = 0; i < virtualNodes; i++) {
                ring.put(hash(base + '#' + i), node);
            }
        }
    }

    /** 路由：对象键顺时针落到环上最近的物理节点；环空返回 {@code null}。 */
    public T route(String objectKey) {
        if (ring.isEmpty()) {
            return null;
        }
        long h = hash(objectKey);
        SortedMap<Long, T> tail = ring.tailMap(h);
        Long slot = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(slot);
    }

    /**
     * FNV-1a 64bit + fmix64 雪崩整形：稳定、无依赖、跨进程一致。
     * <p>裸 FNV-1a 对<b>连续整数键</b>（如顺序雪花 tokenId 作亲和键）低位雪崩极弱，会把相邻键挤进环上极窄弧段
     * → 锚定严重倾斜到 1~2 个 Key。末尾追加 MurmurHash3 fmix64 finalizer 做位扩散，使相邻键均匀散布全环。
     */
    private static long hash(String key) {
        long h = FNV_OFFSET;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return fmix64(h);
    }

    /** MurmurHash3 64bit finalizer：高强度位混淆，让仅末位不同的输入也均匀散布。 */
    private static long fmix64(long h) {
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
