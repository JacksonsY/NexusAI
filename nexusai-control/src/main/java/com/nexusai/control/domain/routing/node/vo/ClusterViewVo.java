package com.nexusai.control.domain.routing.node.vo;

import java.util.List;

/**
 * 集群视图（节点列表 + 集群汇总）。
 * <p>对齐前端 {@code NexusAI.ClusterView}。
 *
 * @author nexusai
 */
public record ClusterViewVo(List<NodeVo> items, ClusterSummaryVo summary) {
}
