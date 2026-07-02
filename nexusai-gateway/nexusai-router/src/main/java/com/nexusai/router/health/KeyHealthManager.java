package com.nexusai.router.health;

import com.nexusai.common.event.ConfigChangedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Key 级健康/熔断状态机 + 自适应 RPM 学习（错误分类驱动）。
 * <p>运行时态在内存 {@link RoutingHealthStat}（不每请求写 DB）。错误分类驱动熔断：429→冷却(优先 Retry-After)、
 * 401/403→较长封禁（dead 语义）、超时/5xx→临时不可调度 + 指数退避半开。{@code schedulable()} 到点惰性复活。
 * <p><b>多实例一致（H6·始终启用）</b>：熔断经 {@link RuntimeStateStore} RTopic 即时广播——本地熔断时下沉广播，
 * 远端熔断经 {@link RuntimeStateStore.BanListener} 落到本地 {@link RoutingHealthStat#remoteBannedUntil}，
 * {@code schedulable()} 只读本地镜像不查 Redis。启动 warm-start 继承集群在用熔断态。
 * 自适应 RPM 本地置信度门控学习（硬 RPM 上限另由 RateLimiterService 跨实例兜住）。配置变更清空内存态。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyHealthManager {

    /** 429 默认冷却 */
    private static final long RATE_LIMIT_COOLDOWN_MS = 30_000L;
    /** 401/403 封禁（dead 语义） */
    private static final long AUTH_BAN_MS = 300_000L;
    /** 超时/5xx 临时不可调度（基准·按连续失败指数退避，封顶 2^5=32 倍 ≈ 320s） */
    private static final long TEMP_BAN_MS = 10_000L;
    private static final int TRANSIENT_BACKOFF_CAP_EXP = 5;

    static final String NS = "key";

    /** volatile：配置变更时整体换引用（原子重建·读者只见旧表满或新表满，杜绝 clear 与重灌之间的空窗）。 */
    private volatile ConcurrentHashMap<Long, RoutingHealthStat> stats = new ConcurrentHashMap<>();

    /** 多实例熔断态下沉 + 即时广播（始终启用）。 */
    private final RuntimeStateStore store;

    /** 注册远端熔断订阅 + 启动 warm-start 继承集群在用熔断态（H6）。 */
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

    public RoutingHealthStat stat(Long keyId) {
        return stats.computeIfAbsent(keyId, k -> new RoutingHealthStat());
    }

    /** 只读探查（不创建条目）：无运行时态返回 null（视为满血/可调度）。 */
    public RoutingHealthStat peek(Long keyId) {
        return stats.get(keyId);
    }

    public boolean schedulable(Long keyId, long now) {
        return stat(keyId).schedulable(now);   // 含远端熔断镜像（remoteBanUntil）·热路径不查 Redis
    }

    /**
     * 数据面选号闸（KeyPoolSelector 选号过滤用）：熔断中→false；半开态只放一个探测请求（CAS·防探测风暴），其余→false。
     * 与 {@link #schedulable}（纯读·控制面判活用）分离——探测名额是副作用，只能由真正发起请求的选号消费。
     */
    public boolean tryAcquireForDispatch(Long keyId, long now) {
        return stat(keyId).tryAcquireForDispatch(now);
    }

    public void recordSuccess(Long keyId, long latencyMs) {
        stat(keyId).recordSuccess(latencyMs);
    }

    /** 上游 429：冷却 + 自适应学习 RPM（置信度门控·连续确认 + safety margin 才落地·防单次偶发误学）。 */
    public void recordRateLimited(Long keyId, Long retryAfterMs, int observedRpm) {
        RoutingHealthStat s = stat(keyId);
        long cooldown = (retryAfterMs != null && retryAfterMs > 0) ? retryAfterMs : RATE_LIMIT_COOLDOWN_MS;
        long until = System.currentTimeMillis() + cooldown;
        s.rateLimitedUntil(until);
        store.recordBan(NS, keyId, until);   // 即时广播熔断·跨实例避让
        s.recordError(15);
        s.observeRpmLimit(observedRpm);      // 置信度门控学习（本地·打分降权）
        log.debug("Key {} 触发限流，冷却 {}ms，学习RPM={}", keyId, cooldown, s.rpmLearned());
    }

    /** 401/403：较长封禁（dead 集合语义）。 */
    public void recordAuthBan(Long keyId) {
        RoutingHealthStat s = stat(keyId);
        long until = System.currentTimeMillis() + AUTH_BAN_MS;
        s.overloadedUntil(until);
        store.recordBan(NS, keyId, until);
        s.recordError(40);
        log.warn("Key {} 鉴权失败封禁 {}ms", keyId, AUTH_BAN_MS);
    }

    /** 超时/5xx：临时不可调度 + 扣健康分。<b>指数退避半开</b>：连续失败时退避翻倍，成功一次即复位。 */
    public void recordTransientError(Long keyId) {
        RoutingHealthStat s = stat(keyId);
        long now = System.currentTimeMillis();
        // 按「轮次」退避 + 武装半开单飞：applyTransientBackoff 原子完成「判当前熔断窗口→决定是否推进指数→置临时熔断戳→武装探测」，
        // 同一故障窗口的并发在途失败不再各自把退避顶到 cap（退避语义回归「连续轮次」）。
        long until = s.applyTransientBackoff(now, TEMP_BAN_MS, TRANSIENT_BACKOFF_CAP_EXP);
        store.recordBan(NS, keyId, until);
        s.recordError(20);
        log.debug("Key {} 临时熔断至 {}（指数退避·按轮次·半开单飞）", keyId, until);
    }

    /**
     * 配置变更（渠道/Key 改动）→ 清空内存健康态（自适应 RPM/错误分等学习态）。
     * <p>🔴 clear 会连同熔断态（auth-ban/限流/临时熔断 + 远端熔断镜像）一并抹掉，若不重灌，一次无关的渠道编辑就会
     * 瞬间「复活」全部死/限流 Key、把流量打向死号。故 clear 后立即从集群熔断快照（{@link RuntimeStateStore#snapshotBans}）
     * 重灌仍生效的熔断态——与 {@link #init} 的 warm-start 同口径。
     */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.hits(ConfigChangedEvent.Scope.CONFIG)) {
            // 🔴 原子替换而非 clear+重灌：clear 与「阻塞读集群熔断快照后逐个回填」之间存在窗口，并发选号线程会看到
            // 空表、把实际被封禁/限流的 Key 当作可调度。先在快照外建好新表再整体换引用。
            long now = System.currentTimeMillis();
            ConcurrentHashMap<Long, RoutingHealthStat> fresh = new ConcurrentHashMap<>();
            store.snapshotBans(NS, now).forEach((id, until) -> {
                RoutingHealthStat s = new RoutingHealthStat();
                s.remoteBannedUntil(until);
                fresh.put(id, s);
            });
            stats = fresh;
            log.info("KeyHealthManager 健康态已原子重建并重灌集群熔断态（CONFIG version={}）", event.version());
        }
    }
}
