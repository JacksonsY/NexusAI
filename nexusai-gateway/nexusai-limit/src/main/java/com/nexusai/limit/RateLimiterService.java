package com.nexusai.limit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RScript;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;

/**
 * 多维限流（Redisson RRateLimiter，apiKey/IP/user 维度 RPM）+ TPM 软闸门（分钟窗口累计 token，
 * 请求前判、响应后累加；评审 MEDIUM·补 token 维度限流）。并发槽（Key 级 ZSET+Lua 原子占释）见 {@link ConcurrencySlot}。
 * <p><b>RPM 时钟基准</b>：RRateLimiter 的滑窗时间取<b>各 JVM 本地时钟</b>（客户端 {@code System.currentTimeMillis()}
 * 作 zset score，<b>非</b> Redis 服务端时钟）——多实例须靠 <b>NTP 纪律</b>约束时钟漂移，漂移过大时分钟窗口对不齐
 * 会致 RPM 轻微超卖/少卖。硬并发闸 {@link ConcurrencySlot} 才用 {@code redis.call('TIME')} 服务端时钟根除漂移。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final String KEY_PREFIX = "nai:rl:";
    private static final String TPM_PREFIX = "nai:tpm:";
    /**
     * TPM 分钟桶原子累加：{@code incrby} + 仅首次写（{@code v==tokens}）设 {@code pexpire}。
     * Lua 在 Redis 单线程内原子执行——杜绝「并发各自 addAndGet 后都不满足 v==tokens 致 TTL 漏设、分钟桶 key 永久泄漏」竞态
     * （此前 Java 双步 addAndGet+if 非原子的隐患·令牌桶 EXPIRE 漏设会致 key 永久泄漏）。
     */
    private static final String TPM_INCR_LUA =
            "local v = redis.call('incrby', KEYS[1], ARGV[1]); "
            + "if v == tonumber(ARGV[1]) then redis.call('pexpire', KEYS[1], ARGV[2]); end; "
            + "return v";

    /** appliedRates 上限与空闲淘汰：活跃 key 每请求 access 不过期（限流持续生效），空闲 key 过期淘汰，防按
     *  token/workspace 基数（B2C 散户级·随建删轮换）无界增长导致的堆内存泄漏（评审 HIGH·与 routing/auth 有界缓存纪律一致）。 */
    private static final int APPLIED_RATES_MAX = 100_000;
    private static final Duration APPLIED_RATES_IDLE_TTL = Duration.ofMinutes(10);
    /**
     * RRateLimiter 键的空闲存活（keepAlive）：每次 acquire 滚动 pexpire 刷新三键（config/value/permits），
     * 空闲超此时长 Redis 自动回收，杜绝散户级 key 轮换下 {@code nai:rl:*} 无界泄漏（legacy 4 参重载 keepAlive=0
     * → 键永不过期）。<b>必须 &gt; {@link #APPLIED_RATES_IDLE_TTL}</b>：appliedRates 是「本节点已初始化该限流器」的
     * 本地记忆，若 Redis 键先于本地记忆过期，会出现「本地记为已应用→跳过 setRate→acquire 命中未初始化限流器
     * assert 失败」；令键 TTL 严格长于本地记忆即可规避（本地先失效则下次请求 setRate 重新初始化）。
     */
    private static final Duration RATE_LIMITER_KEEP_ALIVE = APPLIED_RATES_IDLE_TTL.multipliedBy(2);

    private final RedissonClient redisson;

    /**
     * 本节点已应用到各限流器的速率快照（dim:id → ratePerMin）：仅用于检测配置变更，避免每请求多一次 setRate。
     * 有界 Caffeine（maximumSize + expireAfterAccess）：活跃 key 持续被访问故不过期、限流不被重置；空闲 key 淘汰
     * 后即便偶发重新 setRate 也只重置一个本已注满的桶（无副作用）。
     */
    private final Cache<String, Long> appliedRates = Caffeine.newBuilder()
            .maximumSize(APPLIED_RATES_MAX)
            .expireAfterAccess(APPLIED_RATES_IDLE_TTL)
            .build();

    /**
     * 尝试获取一个令牌。
     *
     * @param dimension 维度（apiKey / ip / user）
     * @param id        维度标识
     * @param ratePerMin 每分钟速率（&lt;=0 表示不限）
     * @return true 放行 / false 触发限流
     */
    public boolean tryAcquire(String dimension, String id, long ratePerMin) {
        if (ratePerMin <= 0) {
            return true;
        }
        String key = KEY_PREFIX + dimension + ":" + id;
        RRateLimiter limiter = redisson.getRateLimiter(key);
        // 🔴 速率随配置变更生效：旧实现 trySetRate「已存在不覆盖」会让改后的 rpm_limit 在所有节点被永久忽略
        // （除非手工删 Redis key）。改为按本节点已应用速率对比，仅在速率变化（或本节点首次见到该 key）时 setRate
        // 强制刷新——避免每请求都 setRate（setRate 会重置令牌桶）。setRate 带 keepAlive Duration：键空闲自过期防无界泄漏。
        Long applied = appliedRates.getIfPresent(key);
        if (applied == null || applied != ratePerMin) {
            limiter.setRate(RateType.OVERALL, ratePerMin, Duration.ofMinutes(1), RATE_LIMITER_KEEP_ALIVE);
            appliedRates.put(key, ratePerMin);
        }
        return limiter.tryAcquire(1);
    }

    /** TPM 软闸门：指定维度（{@code token:/project:/workspace:} 前缀）当前分钟已累计 token 是否仍在上限内（&lt;=0 不限）。请求前判。 */
    public boolean tpmAllowed(String scope, int tpmLimit) {
        if (tpmLimit <= 0) {
            return true;
        }
        RAtomicLong c = redisson.getAtomicLong(tpmKey(scope));
        return c.get() < tpmLimit;
    }

    /** 累加本次实际消耗 token 到指定维度当前分钟窗口（响应后调；Lua 原子 incrby + 首写设 120s 过期自清）。 */
    public void recordTokens(String scope, long tokens) {
        if (scope == null || tokens <= 0) {
            return;
        }
        redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, TPM_INCR_LUA, RScript.ReturnType.INTEGER,
                Collections.<Object>singletonList(tpmKey(scope)), String.valueOf(tokens), "120000");
    }

    /**
     * TPM 分钟桶 key。分钟号用 JVM 时钟分桶——TPM 是<b>软闸门</b>（请求前 {@code <} 判，非原子硬扣），各实例
     * allow 判定与 record 累加都用本实例时钟、自洽；多实例时钟漂移最多在分钟边界把同分钟计数分到相邻两桶，
     * 令软闸门在边界处略偏宽松，对近似限速无害。硬并发闸（{@link ConcurrencySlot}）已用 Redis 服务端时钟杜绝漂移。
     */
    private String tpmKey(String scope) {
        return TPM_PREFIX + scope + ":" + (System.currentTimeMillis() / 60_000L);
    }
}
