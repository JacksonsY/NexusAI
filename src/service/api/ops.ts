import { request } from '../request';

// ===== 缓存监控 Cache（/admin/cache·一比一 Aether CacheMonitoring） =====
export function fetchCacheStats() {
  return request<NexusAI.CacheStats>({ url: '/admin/cache' });
}

/** 会话亲和统计 + 条目列表 */
export function fetchAffinity(keyword?: string) {
  return request<NexusAI.AffinityResponse>({ url: '/admin/cache/affinity', params: keyword ? { keyword } : undefined });
}

/** 清除单条亲和 */
export function clearAffinitySingle(cacheKey: string) {
  return request({ url: '/admin/cache/affinity/single', method: 'delete', params: { cacheKey } });
}

/** 清除全部亲和 */
export function clearAllAffinities() {
  return request<{ cleared: number }>({ url: '/admin/cache/affinity', method: 'delete' });
}

/** 模型别名快照 */
export function fetchAliasSnapshot() {
  return request<NexusAI.AliasSnapshot>({ url: '/admin/cache/alias' });
}

/** 手动刷新模型别名快照 */
export function refreshAliasSnapshot() {
  return request<NexusAI.AliasSnapshot>({ url: '/admin/cache/alias/refresh', method: 'post' });
}

/** Redis 键分类概览 */
export function fetchRedisCategories() {
  return request<NexusAI.RedisCategoriesResponse>({ url: '/admin/cache/redis-keys' });
}

/** 清除指定分类 Redis 键 */
export function clearRedisCategory(category: string) {
  return request<{ deletedCount: number }>({ url: `/admin/cache/redis-keys/${category}`, method: 'delete' });
}

/** 缓存命中分析 */
export function fetchCacheHitAnalysis(hours: number) {
  return request<NexusAI.CacheHitAnalysis>({ url: '/admin/cache/hit-analysis', params: { hours } });
}

/** 项目维度 TTL 分析 */
export function fetchTtlAnalysis(hours: number) {
  return request<NexusAI.TtlAnalysisResponse>({ url: '/admin/cache/ttl-analysis', params: { hours } });
}

// ===== 代理节点 Nodes（/admin/nodes·多实例集群心跳） =====
export function fetchNodes() {
  return request<NexusAI.ClusterView>({ url: '/admin/nodes' });
}

/** 手动剔除离线节点 */
export function removeNode(nodeId: string) {
  return request({ url: `/admin/nodes/${nodeId}`, method: 'delete' });
}

// ===== 模块管理 Modules（/admin/modules·SPI 注册表 + 特性开关·只读） =====
export function fetchModules() {
  return request<NexusAI.Modules>({ url: '/admin/modules' });
}

// ===== 运维监控 Ops Monitor（/admin/ops·一比一 sub2api OpsDashboard） =====

/** 仪表盘查询参数（时间范围/自定义起止/渠道筛选·一比一 sub2api buildApiParams） */
export interface OpsQueryParams {
  rangeMinutes?: number;
  startTime?: string;
  endTime?: string;
  channelId?: number;
}

/** 合并快照（窗口聚合 + 实时 QPS/TPS + 系统资源 + 健康分诊断） */
export function fetchOpsOverview(params: OpsQueryParams) {
  return request<NexusAI.OpsOverview>({ url: '/admin/ops/overview', params });
}

/** 三趋势一次拉（吞吐/错误三线/故障转移率） */
export function fetchOpsTrend(params: OpsQueryParams & { segments?: number }) {
  return request<NexusAI.OpsTrend>({ url: '/admin/ops/trend', params });
}

/** 延迟直方图（固定 6 桶） */
export function fetchOpsLatencyHistogram(params: OpsQueryParams) {
  return request<NexusAI.OpsLatencyBucket[]>({ url: '/admin/ops/latency-histogram', params });
}

/** 错误分类分布 */
export function fetchOpsErrorDistribution(params: OpsQueryParams) {
  return request<NexusAI.OpsErrorCategory[]>({ url: '/admin/ops/error-distribution', params });
}

/** 实时流量窗口（60/300/1800/3600 秒可切·一比一 sub2api realtime traffic summary） */
export function fetchOpsRealtimeTraffic(params: { windowSeconds: number; channelId?: number }) {
  return request<NexusAI.OpsRealtimeTraffic>({ url: '/admin/ops/realtime-traffic', params });
}

/** 告警评估设置（开关 + 间隔·一比一 sub2api alert runtime settings） */
export function fetchOpsAlertSettings() {
  return request<NexusAI.OpsAlertSettings>({ url: '/admin/ops/alert-settings' });
}

export function saveOpsAlertSettings(body: NexusAI.OpsAlertSettings) {
  return request<NexusAI.OpsAlertSettings>({ url: '/admin/ops/alert-settings', method: 'put', data: body });
}

/** 阈值设置 */
export function fetchOpsThresholds() {
  return request<NexusAI.OpsThresholds>({ url: '/admin/ops/thresholds' });
}

export function saveOpsThresholds(body: NexusAI.OpsThresholds) {
  return request<NexusAI.OpsThresholds>({ url: '/admin/ops/thresholds', method: 'put', data: body });
}

/** 告警规则 */
export function fetchAlertRules() {
  return request<NexusAI.AlertRule[]>({ url: '/admin/ops/alert-rules' });
}

export function fetchAlertMetricTypes() {
  return request<string[]>({ url: '/admin/ops/alert-rules/metric-types' });
}

export function createAlertRule(body: Partial<NexusAI.AlertRule> & { enabled?: boolean; notify?: boolean }) {
  return request<NexusAI.AlertRule>({ url: '/admin/ops/alert-rules', method: 'post', data: body });
}

export function updateAlertRule(id: number, body: Partial<NexusAI.AlertRule> & { enabled?: boolean; notify?: boolean }) {
  return request<NexusAI.AlertRule>({ url: `/admin/ops/alert-rules/${id}`, method: 'put', data: body });
}

export function deleteAlertRule(id: number) {
  return request({ url: `/admin/ops/alert-rules/${id}`, method: 'delete' });
}

/** 规则测试（dry-run·现算指标值与是否违反） */
export function testAlertRule(body: { metricType: string; operator: string; threshold: number; windowMinutes: number }) {
  return request<NexusAI.AlertRuleTestResult>({ url: '/admin/ops/alert-rules/test', method: 'post', data: body });
}

/** 告警事件 */
export function fetchAlertEvents(params: {
  status?: string;
  severity?: string;
  hours?: number;
  page?: number;
  pageSize?: number;
}) {
  return request<{ items: NexusAI.AlertEvent[]; total: number }>({ url: '/admin/ops/alert-events', params });
}

export function updateAlertEventStatus(id: number, status: string) {
  return request({ url: `/admin/ops/alert-events/${id}/status`, method: 'put', data: { status } });
}
