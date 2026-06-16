package com.nexusai.router.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自适应 RPM 置信度门控 + 探测回升（#5）与远端熔断镜像（#1）单测。
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
}
