package com.nexusai.control.domain.routing.model.vo;

import java.math.BigDecimal;

/**
 * 模型同步预览 · 上游单模型元数据（models.dev 条目择优后投影到 nai_model 字段口径）。
 * <p>对齐前端 {@code NexusAI.ModelSyncMeta}：三费率为 {@link BigDecimal}（Jackson 输出数值）。
 *
 * @author nexusai
 */
public record ModelSyncMetaVo(
        String modelKey,
        String modelAlias,
        String providerId,
        BigDecimal inputRate,
        BigDecimal outputRate,
        BigDecimal cachedInputRate,
        Integer contextLength,
        Integer maxOutputTokens,
        String modality) {
}
