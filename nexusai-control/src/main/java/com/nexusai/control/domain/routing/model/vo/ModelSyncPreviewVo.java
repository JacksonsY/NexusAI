package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 模型上游元数据同步预览（缺失模型 + 字段冲突）。
 * <p>对齐前端 {@code NexusAI.ModelSyncPreview}：{@code missing} 为可建模型元数据列表，
 * {@code conflicts} 为本地与上游逐字段差异列表。
 *
 * @author nexusai
 */
public record ModelSyncPreviewVo(List<ModelSyncMetaVo> missing, List<ModelSyncConflictVo> conflicts) {
}
