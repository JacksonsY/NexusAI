package com.nexusai.router.affinity;

import com.nexusai.runtime.ModuleToggles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 会话亲和语义（四层选号①锚定 + ②粘性）：把"亲和键 → 上次成功 Key"短 TTL 记忆，
 * 同会话优先复用同 Key 命中上游 prompt cache；选中 Key 劣化（不可调度/槽满）时主动逃逸重选。
 * <p>亲和键由 relay 的 AffinityKeyResolver 三级提取（metadata 稳定标识 → 摘要链 → tokenId）。
 * <p>存储委托 {@link AffinityEntryStore}（memory|redis 两级）；
 * 本类只持语义：粘性读写、劣化/失效逃逸、全局计数器（粘性命中/Key 切换/渠道切换/失效）、
 * relay 成功路径元数据补写、控制台「缓存监控」快照视图。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StickyAffinity {

    /** 条目只读视图（控制台列表）。 */
    public record EntryView(String cacheKey, long channelId, String affinityKey, long keyId,
                            String projectCode, String model, String apiFormat, String clientFamily,
                            String keySource, long cachedTokens,
                            long createdAt, long expireAt, long requestCount) {
    }

    private final AffinityEntryStore store;
    /** 模块管理页运行时开关（sticky_affinity·关闭=不读不写粘性档） */
    private final ModuleToggles moduleToggles;

    private final LongAdder stickyHits = new LongAdder();
    private final LongAdder keySwitches = new LongAdder();
    private final LongAdder providerSwitches = new LongAdder();
    private final LongAdder invalidations = new LongAdder();

    public Long get(Long channelId, String affinity) {
        if (affinity == null || !moduleToggles.enabled("sticky_affinity", true)) {
            return null;
        }
        AffinityEntry e = store.lookup(key(channelId, affinity));
        return e == null ? null : e.keyId;
    }

    /** 选号成功落档：同 Key 复用 → 计数+滚动 TTL；换 Key → 新条目。 */
    public void put(Long channelId, String affinity, Long keyId) {
        if (affinity == null || keyId == null || !moduleToggles.enabled("sticky_affinity", true)) {
            return;
        }
        String k = key(channelId, affinity);
        long now = System.currentTimeMillis();
        AffinityEntry e = store.lookup(k);
        if (e != null && e.keyId == keyId) {
            e.requestCount.incrementAndGet();
            e.lastWriteAt = now;
        } else {
            e = new AffinityEntry(keyId, now);
        }
        store.store(k, e);
    }

    /** 粘性命中（选号②层直接复用上次 Key）计数。 */
    public void recordHit() {
        stickyHits.increment();
    }

    /** 劣化逃逸（粘性 Key 槽满/劣化 → 清除换号）。 */
    public void evictForSwitch(Long channelId, String affinity) {
        evict(channelId, affinity);
        keySwitches.increment();
    }

    /** 失效（粘性 Key 被熔断/下线滤出候选 → 清除）。 */
    public void evictForInvalidation(Long channelId, String affinity) {
        evict(channelId, affinity);
        invalidations.increment();
    }

    public void evict(Long channelId, String affinity) {
        if (affinity != null) {
            store.remove(key(channelId, affinity));
        }
    }

    /** 渠道切换（故障转移把渠道排除换选·relay 埋点）。 */
    public void recordProviderSwitch() {
        providerSwitches.increment();
    }

    /** relay 成功路径补写展示元数据（项目/模型/API 格式/客户端/键来源）+ 累计该会话缓存命中 tokens。 */
    public void attachMeta(Long channelId, String affinity, String projectCode,
                           String model, String apiFormat, String clientFamily,
                           String keySource, long cachedTokens) {
        if (affinity == null) {
            return;
        }
        String k = key(channelId, affinity);
        AffinityEntry e = store.lookup(k);
        if (e == null) {
            return;
        }
        e.projectCode = projectCode;
        e.model = model;
        e.apiFormat = apiFormat;
        e.clientFamily = clientFamily;
        e.keySource = keySource;
        if (cachedTokens > 0) {
            e.cachedTokens.addAndGet(cachedTokens);
        }
        store.store(k, e);
    }

    /** 活跃条目快照（按到期时间降序·控制台「缓存监控」列表）。 */
    public List<EntryView> entries() {
        Map<String, AffinityEntry> all = store.scanAll();
        List<EntryView> list = new ArrayList<>(all.size());
        long ttlMs = store.ttlSeconds() * 1000L;
        for (Map.Entry<String, AffinityEntry> me : all.entrySet()) {
            String[] parts = me.getKey().split("\\|", 2);
            AffinityEntry e = me.getValue();
            list.add(new EntryView(me.getKey(),
                    Long.parseLong(parts[0]), parts.length > 1 ? parts[1] : "",
                    e.keyId, e.projectCode, e.model, e.apiFormat, e.clientFamily,
                    e.keySource, e.cachedTokens.get(),
                    e.createdAt, e.lastWriteAt + ttlMs, e.requestCount.get()));
        }
        list.sort((a, b) -> Long.compare(b.expireAt(), a.expireAt()));
        return list;
    }

    public void removeByCacheKey(String cacheKey) {
        store.remove(cacheKey);
    }

    public long clearAll() {
        return store.clearAll();
    }

    public long activeCount() {
        return store.activeCount();
    }

    public long ttlSeconds() {
        return store.ttlSeconds();
    }

    public String storeMode() {
        return store.storeMode();
    }

    public long stickyHits() {
        return stickyHits.sum();
    }

    public long keySwitches() {
        return keySwitches.sum();
    }

    public long providerSwitches() {
        return providerSwitches.sum();
    }

    public long invalidations() {
        return invalidations.sum();
    }

    private static String key(Long channelId, String affinity) {
        return channelId + "|" + affinity;
    }
}
