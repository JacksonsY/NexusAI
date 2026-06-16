package com.nexusai.control.domain.routing.channel.vo;

/**
 * 渠道测速探活结果（提供商页·探上游连通性 + 鉴权 + 延迟·不计费）。
 * <p>对齐前端 {@code NexusAI.ChannelTestResult}：{@code keyId} 仅探测成功路径有值，失败时为 null。
 *
 * @author nexusai
 */
public record ChannelTestResultVo(
        boolean ok,
        int httpStatus,
        long latencyMs,
        Long keyId,
        String message) {

    /** 探测失败（未取到 Key / 渠道无效 / 不可达）。 */
    public static ChannelTestResultVo fail(String message) {
        return new ChannelTestResultVo(false, 0, 0, null, message);
    }
}
