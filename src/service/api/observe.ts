import { request } from '../request';

// ===== 可观测增强 Observe（错误率/告警/归档/钻取/故障转移）=====
/** Dashboard 运维总览（去钱化·借鉴 axonhub）：累计量/环比计数/成功率环比/Token 用量/渠道成功率排行，一次拉齐。 */
export function fetchDashboard() {
  return request<NexusAI.DashboardStats>({ url: '/admin/observe/dashboard' });
}

/** Dashboard 维度时间窗（对齐 axonhub TimePeriodSelector：累计/本月/近7天/今日）。 */
export type DashWindow = 'allTime' | 'month' | 'week' | 'day';

/** Dashboard 维度分析（去钱化·借鉴 axonhub requests/tokens by channel·model·accessKey）：按请求量取 Top limit（含 token 三态）。 */
export function fetchDimensionStats(dim: 'channel' | 'model' | 'key', window: DashWindow = 'allTime', limit = 10) {
  return request<NexusAI.DimensionStat[]>({
    url: '/admin/observe/dashboard/dimension',
    params: { dim, window, limit }
  });
}

/** Dashboard 性能最快榜（一比一 axonhub fastestModels/fastestChannels）：窗口聚合吞吐 + 置信度排序 Top limit。 */
export function fetchPerformance(dim: 'model' | 'channel' = 'model', window: DashWindow = 'month', limit = 5) {
  return request<NexusAI.PerfStat[]>({
    url: '/admin/observe/dashboard/performance',
    params: { dim, window, limit }
  });
}

/** Dashboard 性能时序（一比一 axonhub model/channelPerformanceStats）：近 days 天逐日×维度吞吐/TTFT/请求量。 */
export function fetchPerformanceDaily(dim: 'model' | 'channel' = 'model', days = 30) {
  return request<NexusAI.PerfDailyPoint[]>({
    url: '/admin/observe/dashboard/performance/daily',
    params: { dim, days }
  });
}

/** Dashboard「每日请求概览」面积图（去钱化）：近 days 天按天聚合请求量 + Token。 */
export function fetchDailySeries(days = 14) {
  return request<NexusAI.DailyPoint[]>({ url: '/admin/observe/dashboard/daily', params: { days } });
}
export function fetchRequestDetail(requestId: string) {
  return request<{ request: NexusAI.RequestLog; usages: NexusAI.UsageLog[] }>({
    url: `/admin/observe/request/${encodeURIComponent(requestId)}`
  });
}
/** 故障转移/重试事件审计（H7）：传 requestId 串单请求失败链，否则近 hours 小时倒序最近 limit 条。 */
export function fetchFailovers(params?: { requestId?: string; hours?: number; limit?: number }) {
  return request<NexusAI.FailoverEvent[]>({ url: '/admin/observe/failovers', params });
}
