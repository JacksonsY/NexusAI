package com.nexusai.control.domain.routing.channel.vo;

import java.util.List;

/**
 * 渠道批量删除请求（{@code ids} = 目标渠道·连带删凭证与路由索引）。
 *
 * @author nexusai
 */
public record ChannelBatchDeleteRequest(List<Long> ids) {
}
