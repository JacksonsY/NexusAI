package com.nexusai.observe.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 流量面实时并发指标（性能分析页「实时运行状态」·本机视角）：
 * 进程内计数器（处理中/累计/峰值/被限流 429），由 {@link RelayMetricsWebFilter} 在 /v1/** 生命周期更新。
 * <p>单实例进程视角；多实例聚合视图属二期（Redis 下沉），与熔断态同决策。
 *
 * @author nexusai
 */
@Component
public class RelayInflightMetrics {

    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();
    private final LongAdder total = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    /** 请求进入：处理中 +1，刷新峰值，累计 +1。 */
    public void onStart() {
        long current = inFlight.incrementAndGet();
        peak.accumulateAndGet(current, Math::max);
        total.increment();
    }

    /** 请求终止（含取消/异常）：处理中 -1；429 计入被限流。 */
    public void onFinish(Integer httpStatus) {
        inFlight.decrementAndGet();
        if (httpStatus != null && httpStatus == 429) {
            rejected.increment();
        }
    }

    public long inFlight() {
        return Math.max(inFlight.get(), 0);
    }

    public long peak() {
        return peak.get();
    }

    public long total() {
        return total.sum();
    }

    public long rejected() {
        return rejected.sum();
    }
}
