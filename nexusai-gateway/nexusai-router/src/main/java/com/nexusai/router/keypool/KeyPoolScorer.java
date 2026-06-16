package com.nexusai.router.keypool;
import com.nexusai.router.health.RoutingHealthStat;

import com.nexusai.persistence.po.NaiChannelKey;
import org.springframework.stereotype.Component;

/**
 * KeyPoolScorer 多因子打分（四层选号③加权 TopK）：
 * <pre>score = healthFactor × latencyFactor × errorFactor × rpmFactor × weight</pre>
 * <ul>
 *   <li>healthFactor = healthScore/100（0~1·累积健康趋势）；</li>
 *   <li>latencyFactor = 1/(1+ewma/baseline)（延迟/TTFT 越低越优·EWMA 未采样=1）；</li>
 *   <li>errorFactor = 1 − 0.7×errorRateEwma（近窗错误率越高越让步·与 health 的累积趋势互补·短窗更灵敏）；</li>
 *   <li>rpmFactor = 学习到 RPM 上限时按健康分轻微让步（避让接近限额的 Key）。</li>
 * </ul>
 * 配置静态 weight 作乘子，保留运维显式偏好。
 *
 * @author nexusai
 */
@Component
public class KeyPoolScorer {

    /** 延迟基准（ms）：EWMA 等于基准时 latencyFactor=0.5 */
    private static final double LATENCY_BASELINE_MS = 800.0;

    public double score(NaiChannelKey key, RoutingHealthStat stat) {
        double health = Math.max(1, stat.healthScore()) / 100.0;
        double ewma = stat.latencyEwmaMs();
        double latencyFactor = ewma <= 0 ? 1.0 : 1.0 / (1.0 + ewma / LATENCY_BASELINE_MS);
        double errorFactor = 1.0 - 0.7 * Math.min(1.0, Math.max(0.0, stat.errorRateEwma()));   // 近窗错误率让步
        double weight = key.getWeight() == null ? 1 : Math.max(1, key.getWeight());
        double base = health * latencyFactor * errorFactor * weight;
        // 学习到 RPM 约束时按健康分轻微让步（避让濒临限额者），避免反复撞限流
        if (stat.rpmLearned() > 0) {
            base *= 0.5 + 0.5 * health;
        }
        return Math.max(1e-6, base);
    }
}
