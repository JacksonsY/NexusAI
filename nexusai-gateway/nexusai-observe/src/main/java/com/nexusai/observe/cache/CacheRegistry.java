package com.nexusai.observe.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Caffeine 缓存注册表（缓存监控前提）：各热路径 Caffeine 缓存（开 {@code recordStats()}）启动时注册，
 * 控制台「缓存监控」经 {@code /admin/cache} 读命中率/容量/淘汰。集中一处汇总分散的本地缓存。
 *
 * @author nexusai
 */
@Component
public class CacheRegistry {

    private final Map<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();
    /** 非 Caffeine 缓存后端的命名统计供给器（如磁盘缓存）：数据面自注册，控制面按名读快照 */
    private final Map<String, Supplier<Map<String, Object>>> statsSuppliers = new ConcurrentHashMap<>();

    public void register(String name, Cache<?, ?> cache) {
        caches.put(name, cache);
    }

    /** 注册命名统计供给器（缓存监控页扩展卡·解除控制面对数据面具体类型的依赖）。 */
    public void registerStats(String name, Supplier<Map<String, Object>> supplier) {
        statsSuppliers.put(name, supplier);
    }

    /** 命名统计快照（未注册/供给异常 → 空 map·尽力而为不影响主链）。 */
    public Map<String, Object> statsOf(String name) {
        Supplier<Map<String, Object>> s = statsSuppliers.get(name);
        if (s == null) {
            return Map.of();
        }
        try {
            Map<String, Object> out = s.get();
            return out == null ? Map.of() : out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 各缓存命中率/容量/淘汰快照（只读）。 */
    public List<Map<String, Object>> stats() {
        return caches.entrySet().stream().map(e -> {
            CacheStats s = e.getValue().stats();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("size", e.getValue().estimatedSize());
            m.put("hitCount", s.hitCount());
            m.put("missCount", s.missCount());
            m.put("hitRate", BigDecimal.valueOf(s.hitRate()).setScale(4, RoundingMode.HALF_UP));
            m.put("evictionCount", s.evictionCount());
            m.put("requestCount", s.requestCount());
            return m;
        }).sorted((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")))).toList();
    }
}
