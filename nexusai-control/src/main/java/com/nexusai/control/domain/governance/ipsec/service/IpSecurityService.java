package com.nexusai.control.domain.governance.ipsec.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nexusai.common.util.IpMatcher;
import com.nexusai.control.domain.governance.ipsec.vo.IpBlacklistEntryVo;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * IP 安全：
 * <ul>
 *   <li>黑名单：Redis {@code nai:ip:blacklist}（RMapCache·支持按条目 TTL·永久=无 TTL）——命中拒绝一切访问；</li>
 *   <li>白名单：KV {@code ip_security.whitelist}（JSON 数组·支持单 IP 与 IPv4 CIDR）——豁免黑名单（匹配算法见 {@link IpMatcher}）；</li>
 *   <li>数据面热路径不打 Redis：5s 本地全量快照（{@link #snapshot()}）。</li>
 * </ul>
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpSecurityService {

    static final String BLACKLIST_KEY = "nai:ip:blacklist";
    static final String WHITELIST_CFG = "ip_security.whitelist";

    private final RedissonClient redisson;
    private final SystemConfigService systemConfig;

    /**
     * 5s 本地快照：{blacklist: Map<ip,reason>, whitelist: List<String>}。
     * 🔴 用 refreshAfterWrite（非 expireAfterWrite）：条目过期后访问立即返回旧值并在 Caffeine 执行器上异步重载
     * （绝非调用线程），故数据面热路径（{@link IpSecurityWebFilter} 在 Netty 事件循环上调 {@link #blockedReason}）
     * 永远只读本地快照、绝不在事件循环上打 Redis。{@link #warmSnapshot()} 在 bean 初始化线程预热避免首访冷加载，
     * 黑/白名单变更走 {@link LoadingCache#refresh}（异步重载·保留旧值不清空·读路径永不落空）。
     */
    private final LoadingCache<String, Snapshot> snapshot = Caffeine.newBuilder()
            .refreshAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1)
            .build(k -> load());

    record Snapshot(Map<String, String> blacklist, List<String> whitelist) {
    }

    /** bean 初始化线程预热快照（非 Netty 事件循环）——此后请求路径恒命中本地值，重载在 Caffeine 执行器异步进行。 */
    @PostConstruct
    void warmSnapshot() {
        try {
            snapshot.get("all");
        } catch (Exception e) {
            log.warn("IP 安全快照预热失败（首访兜底放行）: {}", e.toString());
        }
    }

    private Snapshot load() {
        Map<String, String> black;
        try {
            black = Map.copyOf(blacklistMap().readAllMap());
        } catch (Exception e) {
            log.warn("IP 黑名单快照加载失败（放行兜底）: {}", e.toString());
            black = Map.of();
        }
        List<String> white;
        try {
            white = whitelist();
        } catch (Exception e) {
            log.warn("IP 白名单快照加载失败（空白名单兜底）: {}", e.toString());
            white = List.of();
        }
        return new Snapshot(black, white);
    }

    private RMapCache<String, String> blacklistMap() {
        return redisson.getMapCache(BLACKLIST_KEY);
    }

    Snapshot snapshot() {
        return snapshot.get("all");
    }

    /** 该 IP 是否被拒绝（黑名单命中且不在白名单）。返回 null=放行，非 null=封禁原因。 */
    public String blockedReason(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        Snapshot s = snapshot();
        if (s.blacklist().isEmpty()) {
            return null;
        }
        String reason = s.blacklist().get(ip);
        if (reason == null) {
            return null;
        }
        return IpMatcher.inWhitelist(ip, s.whitelist()) ? null : reason;
    }

    /** 黑名单列表（含剩余 TTL 秒·-1=永久）。 */
    public List<IpBlacklistEntryVo> listBlacklist() {
        RMapCache<String, String> map = blacklistMap();
        List<IpBlacklistEntryVo> items = new ArrayList<>();
        for (String ip : map.keySet()) {
            String reason = map.get(ip);
            long ttlMs = map.remainTimeToLive(ip);
            items.add(new IpBlacklistEntryVo(ip, reason, ttlMs < 0 ? -1 : ttlMs / 1000));
        }
        items.sort(Comparator.comparing(IpBlacklistEntryVo::ip));
        return items;
    }

    /** 加入黑名单（ttlSeconds null/<=0 = 永久）。 */
    public void addBlacklist(String ip, String reason, Long ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds <= 0) {
            blacklistMap().put(ip.trim(), reason == null ? "" : reason);
        } else {
            blacklistMap().put(ip.trim(), reason == null ? "" : reason, ttlSeconds, TimeUnit.SECONDS);
        }
        snapshot.refresh("all");   // 异步重载·保留旧值不清空（读路径永不落空触发事件循环冷加载）
    }

    public void removeBlacklist(String ip) {
        blacklistMap().remove(ip);
        snapshot.refresh("all");   // 异步重载·保留旧值不清空（读路径永不落空触发事件循环冷加载）
    }

    /** 白名单（单 IP / IPv4 CIDR 字符串列表·存 KV）。 */
    public List<String> whitelist() {
        Object v = systemConfig.getParsed(WHITELIST_CFG);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public void addWhitelist(String entry) {
        String e = entry.trim();
        if (!IpMatcher.isValidEntry(e)) {
            throw new IllegalArgumentException("无效的 IP 或 CIDR: " + e);
        }
        List<String> list = new ArrayList<>(whitelist());
        if (!list.contains(e)) {
            list.add(e);
            systemConfig.put(WHITELIST_CFG, list, "IP 白名单");
        }
        snapshot.refresh("all");   // 异步重载·保留旧值不清空（读路径永不落空触发事件循环冷加载）
    }

    public void removeWhitelist(String entry) {
        List<String> list = new ArrayList<>(whitelist());
        if (list.remove(entry)) {
            systemConfig.put(WHITELIST_CFG, list, "IP 白名单");
        }
        snapshot.refresh("all");   // 异步重载·保留旧值不清空（读路径永不落空触发事件循环冷加载）
    }

}
