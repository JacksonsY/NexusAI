package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 模型-渠道声明式关联对账请求（{@code channelIds} = 选中渠道集合即目标态·缺补多删）。
 *
 * @author nexusai
 */
public record AssignChannelsRequest(List<Long> channelIds) {
}
