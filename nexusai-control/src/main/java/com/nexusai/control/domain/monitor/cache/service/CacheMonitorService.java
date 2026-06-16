package com.nexusai.control.domain.monitor.cache.service;

import com.nexusai.billing.config.ModelPriceService;
import com.nexusai.billing.config.UsdRate;
import com.nexusai.control.domain.monitor.cache.vo.AffinityItemVo;
import com.nexusai.control.domain.monitor.cache.vo.AffinityStatsVo;
import com.nexusai.control.domain.monitor.cache.vo.AliasSnapshotVo;
import com.nexusai.control.domain.monitor.cache.vo.CacheHitAnalysisVo;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.CacheHitStats;
import com.nexusai.persistence.log.stat.IntervalRow;
import com.nexusai.persistence.log.stat.ModelCacheRow;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiProject;
import com.nexusai.router.affinity.StickyAffinity;
import com.nexusai.router.keypool.KeyPoolSelector;
import com.nexusai.router.model.ModelAliasResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 缓存监控聚合服务（项目制）：
 * ① 会话亲和统计/列表/清除（{@link StickyAffinity} 内存态）
 * ② 模型别名快照（{@link ModelAliasResolver}·模型映射缓存）
 * ③ Redis 键分类扫描/清除（nai:* 前缀分类）
 * ④ 命中分析（request_log cache_tokens 聚合）+ TTL 分析（项目维度请求间隔百分位 → 推荐 TTL）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheMonitorService {

    /** 亲和条目列表返回上限。 */
    private static final int AFFINITY_LIST_LIMIT = 500;
    /** Redis 扫描分块大小（SCAN chunk）。 */
    private static final int REDIS_SCAN_CHUNK = 500;
    /** $/M 费率折回 USD 的小数位移（÷10^6）。 */
    private static final int PER_MILLION_EXP = 6;
    /** USD 金额展示精度（micro-USD·6 位）。 */
    private static final int USD_SCALE = 6;
    /** TTL 分析原始拉取上限（差分前的最大日志行数）。 */
    private static final int TTL_FETCH_LIMIT = 20000;

    /** Redis 键分类（名称/模式/说明/是否允许控制台清除）。事件流与 USD 余额是资金/事件真理源，禁清。 */
    private record RedisCategory(String key, String name, String pattern, String description, boolean clearable) {
    }

    private static final List<RedisCategory> REDIS_CATEGORIES = List.of(
            new RedisCategory("usd", "USD 余额", "nai:usd:*", "强闸门运行时余额（资金真理源·禁止清除）", false),
            new RedisCategory("slot", "并发槽", "nai:slot:*", "Key 级并发槽 ZSET（清除后由 TTL 自愈）", true),
            new RedisCategory("rpm", "RPM 限流", "nai:rl:*", "Token 级每分钟请求数滑窗", true),
            new RedisCategory("tpm", "TPM 限流", "nai:tpm:*", "Token 级每分钟 token 数滑窗", true),
            // 🔴 熔断态同存进程内 RoutingHealthStat 与 Redis 快照：裸删 nai:ban:* 只清集群快照、不解运行实例的进程内熔断，
            // 还抹掉新实例 warm-start 继承的熔断态（新实例会把流量打向实际仍坏的 Key/渠道）。必须经运行时一键复活
            // （/admin/runtime/*/revive·清进程内戳 + RTopic 广播 + 重灌快照），故此处标记不可裸清。
            new RedisCategory("ban", "Key 熔断", "nai:ban:*",
                    "Key/渠道临时不可调度标记（请用运行时一键复活 /admin/runtime/*/revive 解除·裸删只清集群快照不解进程内熔断）", false),
            new RedisCategory("rcache", "响应缓存", "nai:rcache:*", "相同请求体的精确响应缓存", true),
            new RedisCategory("idem", "请求幂等", "nai:idem:*", "Idempotency-Key 防重键", true),
            new RedisCategory("dedup", "请求去重", "nai:dedup:*", "请求体去重键", true),
            new RedisCategory("quota-op", "契约③幂等", "nai:quota:op:*", "QuotaOperation 幂等保护键", true),
            new RedisCategory("usage-stream", "UsageEvent 流", "nexusai:usage:stream", "契约② 计量事件流（禁止清除）", false),
            new RedisCategory("requestlog-stream", "请求日志流", "nexusai:requestlog:stream", "请求日志异步落库流（禁止清除）", false));

    private final StickyAffinity stickyAffinity;
    private final ModelAliasResolver aliasResolver;
    private final RedissonClient redisson;
    private final RequestLogStore requestLogStore;
    private final NaiChannelMapper channelMapper;
    private final NaiProjectMapper projectMapper;
    private final NaiAbilityMapper abilityMapper;
    private final ModelPriceService modelPriceService;

    // ===== ① 会话亲和 =====

    /** 亲和系统统计（4 统计卡）。 */
    public AffinityStatsVo affinityStats() {
        // anchorMinScoreRatio：锚定置顶负载安全闸——锚定 Key 打分低于主选分此比例则让位负载均衡
        return new AffinityStatsVo(
                stickyAffinity.activeCount(),
                stickyAffinity.ttlSeconds(),
                stickyAffinity.storeMode(),
                stickyAffinity.stickyHits(),
                stickyAffinity.keySwitches(),
                stickyAffinity.providerSwitches(),
                stickyAffinity.invalidations(),
                KeyPoolSelector.ANCHOR_MIN_SCORE_RATIO);
    }

    /** 亲和条目列表（渠道/项目名解析·keyword 过滤·上限 {@value #AFFINITY_LIST_LIMIT}）。 */
    public List<AffinityItemVo> affinityList(String keyword) {
        List<StickyAffinity.EntryView> entries = stickyAffinity.entries();
        Map<Long, String> channelNames = channelMapper.selectList(null).stream()
                .collect(Collectors.toMap(NaiChannel::getId, NaiChannel::getName, (a, b) -> a));
        Map<String, String> projectNames = projectMapper.selectList(null).stream()
                .filter(p -> p.getProjectCode() != null)
                .collect(Collectors.toMap(NaiProject::getProjectCode, NaiProject::getName, (a, b) -> a));
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        List<AffinityItemVo> out = new ArrayList<>();
        for (StickyAffinity.EntryView e : entries) {
            String projectName = e.projectCode() == null ? null : projectNames.get(e.projectCode());
            if (kw != null && !kw.isEmpty()) {
                String hay = (e.affinityKey() + " " + nullSafe(e.projectCode()) + " " + nullSafe(projectName)
                        + " " + nullSafe(e.model()) + " " + e.keyId()).toLowerCase();
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            out.add(new AffinityItemVo(
                    e.cacheKey(), e.affinityKey(), e.channelId(), channelNames.get(e.channelId()),
                    e.keyId(), e.projectCode(), projectName, e.model(), e.apiFormat(),
                    e.clientFamily(), e.keySource(), e.cachedTokens(), e.createdAt(),
                    e.expireAt(), e.requestCount()));
            if (out.size() >= AFFINITY_LIST_LIMIT) {
                break;
            }
        }
        return out;
    }

    public void clearAffinity(String cacheKey) {
        stickyAffinity.removeByCacheKey(cacheKey);
    }

    public long clearAllAffinities() {
        return stickyAffinity.clearAll();
    }

    // ===== ② 模型别名快照 =====

    public AliasSnapshotVo aliasSnapshot() {
        return decorateAlias(aliasResolver.snapshotView());
    }

    public AliasSnapshotVo refreshAliasSnapshot() {
        aliasResolver.forceRefresh();
        return decorateAlias(aliasResolver.snapshotView());
    }

    /** 给别名规则补「提供商」列（modelKey → ability 挂载渠道名）。 */
    private AliasSnapshotVo decorateAlias(ModelAliasResolver.SnapshotView snap) {
        Map<Long, String> channelNames = channelMapper.selectList(null).stream()
                .collect(Collectors.toMap(NaiChannel::getId, NaiChannel::getName, (a, b) -> a));
        Map<String, List<String>> providersByModel = new LinkedHashMap<>();
        for (NaiAbility ab : abilityMapper.selectList(null)) {
            String name = channelNames.get(ab.getChannelId());
            if (name == null) {
                continue;
            }
            List<String> list = providersByModel.computeIfAbsent(ab.getModel(), k -> new ArrayList<>());
            if (!list.contains(name)) {
                list.add(name);
            }
        }
        List<AliasSnapshotVo.Rule> rules = new ArrayList<>(snap.rules().size());
        for (ModelAliasResolver.AliasRuleView r : snap.rules()) {
            rules.add(new AliasSnapshotVo.Rule(r.pattern(), r.modelKey(), r.modelAlias(), r.status(),
                    providersByModel.getOrDefault(r.modelKey(), List.of())));
        }
        return new AliasSnapshotVo(snap.modelCount(), snap.ruleCount(), snap.loadedAt(), snap.refreshMs(), rules);
    }

    // ===== ③ Redis 键分类 =====

    public Map<String, Object> redisCategories() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> cats = new ArrayList<>();
        long totalKeys = 0;
        try {
            RKeys keys = redisson.getKeys();
            for (RedisCategory cat : REDIS_CATEGORIES) {
                long count = countByPattern(keys, cat.pattern());
                totalKeys += count;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", cat.key());
                m.put("name", cat.name());
                m.put("pattern", cat.pattern());
                m.put("description", cat.description());
                m.put("clearable", cat.clearable());
                m.put("count", count);
                cats.add(m);
            }
            data.put("available", true);
        } catch (Exception e) {
            log.warn("Redis 缓存分类扫描失败", e);
            data.put("available", false);
            data.put("message", "Redis 不可用: " + e.getClass().getSimpleName());
        }
        data.put("categories", cats);
        data.put("totalKeys", totalKeys);
        return data;
    }

    public long clearRedisCategory(String categoryKey) {
        RedisCategory cat = REDIS_CATEGORIES.stream()
                .filter(c -> c.key().equals(categoryKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知缓存分类: " + categoryKey));
        if (!cat.clearable()) {
            throw new IllegalArgumentException("该分类为真理源/事件流，禁止清除: " + cat.name());
        }
        return redisson.getKeys().deleteByPattern(cat.pattern());
    }

    private static long countByPattern(RKeys keys, String pattern) {
        long n = 0;
        for (String ignored : keys.getKeys(KeysScanOptions.defaults().pattern(pattern).chunkSize(REDIS_SCAN_CHUNK))) {
            n++;
        }
        return n;
    }

    // ===== ④ 命中分析 + TTL 分析 =====

    /**
     * 缓存命中分析（USD 口径）：请求/Token 命中率 + 按模型 USD 价格折算的
     * 缓存读取费用（cachedInputRate×cacheTokens）与预估节省（(inputRate-cachedInputRate)×cacheTokens）。
     * 「缓存创建费用」依赖 cache_creation tokens——日志只存读命中（cache_tokens），创建口径可能缺失。
     */
    public CacheHitAnalysisVo hitAnalysis(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        CacheHitStats s = requestLogStore.cacheHitStats(since);
        BigDecimal readCost = BigDecimal.ZERO;
        BigDecimal creationCost = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        for (ModelCacheRow row : requestLogStore.cacheHitByModel(since)) {
            UsdRate p = modelPriceService.priceFor(row.model());
            if (p == null) {
                continue;
            }
            // token 费率为 $/M（与 UsageMeter 同口径）：费率×tokens 后必须 ÷10^6 折回 USD，否则金额虚高 100 万倍
            BigDecimal tokens = BigDecimal.valueOf(row.cacheTokens());
            readCost = readCost.add(p.cachedInputRate().multiply(tokens).movePointLeft(PER_MILLION_EXP));
            creationCost = creationCost.add(p.writeCachedRate()
                    .multiply(BigDecimal.valueOf(row.cacheCreationTokens())).movePointLeft(PER_MILLION_EXP));
            BigDecimal diff = p.inputRate().subtract(p.cachedInputRate());
            if (diff.signum() > 0) {
                savings = savings.add(diff.multiply(tokens).movePointLeft(PER_MILLION_EXP));
            }
        }
        return new CacheHitAnalysisVo(
                hours, s.totalRequests(), s.requestsWithHit(), rate(s.requestsWithHit(), s.totalRequests()),
                s.promptTokens(), s.cacheTokens(), rate(s.cacheTokens(), s.promptTokens()), s.cacheCreationTokens(),
                creationCost.setScale(USD_SCALE, RoundingMode.HALF_UP),
                readCost.setScale(USD_SCALE, RoundingMode.HALF_UP),
                savings.setScale(USD_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * TTL 分析（项目维度）：近窗各项目相邻请求间隔分布 + P50/P75/P90 →
     * 推荐粘性 TTL（p90≤5→5min / ≤15→15 / ≤30→30 / 其余 60）。
     */
    public Map<String, Object> ttlAnalysis(int hours) {
        List<IntervalRow> rows = requestLogStore.recentForIntervals(LocalDateTime.now().minusHours(hours), TTL_FETCH_LIMIT);
        Map<String, String> projectNames = projectMapper.selectList(null).stream()
                .filter(p -> p.getProjectCode() != null)
                .collect(Collectors.toMap(NaiProject::getProjectCode, NaiProject::getName, (a, b) -> a));

        // rows 已按 (project_code, create_dt) 升序：逐项目差分出间隔（分钟）
        Map<String, List<Double>> intervals = new LinkedHashMap<>();
        Map<String, Integer> requestCounts = new LinkedHashMap<>();
        IntervalRow prev = null;
        for (IntervalRow row : rows) {
            if (row.projectCode() == null) {
                prev = row;
                continue;
            }
            requestCounts.merge(row.projectCode(), 1, Integer::sum);
            if (prev != null && row.projectCode().equals(prev.projectCode())) {
                double minutes = Duration.between(prev.createDt(), row.createDt()).toMillis() / 60000.0;
                intervals.computeIfAbsent(row.projectCode(), k -> new ArrayList<>()).add(minutes);
            }
            prev = row;
        }

        Map<String, Integer> ttlDistribution = new LinkedHashMap<>(Map.of("5min", 0, "15min", 0, "30min", 0, "60min", 0));
        List<Map<String, Object>> projects = new ArrayList<>();
        for (Map.Entry<String, List<Double>> en : intervals.entrySet()) {
            List<Double> iv = en.getValue();
            if (iv.size() < 2) {
                continue;
            }
            iv.sort(Comparator.naturalOrder());
            double p50 = percentile(iv, 0.50);
            double p75 = percentile(iv, 0.75);
            double p90 = percentile(iv, 0.90);
            int ttl = p90 <= 5 ? 5 : p90 <= 15 ? 15 : p90 <= 30 ? 30 : 60;
            ttlDistribution.merge(ttl + "min", 1, Integer::sum);

            Map<String, Long> dist = distribution(iv);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("projectCode", en.getKey());
            p.put("projectName", projectNames.get(en.getKey()));
            p.put("requestCount", requestCounts.getOrDefault(en.getKey(), 0));
            p.put("intervalDistribution", dist);
            p.put("percentiles", Map.of("p50", round2(p50), "p75", round2(p75), "p90", round2(p90)));
            p.put("avgIntervalMinutes", round2(iv.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            p.put("minIntervalMinutes", round2(iv.get(0)));
            p.put("maxIntervalMinutes", round2(iv.get(iv.size() - 1)));
            p.put("recommendedTtlMinutes", ttl);
            projects.add(p);
        }
        projects.sort(Comparator.comparingInt(p -> -((Integer) p.get("requestCount"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysisPeriodHours", hours);
        out.put("totalProjectsAnalyzed", projects.size());
        out.put("ttlDistribution", ttlDistribution);
        out.put("projects", projects);
        return out;
    }

    private static Map<String, Long> distribution(List<Double> intervals) {
        Map<String, Long> dist = new LinkedHashMap<>();
        dist.put("within5min", intervals.stream().filter(v -> v <= 5).count());
        dist.put("within15min", intervals.stream().filter(v -> v > 5 && v <= 15).count());
        dist.put("within30min", intervals.stream().filter(v -> v > 15 && v <= 30).count());
        dist.put("within60min", intervals.stream().filter(v -> v > 30 && v <= 60).count());
        dist.put("over60min", intervals.stream().filter(v -> v > 60).count());
        return dist;
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double rate(long part, long total) {
        return total <= 0 ? 0 : Math.round(part * 10000.0 / total) / 100.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
