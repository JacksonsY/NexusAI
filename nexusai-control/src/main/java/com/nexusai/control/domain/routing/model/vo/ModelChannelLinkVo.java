package com.nexusai.control.domain.routing.model.vo;

/**
 * 模型的关联渠道行（详情抽屉/关联对账·含路由行配置 + 渠道出站映射后的目标模型名）。
 * <p>对齐前端 {@code NexusAI.ModelChannelLink}：组件名与 JSON 键一一对应。
 *
 * @author nexusai
 */
public record ModelChannelLinkVo(
        Long channelId,
        String channelName,
        String protocol,
        String baseUrl,
        Integer channelStatus,
        boolean abilityEnabled,
        Integer priority,
        Integer weight,
        String targetModel) {
}
