import { request } from '../request';

// ===== 代理节点 Proxy Nodes（/admin/proxy-nodes·ProxyNodes） =====

export function fetchProxyNodes() {
  return request<NexusAI.ProxyNode[]>({ url: '/admin/proxy-nodes' });
}

export function fetchProxyNode(id: string) {
  return request<NexusAI.ProxyNodeDetail>({ url: `/admin/proxy-nodes/${id}` });
}

export function createProxyNode(data: NexusAI.ProxyNodeUpsert) {
  return request<NexusAI.ProxyNode>({ url: '/admin/proxy-nodes/manual', method: 'post', data });
}

export function updateProxyNode(id: string, data: NexusAI.ProxyNodeUpsert) {
  return request<NexusAI.ProxyNode>({ url: `/admin/proxy-nodes/${id}`, method: 'put', data });
}

export function deleteProxyNode(id: string) {
  return request<{ clearedSystemProxy: boolean }>({ url: `/admin/proxy-nodes/${id}`, method: 'delete' });
}

export function testProxyNode(id: string) {
  return request<NexusAI.ProxyTestResult>({ url: `/admin/proxy-nodes/${id}/test`, method: 'post' });
}

export function testProxyUrl(data: NexusAI.ProxyNodeUpsert) {
  return request<NexusAI.ProxyTestResult>({ url: '/admin/proxy-nodes/test-url', method: 'post', data });
}

export function fetchProxyNodeMetrics(id: string, hours = 24) {
  return request<NexusAI.ProxyNodeMetrics>({ url: `/admin/proxy-nodes/${id}/metrics`, params: { hours } });
}

export function fetchProxyNodeEvents(id: string, limit = 50) {
  return request<NexusAI.ProxyNodeEvent[]>({ url: `/admin/proxy-nodes/${id}/events`, params: { limit } });
}

// ===== 模块管理 Modules（/admin/modules/status·ModuleManagement） =====

export function fetchModuleStatus() {
  return request<NexusAI.ModuleStatus[]>({ url: '/admin/modules/status' });
}

export function setModuleEnabled(name: string, enabled: boolean) {
  return request({ url: `/admin/modules/status/${name}/enabled`, method: 'put', data: { enabled } });
}

export function fetchModuleSettings(name: string) {
  return request<NexusAI.ModuleSettings>({ url: `/admin/modules/${name}/settings` });
}

export function saveModuleSettings(name: string, values: Record<string, unknown>) {
  return request({ url: `/admin/modules/${name}/settings`, method: 'put', data: values });
}

export function runModuleAction(name: string) {
  return request<{ message: string }>({ url: `/admin/modules/${name}/action`, method: 'post' });
}

export function fetchModuleOrder() {
  return request<string[]>({ url: '/admin/modules/order' });
}

export function saveModuleOrder(order: string[]) {
  return request({ url: '/admin/modules/order', method: 'put', data: order });
}

// ===== 系统设置 System（/admin/system·SystemSettings） =====

export function fetchSystemConfigs() {
  return request<Record<string, unknown>>({ url: '/admin/system/configs' });
}

/** 批量保存（section 一次提交多键） */
export function saveSystemConfigs(values: Record<string, unknown>) {
  return request({ url: '/admin/system/configs', method: 'put', data: values });
}

export function deleteSystemConfig(key: string) {
  return request({ url: `/admin/system/configs/${key}`, method: 'delete' });
}

export function fetchSystemVersion() {
  return request<NexusAI.SystemVersion>({ url: '/admin/system/version' });
}

export function exportSystemConfig() {
  return request<Record<string, unknown>>({ url: '/admin/system/config/export' });
}

export function importSystemConfig(payload: Record<string, unknown>, mergeMode: 'skip' | 'overwrite') {
  return request<NexusAI.ConfigImportStats>({
    url: '/admin/system/config/import',
    method: 'post',
    data: payload,
    params: { mergeMode }
  });
}

export function purgeSystemData(target: string) {
  return request<{ target: string; deleted: number }>({ url: `/admin/system/purge/${target}`, method: 'post' });
}

// ===== 邮件配置 Email（/admin/email·EmailSettings·仅 SMTP 段） =====

export function fetchEmailConfig() {
  return request<NexusAI.EmailConfig>({ url: '/admin/email/config' });
}

export function saveEmailConfig(data: Partial<NexusAI.EmailConfig> & { password?: string }) {
  return request({ url: '/admin/email/config', method: 'put', data });
}

export function testEmailConnection() {
  return request<{ message: string }>({ url: '/admin/email/test-connection', method: 'post' });
}

export function testEmailSend(to: string) {
  return request<{ message: string }>({ url: '/admin/email/test-send', method: 'post', data: { to } });
}

// ===== IP 安全（/admin/ip-security·IPSecurity） =====

export function fetchIpBlacklist() {
  return request<NexusAI.IpBlacklistEntry[]>({ url: '/admin/ip-security/blacklist' });
}

export function addIpBlacklist(data: { ip: string; reason: string; ttlSeconds?: number }) {
  return request({ url: '/admin/ip-security/blacklist', method: 'post', data });
}

export function removeIpBlacklist(ip: string) {
  return request({ url: '/admin/ip-security/blacklist', method: 'delete', params: { ip } });
}

export function fetchIpWhitelist() {
  return request<string[]>({ url: '/admin/ip-security/whitelist' });
}

export function addIpWhitelist(ip: string) {
  return request({ url: '/admin/ip-security/whitelist', method: 'post', data: { ip } });
}

export function removeIpWhitelist(ip: string) {
  return request({ url: '/admin/ip-security/whitelist', method: 'delete', params: { ip } });
}

// ===== 审计日志（/admin/audit-logs·AuditLogs） =====

export function fetchAuditLogs(params: {
  actor?: string;
  eventType?: string;
  days?: number;
  page?: number;
  pageSize?: number;
}) {
  return request<{ items: NexusAI.AuditLog[]; total: number }>({ url: '/admin/audit-logs', params });
}

export function fetchAuditEventTypes() {
  return request<string[]>({ url: '/admin/audit-logs/event-types' });
}

// ===== 通知服务（/admin/notifications·ImportantNotification 独立页） =====

export function fetchNotificationConfig() {
  return request<NexusAI.NotificationConfig>({ url: '/admin/notifications/config' });
}

export function saveNotificationConfig(data: Partial<NexusAI.NotificationConfig>) {
  return request({ url: '/admin/notifications/config', method: 'put', data });
}

export function testNotification(data: { itemKey: string; channel?: string }) {
  return request<NexusAI.NotificationTestResult[]>({ url: '/admin/notifications/test', method: 'post', data });
}
