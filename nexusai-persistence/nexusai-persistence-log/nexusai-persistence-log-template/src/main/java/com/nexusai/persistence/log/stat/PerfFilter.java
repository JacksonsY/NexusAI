package com.nexusai.persistence.log.stat;

/**
 * 性能分析筛选（性能分析页）：渠道/模型/能力/流式四过滤，
 * 全部可空（空=不限）。值经参数绑定，无注入面。
 *
 * @param channelId 渠道 id
 * @param model     模型名精确匹配
 * @param modality  能力（chat/embedding/image/rerank）
 * @param stream    流式过滤（null=不限）
 * @author nexusai
 */
public record PerfFilter(Long channelId, String model, String modality, Boolean stream) {

    public static final PerfFilter NONE = new PerfFilter(null, null, null, null);

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    public boolean hasModality() {
        return modality != null && !modality.isBlank();
    }
}
