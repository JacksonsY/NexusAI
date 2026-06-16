package com.nexusai.control.domain.routing.channel.vo;

import java.util.List;

/**
 * 上游模型拉取结果（GET {baseUrl}/models·与本渠道已注册 ability 对照）。
 * <p>对齐前端 {@code NexusAI.ChannelUpstreamModels}：{@code ok} 必有；成功时 {@code models}/{@code registered}
 * 为已排序模型 id 列表，失败时 {@code message} 有值。
 *
 * @author nexusai
 */
public record ChannelUpstreamModelsVo(
        boolean ok,
        String message,
        List<String> models,
        List<String> registered) {

    /** 成功结果（上游模型列表 + 已注册集合·均已排序）。 */
    public static ChannelUpstreamModelsVo ok(List<String> models, List<String> registered) {
        return new ChannelUpstreamModelsVo(true, null, models, registered);
    }

    /** 失败结果（仅错误信息）。 */
    public static ChannelUpstreamModelsVo fail(String message) {
        return new ChannelUpstreamModelsVo(false, message, null, null);
    }
}
