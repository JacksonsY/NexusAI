package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 模型批量删除请求（{@code ids} = 目标模型 id）。
 *
 * @author nexusai
 */
public record ModelBatchDeleteRequest(List<Long> ids) {
}
