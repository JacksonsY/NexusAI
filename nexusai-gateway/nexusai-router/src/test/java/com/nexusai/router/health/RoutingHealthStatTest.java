package com.nexusai.router.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自适应 RPM 置信度门控 + 探测回升（#5）、远端熔断镜像（#1）、半开单飞探测 + 按轮次退避（#3）单测。
 */
class RoutingHealthStatTest {

    @Test
    void rpmLearningRequiresConfirmationThenAppliesSafetyMargin() {
        RoutingHealthStat s = new RoutingHealthStat();
        s.observeRpmLimit(100);
        assertThat(s.rpmLearned()).as("单次观测不落地·防偶发 429 误学").isZero();
        s.observeRpmLimit(100);
        assertThat(s.rpmLearned()).as("第二次一致确认 → floor(100×0.95)").isEqualTo(95);
    }

    @Test
    void rpmLearningTakesTighterOnNewConfirmedObservation() {
        RoutingHealthStat s = new RoutingHealthStat();
        s.observeRpmLimit(100);
        s.observeRpmLimit(100);   // learned=95
        s.observeRpmLimit(40);    // 新候选（差异 > 容差）→ 重置确认计数
        assertThat(s.rpmLearned()).isEqualTo(95);
        s.observeRpmLimit(40);    // 新候选第二次确认 → min(95, floor(40×0.95)=38)
        assertThat(s.rpmLearned()).isEqualTo(38);
    }

    @Test
    void sustainedSuccessProbesUpAndClearsLearnedRpm() {
        RoutingHealthStat s = new RoutingHealthStat();
        s.observeRpmLimit(100);
        s.observeRpmLimit(100);   // learned=95·探测计数归零
        for (int i = 0; i < 19; i++) {
            s.recordSuccess(10);
        }
        assertThat(s.rpmLearned()).as("未达探测阈值仍在").isEqualTo(95);
        s.recordSuccess(10);      // 第 20 次连续成功 → 探测性解除（自愈·不靠重启）
        assertThat(s.rpmLearned()).as("充分成功后解除学习上限").isZero();
    }

    @Test
    void remoteBanMirrorMakesUnschedulableUntilExpiry() {
        RoutingHealthStat s = new RoutingHealthStat();
        long now = 1_000_000L;
        assertThat(s.schedulable(now)).isTrue();
        s.remoteBannedUntil(now + 5_000);   // 其他实例熔断经 RTopic 广播落本地镜像
        assertThat(s.schedulable(now)).as("远端熔断镜像参与本地调度判定·H6 多实例一致").isFalse();
        assertThat(s.blockedUntil()).isEqualTo(now + 5_000);
        assertThat(s.schedulable(now + 6_000)).as("到点惰性复活").isTrue();
    }

    @Test
    void remoteBanMergesMaxNeverShrinks() {
        RoutingHealthStat s = new RoutingHealthStat();
        s.remoteBannedUntil(2_000);
        s.remoteBannedUntil(1_000);   // 更早的戳不得缩短封禁
        assertThat(s.blockedUntil()).isEqualTo(2_000);
    }

    @Test
    void halfOpenReleasesSingleProbeThenBlocksUntilSuccess() {
        RoutingHealthStat s = new RoutingHealthStat();
        long now = 1_000_000L;
        long until = s.applyTransientBackoff(now, 10_000L, 5);   // 首次失败 → 熔断 10s
        assertThat(until).isEqualTo(now + 10_000L);
        assertThat(s.tryAcquireForDispatch(now + 5_000L)).as("熔断窗口内数据面全拒").isFalse();

        long probeAt = now + 10_000L;   // 熔断到期 → 半开
        assertThat(s.tryAcquireForDispatch(probeAt)).as("半开首个请求放行探测").isTrue();
        assertThat(s.tryAcquireForDispatch(probeAt)).as("其余并发仍视为不可调度·防探测风暴").isFalse();
        assertThat(s.schedulable(probeAt)).as("schedulable 纯读不受单飞影响·控制面判活为 true").isTrue();

        s.recordSuccess(10);   // 探测成功 → 熔断闭合
        assertThat(s.tryAcquireForDispatch(probeAt)).as("闭合后并发全放行").isTrue();
        assertThat(s.tryAcquireForDispatch(probeAt)).isTrue();
    }

    @Test
    void transientBackoffAdvancesPerRoundNotPerConcurrentFailure() {
        RoutingHealthStat s = new RoutingHealthStat();
        long base = 10_000L;
        long t0 = 1_000_000L;
        assertThat(s.applyTransientBackoff(t0, base, 5)).as("轮1·exp0 → 10s").isEqualTo(t0 + base);
        // 同故障窗口内 N 个并发在途失败：不推进指数，沿用当轮到期（退避语义为「连续轮次」而非「并发次数」）
        assertThat(s.applyTransientBackoff(t0 + 1_000L, base, 5)).as("同轮散兵不推进").isEqualTo(t0 + base);
        assertThat(s.applyTransientBackoff(t0 + 2_000L, base, 5)).as("同轮散兵不推进").isEqualTo(t0 + base);

        long t1 = t0 + base;   // 轮1 到期后新一轮失败 → exp1 → 20s
        assertThat(s.applyTransientBackoff(t1, base, 5)).as("新一轮才推进指数退避").isEqualTo(t1 + 2 * base);
        long t2 = t1 + 2 * base;   // 轮3 → exp2 → 40s
        assertThat(s.applyTransientBackoff(t2, base, 5)).isEqualTo(t2 + 4 * base);
    }
}
