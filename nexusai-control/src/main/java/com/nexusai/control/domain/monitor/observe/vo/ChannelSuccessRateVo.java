package com.nexusai.control.domain.monitor.observe.vo;

/**
 * 渠道成功率排行（近 7 天·Dashboard）。
 * <p>字段名/类型与前端 {@code NexusAI.ChannelSuccessRate} 严格对齐（channelId 为 Long·全局序列化为字符串）。
 *
 * @author nexusai
 */
public record ChannelSuccessRateVo(
        Long channelId,
        String channelName,
        long successCount,
        long failedCount,
        double successRate) {
}
