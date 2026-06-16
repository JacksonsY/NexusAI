package com.nexusai.control.domain.routing.model.vo;

/**
 * 模型-渠道声明式对账结果（选中渠道集合即目标态·缺补 ability 多删 ability）。
 * <p>{@code added}/{@code removed} 为本次实际新增/删除的关联数（int·序列化为 number）。
 *
 * @author nexusai
 */
public record ChannelAssignResultVo(int added, int removed) {
}
