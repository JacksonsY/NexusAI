package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 链路预览 · 协议家族组（api_format 组·计数 + 组内渠道）。
 * <p>对齐前端 {@code NexusAI.ModelRoutingGroup}：Long 计数经全局 Jackson 序列化为字符串。
 *
 * @author nexusai
 */
public record ModelRoutingGroupVo(
        String protocol,
        long totalChannels,
        long activeChannels,
        long totalKeys,
        long activeKeys,
        List<ModelRoutingChannelVo> channels) {
}
