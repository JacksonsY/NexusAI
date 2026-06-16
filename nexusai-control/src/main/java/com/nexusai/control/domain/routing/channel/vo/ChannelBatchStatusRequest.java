package com.nexusai.control.domain.routing.channel.vo;

import java.util.List;

/**
 * 渠道批量启停请求（{@code ids} = 目标渠道·{@code status} = 1 启用 / 0 停用）。
 *
 * @author nexusai
 */
public record ChannelBatchStatusRequest(List<Long> ids, Integer status) {
}
