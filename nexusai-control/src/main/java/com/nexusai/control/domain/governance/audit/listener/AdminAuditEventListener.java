package com.nexusai.control.domain.governance.audit.listener;

import com.nexusai.control.domain.governance.audit.service.AuditLogService;
import com.nexusai.common.event.AdminAuditEvent;
import com.nexusai.persistence.po.NaiAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 审计事件监听：登录成败/登出等由 control-api 显式发布的事件落库。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class AdminAuditEventListener {

    private final AuditLogService auditLogService;

    @EventListener
    public void on(AdminAuditEvent event) {
        NaiAuditLog entry = new NaiAuditLog();
        entry.setActor(event.actor());
        entry.setEventType(event.eventType());
        entry.setDescription(event.description());
        entry.setIp(event.ip());
        entry.setErrorMessage(event.errorMessage());
        auditLogService.record(entry);
    }
}
