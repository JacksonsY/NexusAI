package com.nexusai.control.domain.routing.channel.vo;

import java.util.List;

/**
 * 批量注册上游模型为本渠道 ability 请求（{@code models} = 勾选注册的上游模型 id）。
 *
 * @author nexusai
 */
public record RegisterUpstreamModelsRequest(List<String> models) {
}
