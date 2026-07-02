package com.nexusai.router.health;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单 Key 运行时统计（健康分/延迟 EWMA/错误率/自适应 RPM/熔断戳本地·DB 仅存配置）。
 * <p><b>熔断戳含远端镜像</b>（{@code remoteBanUntil}）：多实例下别的实例熔断某 Key/渠道经
 * {@link RuntimeStateStore} RTopic 广播落到本字段，{@link #schedulable} 一并判，无需热路径查 Redis（H6 多实例一致）。
 * <p><b>半开单飞探测</b>：熔断到期后并非放开全部并发——{@link #schedulable} 保持纯读（观测/控制面判活用），
 * 数据面选号经 {@link #tryAcquireForDispatch} 以 CAS 只放行<b>一个</b>探测请求，其余仍视为不可调度，
 * 防「刚复活即被并发打爆」的探测风暴；探测成功即闭合、失败按<b>轮次</b>指数退避（见 {@link #applyTransientBackoff}）。
 * <p><b>自适应 RPM 置信度门控</b>：观测到的上游 RPM 需连续确认 + safety margin 才落地（防单次偶发
 * 429 误学），充分成功后探测性解除（自愈·不靠重启复位）。
 *
 * @author nexusai
 */
public class RoutingHealthStat {

    /** 健康分 0~100（初始满分） */
    private volatile int healthScore = 100;
    /** 延迟 EWMA（ms·0 表示未采样） */
    private volatile double latencyEwmaMs = 0;
    /** 自适应学习的 RPM 上限（0 表示未学习到约束·已含 safety margin） */
    private volatile int rpmLearned = 0;
    /** 近窗错误率 EWMA（0~1·每请求按"是否错误"指数滑动·打分因子⑤） */
    private volatile double errorRateEwma = 0;
    /** 连续临时错误次数（指数退避用·成功即清零=半开探测成功复位） */
    private volatile int consecutiveTransient = 0;

    /** RPM 学习置信度门控：候选观测值 + 连续一致确认次数（达阈值才落地 rpmLearned） */
    private volatile int rpmObservedPending = 0;
    private volatile int rpmObserveCount = 0;
    /** 学习上限生效后的连续成功计数（达阈值探测性解除 = 自愈回升） */
    private volatile int rpmProbeSuccess = 0;

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    /** Key 级熔断到期戳（epoch ms·<=now 即可调度） */
    private volatile long rateLimitResetAt = 0;
    private volatile long overloadUntil = 0;
    private volatile long tempUnschedulableUntil = 0;
    /** 远端实例熔断镜像（RTopic 广播落地·与本地三戳同语义参与调度判定·H6 多实例一致） */
    private volatile long remoteBanUntil = 0;

    /**
     * 半开探测门：任一熔断戳被置入未来即 {@code true}（曾熔断·待经一次成功探测闭合）；探测成功 {@link #recordSuccess} 复位。
     * 与 {@link #probeLeaseUntil} 合成「半开态只放一个探测」的单飞闸。
     */
    private volatile boolean probePending = false;
    /**
     * 在飞探测租约到期戳（epoch ms·CAS 抢占）：半开态某请求抢到探测名额后置 {@code now + PROBE_LEASE_MS}，
     * 其余请求见 {@code now < 租约} 即让位（视为不可调度）。租约兜底防探测请求丢失（线程死/请求弃）致 Key 永久锁死——
     * 到期后另一请求可再抢探测；正常探测由 {@link #recordSuccess} 或新一轮熔断即时收敛，租约仅兜底。
     */
    private final AtomicLong probeLeaseUntil = new AtomicLong(0);

    private static final double EWMA_ALPHA = 0.3;
    /** 半开单飞探测租约：探测请求未回报 success/error 的兜底重放窗口（防丢失探测永久锁死 Key）。 */
    private static final long PROBE_LEASE_MS = 30_000L;

    /** RPM 学习落地所需的连续一致观测次数（置信度门控·防单次偶发误学） */
    private static final int RPM_LEARN_CONFIRMATIONS = 2;
    /** 学习 RPM 的 safety margin（取观测值的 95%·留余量防贴边再撞） */
    private static final double RPM_SAFETY_MARGIN = 0.95;
    /** 学习上限生效后连续成功多少次即探测性解除（自愈回升） */
    private static final int RPM_PROBE_SUCCESS_THRESHOLD = 20;

    public boolean schedulable(long now) {
        return rateLimitResetAt <= now && overloadUntil <= now
                && tempUnschedulableUntil <= now && remoteBanUntil <= now;
    }

    /**
     * 数据面选号闸（含半开单飞）：熔断窗口内→{@code false}；全闭合且非半开→{@code true}（常态无锁）；
     * 半开态（熔断刚到期、待探测）→ CAS 只放<b>一个</b>探测请求，其余 {@code false}（防探测风暴）。
     * <p>与 {@link #schedulable}（纯读·观测/控制面判活用）分离：探测名额是<b>副作用</b>，只能由真正要发起请求的数据面消费。
     */
    public boolean tryAcquireForDispatch(long now) {
        if (!schedulable(now)) {
            return false;   // OPEN：熔断窗口内
        }
        if (!probePending) {
            return true;    // CLOSED：全闭合·常态
        }
        // HALF_OPEN：CAS 抢占唯一探测名额，抢到者放行、其余让位
        long lease = probeLeaseUntil.get();
        if (now < lease) {
            return false;   // 已有探测在飞（租约内）
        }
        return probeLeaseUntil.compareAndSet(lease, now + PROBE_LEASE_MS);
    }

    /** 熔断解除时刻（epoch ms·四类封禁取最晚；&le;now 表示未熔断）——链路预览倒计时用。 */
    public long blockedUntil() {
        return Math.max(Math.max(rateLimitResetAt, overloadUntil),
                Math.max(tempUnschedulableUntil, remoteBanUntil));
    }

    public synchronized void recordSuccess(long latencyMs) {
        successCount.incrementAndGet();
        latencyEwmaMs = latencyEwmaMs == 0 ? latencyMs : EWMA_ALPHA * latencyMs + (1 - EWMA_ALPHA) * latencyEwmaMs;
        healthScore = Math.min(100, healthScore + 2);
        errorRateEwma = (1 - EWMA_ALPHA) * errorRateEwma;   // 衰减向 0（本次非错误）
        consecutiveTransient = 0;                            // 半开探测成功 → 退避复位
        if (probePending) {                                 // 半开探测成功 → 撤单飞门·熔断闭合（后续并发全放行）
            probePending = false;
            probeLeaseUntil.set(0);
        }
        // 自适应 RPM 探测性回升：学习上限生效后充分成功 → 解除（自愈·不靠重启/配置变更复位）
        if (rpmLearned > 0 && ++rpmProbeSuccess >= RPM_PROBE_SUCCESS_THRESHOLD) {
            rpmLearned = 0;
            rpmObservedPending = 0;
            rpmObserveCount = 0;
            rpmProbeSuccess = 0;
        }
    }

    public synchronized void recordError(int penalty) {
        errorCount.incrementAndGet();
        healthScore = Math.max(0, healthScore - penalty);
        errorRateEwma = EWMA_ALPHA + (1 - EWMA_ALPHA) * errorRateEwma;   // 拉向 1（本次为错误）
    }

    /** 近窗错误率 EWMA（0~1）。 */
    public double errorRateEwma() {
        return errorRateEwma;
    }

    /**
     * 应用一次临时熔断（超时/5xx）并返回本次熔断到期戳（epoch ms）。<b>退避按「轮次」而非「并发次数」</b>：
     * 仅当当前不在临时熔断窗口内（{@code now >= tempUnschedulableUntil}，即新一轮探测失败或首次失败）才递增连续失败计数、
     * 推进指数退避 = baseMs × 2^min(连续失败-1, capExp)；同一故障窗口内的并发在途失败为「同轮散兵」，沿用当轮退避、
     * 不重复推进——杜绝 N 个并发失败一次把退避顶到 cap。成功一次即 {@link #recordSuccess} 清零回到闭合。
     */
    public synchronized long applyTransientBackoff(long now, long baseMs, int capExp) {
        if (now < tempUnschedulableUntil) {
            return tempUnschedulableUntil;   // 同轮散兵：不推进指数、不重复武装，沿用当轮熔断到期
        }
        int n = ++consecutiveTransient;
        int exp = Math.min(n - 1, capExp);
        long until = now + baseMs * (1L << exp);
        tempUnschedulableUntil = Math.max(tempUnschedulableUntil, until);
        armProbe();
        return until;
    }

    public int healthScore() {
        return healthScore;
    }

    public double latencyEwmaMs() {
        return latencyEwmaMs;
    }

    public int rpmLearned() {
        return rpmLearned;
    }

    /**
     * 置信度门控的 RPM 学习（替代单次 429 即落地）：观测到上游声明/推断的 RPM 上限，
     * 需连续 {@value #RPM_LEARN_CONFIRMATIONS} 次一致确认（±10% 容差）才落地为学习上限，并打 {@value #RPM_SAFETY_MARGIN}
     * safety margin 留余量；取更紧者。首次/不一致观测仅累计候选不落地——熔断冷却（rateLimitResetAt）已提供即时避让，
     * 故单次偶发 429 不会误学长期降权。
     */
    public synchronized void observeRpmLimit(int observed) {
        if (observed <= 0) {
            return;
        }
        int tol = Math.max(1, rpmObservedPending / 10);
        if (rpmObservedPending > 0 && Math.abs(observed - rpmObservedPending) <= tol) {
            rpmObserveCount++;
        } else {
            rpmObservedPending = observed;
            rpmObserveCount = 1;
        }
        if (rpmObserveCount >= RPM_LEARN_CONFIRMATIONS) {
            int withMargin = Math.max(1, (int) Math.floor(rpmObservedPending * RPM_SAFETY_MARGIN));
            rpmLearned = rpmLearned <= 0 ? withMargin : Math.min(rpmLearned, withMargin);
            rpmProbeSuccess = 0;
        }
    }

    public long successCount() {
        return successCount.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    public synchronized void rateLimitedUntil(long ts) {
        if (ts > rateLimitResetAt) {
            rateLimitResetAt = ts;
            armProbe();
        }
    }

    public synchronized void overloadedUntil(long ts) {
        if (ts > overloadUntil) {
            overloadUntil = ts;
            armProbe();
        }
    }

    public synchronized void unschedulableUntil(long ts) {
        if (ts > tempUnschedulableUntil) {
            tempUnschedulableUntil = ts;
            armProbe();
        }
    }

    /** 远端实例熔断镜像（RTopic 广播落地·max 合并·参与 {@link #schedulable} 判定·H6 多实例一致）。 */
    public synchronized void remoteBannedUntil(long ts) {
        if (ts > remoteBanUntil) {
            remoteBanUntil = ts;
            armProbe();
        }
    }

    /** 进入/延续熔断：武装半开探测门并清空在飞租约，令下一次熔断到期后的半开从干净的唯一探测名额开始。 */
    private void armProbe() {
        probePending = true;
        probeLeaseUntil.set(0);
    }
}
