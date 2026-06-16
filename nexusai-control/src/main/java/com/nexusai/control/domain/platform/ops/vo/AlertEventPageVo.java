package com.nexusai.control.domain.platform.ops.vo;

import com.nexusai.persistence.po.NaiAlertEvent;

import java.util.List;

/**
 * 告警事件分页（items + total·非框架 {@code records/pageNum/pageSize} 形态）。
 * <p>组件名/类型与前端 {@code { items: NexusAI.AlertEvent[]; total: number }} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record AlertEventPageVo(List<NaiAlertEvent> items, long total) {
}
