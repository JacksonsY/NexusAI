package com.nexusai.control.domain.monitor.cache.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.monitor.cache.service.CacheMonitorService;
import com.nexusai.control.domain.monitor.cache.vo.AliasSnapshotVo;
import com.nexusai.control.domain.monitor.cache.vo.CacheHitAnalysisVo;
import com.nexusai.observe.cache.CacheRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 缓存监控（/admin/cache）：
 * ① 本地 Caffeine 缓存命中率（{@link CacheRegistry}）+ Redis 概况
 * ② 会话亲和统计/列表/清除 ③ 模型别名快照/刷新 ④ Redis 键分类扫描/清除
 * ⑤ 命中分析 + 项目维度 TTL 分析。
 *
 * @author nexusai
 */
@Slf4j
@RestController
@RequestMapping("/admin/cache")
@RequiredArgsConstructor
public class CacheStatsController {

    private final CacheRegistry cacheRegistry;
    private final RedissonClient redisson;
    private final CacheMonitorService monitor;

    /** 本地 Caffeine 缓存命中率/容量/淘汰 + Redis 连通概况。 */
    @GetMapping
    public Mono<R<Map<String, Object>>> stats() {
        return blocking(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("caches", cacheRegistry.stats());
            Map<String, Object> redis = new LinkedHashMap<>();
            try {
                redis.put("connected", true);
                redis.put("keyCount", redisson.getKeys().count());
            } catch (Exception e) {
                redis.put("connected", false);
                redis.put("error", e.getClass().getSimpleName());
            }
            data.put("redis", redis);
            data.put("disk", cacheRegistry.statsOf("disk"));
            return data;
        });
    }

    /** 会话亲和统计 + 条目列表（4 统计卡 + 亲和性列表）。 */
    @GetMapping("/affinity")
    public Mono<R<Map<String, Object>>> affinity(@RequestParam(required = false) String keyword) {
        return blocking(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stats", monitor.affinityStats());
            data.put("items", monitor.affinityList(keyword));
            return data;
        });
    }

    /** 清除单条亲和（cacheKey = channelId|affinityKey）。 */
    @DeleteMapping("/affinity/single")
    public Mono<R<Void>> clearAffinity(@RequestParam String cacheKey) {
        return blocking(() -> {
            monitor.clearAffinity(cacheKey);
            return (Void) null;
        });
    }

    /** 清除全部亲和。 */
    @DeleteMapping("/affinity")
    public Mono<R<Map<String, Object>>> clearAllAffinities() {
        return blocking(() -> Map.of("cleared", monitor.clearAllAffinities()));
    }

    /** 模型别名快照（模型映射缓存·带提供商列）。 */
    @GetMapping("/alias")
    public Mono<R<AliasSnapshotVo>> aliasSnapshot() {
        return blocking(monitor::aliasSnapshot);
    }

    /** 手动刷新模型别名快照。 */
    @PostMapping("/alias/refresh")
    public Mono<R<AliasSnapshotVo>> refreshAlias() {
        return blocking(monitor::refreshAliasSnapshot);
    }

    /** Redis 键分类概览。 */
    @GetMapping("/redis-keys")
    public Mono<R<Map<String, Object>>> redisKeys() {
        return blocking(monitor::redisCategories);
    }

    /** 清除指定分类 Redis 键（真理源/事件流分类拒绝）。 */
    @DeleteMapping("/redis-keys/{category}")
    public Mono<R<Map<String, Object>>> clearRedisCategory(@PathVariable String category) {
        return blocking(() -> Map.of("deletedCount", monitor.clearRedisCategory(category)));
    }

    /** 缓存命中分析（请求命中率/Token 命中率）。 */
    @GetMapping("/hit-analysis")
    public Mono<R<CacheHitAnalysisVo>> hitAnalysis(@RequestParam(defaultValue = "24") int hours) {
        return blocking(() -> monitor.hitAnalysis(clampHours(hours)));
    }

    /** 项目维度 TTL 分析（请求间隔分布/百分位 → 推荐粘性 TTL）。 */
    @GetMapping("/ttl-analysis")
    public Mono<R<Map<String, Object>>> ttlAnalysis(@RequestParam(defaultValue = "24") int hours) {
        return blocking(() -> monitor.ttlAnalysis(clampHours(hours)));
    }

    private static int clampHours(int hours) {
        return Math.min(Math.max(hours, 1), 720);
    }

    private static <T> Mono<R<T>> blocking(Supplier<T> supplier) {
        return Mono.fromCallable(() -> R.ok(supplier.get())).subscribeOn(Schedulers.boundedElastic());
    }
}
