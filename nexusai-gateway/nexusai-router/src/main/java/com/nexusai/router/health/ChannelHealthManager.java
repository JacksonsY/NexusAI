package com.nexusai.router.health;

import com.nexusai.common.event.ConfigChangedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道级健康/熔断状态机（与 Key 级 {@link KeyHealthManager} 对称的渠道维度）。
 * 上游 429→冷却 / 403→封禁 / 超时·5xx→临时不可调度；{@code schedulable()} 到点惰性复活。
 * 复用 {@link RoutingHealthStat} 作通用健康统计容器。
 * <p><b>多实例一致（H6·始终启用）</b>：熔断经 {@link RuntimeStateStore} RTopic 即时广播 + 远端镜像落本地
 * {@link RoutingHealthStat#remoteBannedUntil}，启动 warm-start 继承集群在用渠道熔断态——热路径只读本地不查 Redis。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelHealthManager {

    private static final long RATE_LIMIT_COOLDOWN_MS = 30_000L;
    private static final long AUTH_BAN_MS = 300_000L;
    private static final long TEMP_BAN_MS = 10_000L;

    static final String NS = "chan";

    /** volatile：配置变更时整体换引用（原子重建·读者只见旧表满或新表满，杜绝 clear 与重灌之间的空窗）。 */
    private volatile ConcurrentHashMap<Long, RoutingHealthStat> stats = new ConcurrentHashMap<>();

    /** 多实例熔断态下沉 + 即时广播（始终启用）。 */
    private final RuntimeStateStore store;

    /** 注册远端熔断订阅 + 启动 warm-start 继承集群在用渠道熔断态（H6）。 */
    @PostConstruct
    public void init() {
        store.register((ns, id, untilMs) -> {
            if (NS.equals(ns)) {
                stat(id).remoteBannedUntil(untilMs);
            }
        });
        long now = System.currentTimeMillis();
        store.snapshotBans(NS, now).forEach((id, until) -> stat(id).remoteBannedUntil(until));
    }

    public boolean schedulable(Long channelId, long now) {
        RoutingHealthStat s = stats.get(channelId);
        return s == null || s.schedulable(now);   // 含远端熔断镜像·热路径不查 Redis
    }

    /** 只读探查（不创建条目）：无运行时态返回 null（视为满血/可调度）。 */
    public RoutingHealthStat peek(Long channelId) {
        return stats.get(channelId);
    }

    public void recordSuccess(Long channelId, long latencyMs) {
        stat(channelId).recordSuccess(latencyMs);
    }

    public void recordRateLimited(Long channelId, Long retryAfterMs) {
        RoutingHealthStat s = stat(channelId);
        long cd = (retryAfterMs != null && retryAfterMs > 0) ? retryAfterMs : RATE_LIMIT_COOLDOWN_MS;
        long until = System.currentTimeMillis() + cd;
        s.rateLimitedUntil(until);
        store.recordBan(NS, channelId, until);
        s.recordError(15);
    }

    public void recordAuthBan(Long channelId) {
        RoutingHealthStat s = stat(channelId);
        long until = System.currentTimeMillis() + AUTH_BAN_MS;
        s.overloadedUntil(until);
        store.recordBan(NS, channelId, until);
        s.recordError(40);
        log.warn("渠道 {} 鉴权失败封禁 {}ms", channelId, AUTH_BAN_MS);
    }

    public void recordTransientError(Long channelId) {
        RoutingHealthStat s = stat(channelId);
        long until = System.currentTimeMillis() + TEMP_BAN_MS;
        s.unschedulableUntil(until);
        store.recordBan(NS, channelId, until);
        s.recordError(20);
    }

    private RoutingHealthStat stat(Long channelId) {
        return stats.computeIfAbsent(channelId, k -> new RoutingHealthStat());
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.hits(ConfigChangedEvent.Scope.CONFIG)) {
            // 🔴 与 KeyHealthManager 对称·原子替换而非 clear+重灌：clear 与阻塞读快照重灌之间的空窗会把死/限流渠道
            // 瞬间复活、selectChannel 立即重选死渠道。先在快照外建好新表再整体换引用。
            long now = System.currentTimeMillis();
            ConcurrentHashMap<Long, RoutingHealthStat> fresh = new ConcurrentHashMap<>();
            store.snapshotBans(NS, now).forEach((id, until) -> {
                RoutingHealthStat s = new RoutingHealthStat();
                s.remoteBannedUntil(until);
                fresh.put(id, s);
            });
            stats = fresh;
            log.info("ChannelHealthManager 健康态已原子重建并重灌集群熔断态（CONFIG version={}）", event.version());
        }
    }
}
