package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 链路预览 · 渠道节点（第一级选渠道·含下挂渠道密钥池）。
 * <p>对齐前端 {@code NexusAI.ModelRoutingChannel}：组件名与 JSON 键一一对应。
 *
 * @author nexusai
 */
public record ModelRoutingChannelVo(
        Long channelId,
        String name,
        String baseUrl,
        int priority,
        int weight,
        Integer channelStatus,
        boolean abilityEnabled,
        String targetModel,
        int healthScore,
        boolean schedulable,
        List<ModelRoutingKeyVo> keys) {
}
