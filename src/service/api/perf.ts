import { request } from '../request';

// ===== 性能分析 Perf（实时运行状态/渠道性能/分位时序/错误分布·/admin/stats/performance + monitoring）=====

/** 实时运行状态（10s 轮询）：健康分/错误计数/并发指标/并发槽/集群节点/降级切换/最近错误/熔断与建议。 */
export function fetchPerfLive() {
  return request<NexusAI.PerfLive>({ url: '/admin/observe/perf/live' });
}

/** 渠道性能聚合（上游服务性能）：筛选 + summary + 每渠道行（P90/P99）+ 趋势曲线。 */
export function fetchPerfProviders(params: {
  channelId?: number | string | null;
  model?: string | null;
  modality?: string | null;
  stream?: boolean | null;
  lookbackHours?: number;
  slowThresholdMs?: number;
  limit?: number;
}) {
  const query: Record<string, unknown> = {};
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') query[key] = value;
  });
  return request<NexusAI.PerfProvidersResponse>({ url: '/admin/observe/perf/providers', params: query });
}

/** 按日延迟分位时序（响应 + 首字 P50/P90/P99）。 */
export function fetchPerfPercentiles(days = 7) {
  return request<NexusAI.PercentileDayRow[]>({ url: '/admin/observe/perf/percentiles', params: { days } });
}

/** 错误分类分布 + 按日错误趋势。 */
export function fetchPerfErrors(days = 7) {
  return request<NexusAI.PerfErrorsResponse>({ url: '/admin/observe/perf/errors', params: { days } });
}
