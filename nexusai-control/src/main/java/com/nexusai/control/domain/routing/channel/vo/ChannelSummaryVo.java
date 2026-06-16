package com.nexusai.control.domain.routing.channel.vo;

import com.nexusai.persistence.po.NaiChannel;

import java.time.LocalDateTime;

/**
 * 渠道汇总行（提供商页·配置 + 凭证/模型水位 + 近 24h 流量 + 运行时健康）。
 * <p>对齐前端 {@code NexusAI.ChannelSummary}：组件名与 JSON 键一一对应，序列化字节等同原 Map。
 * Long 计数经全局 Jackson 序列化为字符串（雪花/精度策略），与前端 {@code number | string} 一致；
 * {@code lastEventAt} 维持原 Map 持有的 {@link LocalDateTime}（Jackson 输出 ISO 串）。
 *
 * @author nexusai
 */
public record ChannelSummaryVo(
        NaiChannel channel,
        long totalKeys,
        long activeKeys,
        long schedulableKeys,
        String maskedCredential,
        long totalModels,
        long activeModels,
        long requests24h,
        Double successRate24h,
        LocalDateTime lastEventAt,
        int healthScore,
        boolean channelSchedulable) {
}
