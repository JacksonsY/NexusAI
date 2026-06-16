package com.nexusai.control.domain.platform.ops.algo;

/**
 * 流量速率纯算法（零 Spring/零 IO）：分钟桶序列 → QPS/TPS 三元组
 * （current=最近 60s 单桶速率 / peak=窗口内最热桶速率 / avg=全窗均值），
 * 供 overview 流量卡与实时流量窗口两处复用（原先两处逐行重复的求和/取峰/均值数学收口于此）。
 *
 * @author nexusai
 */
public final class TrafficMath {

    private TrafficMath() {
    }

    /** 速率三元组（次/秒或 token/秒·两位小数）。 */
    public record Rates(double current, double peak, double avg) {
    }

    /**
     * @param current60sCount 最近 60s 单桶计数（current = count/60）
     * @param perSegment      窗口内逐段计数
     * @param segSeconds      每段秒数（peak = 最大段计数/segSeconds）
     * @param windowSeconds   全窗秒数（avg = 总计数/windowSeconds）
     */
    public static Rates rates(long current60sCount, long[] perSegment, long segSeconds, long windowSeconds) {
        long sum = 0;
        long max = 0;
        for (long v : perSegment) {
            sum += v;
            max = Math.max(max, v);
        }
        long window = Math.max(windowSeconds, 1);
        long seg = Math.max(segSeconds, 1);
        return new Rates(round2(current60sCount / 60.0), round2((double) max / seg), round2((double) sum / window));
    }

    public static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
