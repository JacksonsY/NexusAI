package com.nexusai.router.affinity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 亲和条目两级存储（从 {@link StickyAffinity} 拆出的纯存储层）：
 * {@code nexusai.pool.sticky.store=memory|redis}。memory（默认）= 单实例 Caffeine；
 * redis = Redis 真理源（多实例共享）+ Caffeine 短 TTL L1 挡读，Redis 故障时自动降级走本地（不阻断热路径）。
 * <p>只管存取/扫描/编解码，亲和语义（逃逸/计数/开关）在 {@link StickyAffinity}。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class AffinityEntryStore {

    private static final String REDIS_PREFIX = "nai:sticky:";
    /** redis 模式下 L1 挡读 TTL（秒）——容忍多实例间该窗口的元数据滞后 */
    private static final long L1_TTL_SECONDS = 5;
    /** redis 模式扫描上限（控制台快照） */
    private static final int SCAN_LIMIT = 1000;

    private final Cache<String, AffinityEntry> cache;
    private final long ttlSeconds;
    private final boolean redisMode;
    private final RedissonClient redisson;

    public AffinityEntryStore(@Value("${nexusai.pool.sticky.ttl-seconds:300}") long ttlSeconds,
                              @Value("${nexusai.pool.sticky.max-size:50000}") long maxSize,
                              @Value("${nexusai.pool.sticky.store:memory}") String store,
                              RedissonClient redisson) {
        this.ttlSeconds = ttlSeconds;
        this.redisMode = "redis".equalsIgnoreCase(store);
        this.redisson = redisson;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(redisMode ? Math.min(L1_TTL_SECONDS, ttlSeconds) : ttlSeconds))
                .maximumSize(maxSize)
                .build();
        log.info("AffinityEntryStore store={} ttl={}s", redisMode ? "redis" : "memory", ttlSeconds);
    }

    /** 读：L1 → redis 模式回源 Redis 并回填 L1。 */
    AffinityEntry lookup(String k) {
        AffinityEntry e = cache.getIfPresent(k);
        if (e != null || !redisMode) {
            return e;
        }
        try {
            AffinityEntry remote = fromJson(redisson.<String>getBucket(REDIS_PREFIX + k, StringCodec.INSTANCE).get());
            if (remote != null) {
                cache.put(k, remote);
            }
            return remote;
        } catch (Exception ex) {
            log.warn("亲和条目 Redis 读失败（降级本地）: {}", ex.toString());
            return null;
        }
    }

    /** 写：L1 必写（刷新 TTL）；redis 模式同步写 Redis（带 TTL·失败不阻断）。 */
    void store(String k, AffinityEntry e) {
        cache.put(k, e);
        if (!redisMode) {
            return;
        }
        try {
            RBucket<String> bucket = redisson.getBucket(REDIS_PREFIX + k, StringCodec.INSTANCE);
            bucket.set(toJson(e), Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            log.warn("亲和条目 Redis 写失败（降级本地）: {}", ex.toString());
        }
    }

    void remove(String cacheKey) {
        cache.invalidate(cacheKey);
        if (redisMode) {
            try {
                redisson.getBucket(REDIS_PREFIX + cacheKey, StringCodec.INSTANCE).delete();
            } catch (Exception e) {
                log.warn("亲和条目 Redis 删除失败: {}", e.toString());
            }
        }
    }

    long clearAll() {
        long n = redisMode ? 0 : cache.estimatedSize();
        cache.invalidateAll();
        if (redisMode) {
            try {
                n = redisson.getKeys().deleteByPattern(REDIS_PREFIX + "*");
            } catch (Exception e) {
                log.warn("亲和条目 Redis 清空失败: {}", e.toString());
            }
        }
        return n;
    }

    long activeCount() {
        if (redisMode) {
            try {
                long n = 0;
                for (String ignored : redisson.getKeys().getKeys(
                        KeysScanOptions.defaults().pattern(REDIS_PREFIX + "*").chunkSize(500))) {
                    n++;
                }
                return n;
            } catch (Exception e) {
                // 降级本地
            }
        }
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /** 活跃条目快照（memory=Caffeine 全量；redis=SCAN nai:sticky:*·上限 {@value #SCAN_LIMIT} 键）。 */
    Map<String, AffinityEntry> scanAll() {
        Map<String, AffinityEntry> all = new LinkedHashMap<>();
        if (redisMode) {
            try {
                int n = 0;
                for (String rk : redisson.getKeys().getKeys(
                        KeysScanOptions.defaults().pattern(REDIS_PREFIX + "*").chunkSize(500))) {
                    String json = redisson.<String>getBucket(rk, StringCodec.INSTANCE).get();
                    AffinityEntry e = fromJson(json);
                    if (e != null) {
                        all.put(rk.substring(REDIS_PREFIX.length()), e);
                    }
                    if (++n >= SCAN_LIMIT) {
                        break;
                    }
                }
            } catch (Exception ex) {
                log.warn("亲和条目 Redis 扫描失败，回退本地 L1: {}", ex.toString());
                all.putAll(cache.asMap());
            }
        } else {
            cache.cleanUp();
            all.putAll(cache.asMap());
        }
        return all;
    }

    long ttlSeconds() {
        return ttlSeconds;
    }

    String storeMode() {
        return redisMode ? "redis" : "memory";
    }

    private static String toJson(AffinityEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("keyId", e.keyId);
        m.put("createdAt", e.createdAt);
        m.put("lastWriteAt", e.lastWriteAt);
        m.put("requestCount", e.requestCount.get());
        m.put("projectCode", e.projectCode);
        m.put("model", e.model);
        m.put("apiFormat", e.apiFormat);
        m.put("clientFamily", e.clientFamily);
        m.put("keySource", e.keySource);
        m.put("cachedTokens", e.cachedTokens.get());
        return JsonUtils.toJson(m);
    }

    private static AffinityEntry fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> m = JsonUtils.parse(json, new TypeReference<Map<String, Object>>() {
            });
            AffinityEntry e = new AffinityEntry(((Number) m.getOrDefault("keyId", 0L)).longValue(),
                    ((Number) m.getOrDefault("createdAt", System.currentTimeMillis())).longValue());
            e.lastWriteAt = ((Number) m.getOrDefault("lastWriteAt", e.createdAt)).longValue();
            e.requestCount.set(((Number) m.getOrDefault("requestCount", 1L)).longValue());
            e.projectCode = (String) m.get("projectCode");
            e.model = (String) m.get("model");
            e.apiFormat = (String) m.get("apiFormat");
            e.clientFamily = (String) m.get("clientFamily");
            e.keySource = (String) m.get("keySource");
            e.cachedTokens.set(((Number) m.getOrDefault("cachedTokens", 0L)).longValue());
            return e;
        } catch (Exception ex) {
            return null;
        }
    }
}
