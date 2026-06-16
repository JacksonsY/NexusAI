package com.nexusai.control.domain.governance.audit.vo;

import com.nexusai.persistence.po.NaiAuditLog;

import java.util.List;

/**
 * 审计日志分页（items + total·非框架 {@code records/pageNum/pageSize} 形态）。
 * <p>组件名/类型与前端 {@code { items: NexusAI.AuditLog[]; total: number }} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record AuditLogPageVo(List<NaiAuditLog> items, long total) {
}
