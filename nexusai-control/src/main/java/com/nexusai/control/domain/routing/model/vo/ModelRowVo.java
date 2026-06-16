package com.nexusai.control.domain.routing.model.vo;

import com.nexusai.persistence.po.NaiModel;

import java.time.LocalDateTime;

/**
 * 模型目录列表行 = 模型 + 三统计聚合（关联渠道数 active/total + 近 30 天调用次数）。
 * <p>对齐前端 {@code NexusAI.ModelRow}：Long 计数经全局 Jackson 序列化为字符串；
 * {@code lastUsedAt} 维持原 Map 持有的 {@link LocalDateTime}（Jackson 输出 ISO 串）。
 *
 * @author nexusai
 */
public record ModelRowVo(
        NaiModel model,
        long channelCount,
        long activeChannelCount,
        long usageCount,
        LocalDateTime lastUsedAt) {
}
