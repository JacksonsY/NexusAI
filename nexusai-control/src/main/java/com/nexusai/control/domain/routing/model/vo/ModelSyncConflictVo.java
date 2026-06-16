package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 模型同步预览 · 字段冲突（本地 sync_official=1 模型 vs models.dev 上游逐字段 diff）。
 * <p>对齐前端 {@code NexusAI.ModelSyncConflict}：{@code fields[].local/upstream} 为原值
 * （String/Integer/BigDecimal·序列化字节等同原 Map）。
 *
 * @author nexusai
 */
public record ModelSyncConflictVo(String modelKey, String providerId, List<FieldDiff> fields) {

    /** 单字段差异（local=本地现值·upstream=上游候选值·可为 null）。 */
    public record FieldDiff(String field, Object local, Object upstream) {
    }
}
