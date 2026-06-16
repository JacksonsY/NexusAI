package com.nexusai.control.domain.monitor.cache.vo;

import java.util.List;

/**
 * 模型别名快照（缓存监控·模型别名缓存）。
 * <p>字段名/顺序/类型与前端 {@code NexusAI.AliasSnapshot} 及原 Map 构造顺序严格对齐。
 *
 * @author nexusai
 */
public record AliasSnapshotVo(
        int modelCount,
        int ruleCount,
        long loadedAt,
        long refreshMs,
        List<Rule> rules) {

    /** 别名规则行（含「提供商」列）。 */
    public record Rule(
            String pattern,
            String modelKey,
            String modelAlias,
            Integer status,
            List<String> providers) {
    }
}
