package com.nexusai.router.algo;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

/**
 * 加权随机选择（基数 weight+10 防 0 权重饿死）。
 *
 * @author nexusai
 */
public final class WeightedPicker {

    private static final int BASE = 10;

    private WeightedPicker() {
    }

    public static <T> T pick(List<T> items, ToIntFunction<T> weightFn) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        long total = 0;
        long[] acc = new long[items.size()];
        for (int i = 0; i < items.size(); i++) {
            long w = Math.max(0, weightFn.applyAsInt(items.get(i))) + BASE;
            total += w;
            acc[i] = total;
        }
        long r = ThreadLocalRandom.current().nextLong(total);
        for (int i = 0; i < acc.length; i++) {
            if (r < acc[i]) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }
}
