package com.nexusai.router.algo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * 加权 TopK 排序（四层选号③的纯算法·从 KeyPoolSelector 提出）：打分降序 → 头部 TopK 加权随机定主选
 * → 主选置首其余按分降序，组成占槽尝试序；
 * 可选锚定元素在<b>负载安全</b>时置顶（锚定分 ≥ 最高分 × 比例闸，劣化/过热则让位负载均衡）。
 * <p>纯函数、无状态、零 IO——打分函数对每个元素只调用一次（先物化再排序）。
 *
 * @author nexusai
 */
public final class TopKWeightedOrder {

    private TopKWeightedOrder() {
    }

    /**
     * @param items               候选（非空）
     * @param scoreFn             打分函数（每元素调用一次）
     * @param topK                加权随机的候选头部规模
     * @param anchor              锚定判定（null = 无锚定）
     * @param anchorMinScoreRatio 锚定置顶的负载安全闸：锚定分 ≥ 最高分 × 此比例才置顶
     * @return 占槽尝试序（主选/锚定在前，其余按分降序）
     */
    public static <T> List<T> order(List<T> items, ToDoubleFunction<T> scoreFn, int topK,
                                    Predicate<T> anchor, double anchorMinScoreRatio) {
        record Scored<T>(T item, double score) {
        }
        List<Scored<T>> scored = new ArrayList<>(items.size());
        for (T item : items) {
            scored.add(new Scored<>(item, scoreFn.applyAsDouble(item)));
        }
        scored.sort(Comparator.comparingDouble((Scored<T> s) -> s.score()).reversed());

        // 头部 TopK 加权随机定主选
        int k = Math.min(topK, scored.size());
        double sum = 0;
        for (int i = 0; i < k; i++) {
            sum += scored.get(i).score();
        }
        int primaryIdx = 0;
        if (sum > 0) {
            double r = ThreadLocalRandom.current().nextDouble(sum);
            double acc = 0;
            for (int i = 0; i < k; i++) {
                acc += scored.get(i).score();
                if (r < acc) {
                    primaryIdx = i;
                    break;
                }
            }
        }
        List<T> order = new ArrayList<>(scored.size());
        order.add(scored.get(primaryIdx).item());
        for (int i = 0; i < scored.size(); i++) {
            if (i != primaryIdx) {
                order.add(scored.get(i).item());
            }
        }
        // 锚定置顶：确定性会话亲和优先于加权随机主选——但仅在负载安全时（锚定分 ≥ 最高分 × 比例）；
        // 锚定元素劣化/过热（打分远低于头部）则不置顶，让位负载均衡，避免确定性亲和把流量压垮单 Key。
        if (anchor != null && !scored.isEmpty()) {
            double topScore = scored.get(0).score();
            double anchorScore = -1;
            for (Scored<T> s : scored) {
                if (anchor.test(s.item())) {
                    anchorScore = s.score();
                    break;
                }
            }
            if (anchorScore >= 0 && anchorScore >= anchorMinScoreRatio * topScore) {
                for (int i = 1; i < order.size(); i++) {
                    if (anchor.test(order.get(i))) {
                        order.add(0, order.remove(i));
                        break;
                    }
                }
            }
        }
        return order;
    }
}
