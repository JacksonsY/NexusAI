import { request } from '../request';

// ===== 健康监控 Health（协议端点/模型/渠道三维度·一比一 Aether /admin/endpoints/health/*）=====

/** 协议端点健康监控：按渠道协议族聚合（成功率 + 100 段时间线 + 渠道/凭证数）。 */
export function fetchHealthProtocols(lookbackHours = 6) {
  return request<NexusAI.ProtocolHealthResponse>({
    url: '/admin/observe/health/protocols',
    params: { lookbackHours }
  });
}

/** 模型健康监控：按模型聚合（可用率/时延/TTFT + 60 段时间线），请求量 Top modelLimit。 */
export function fetchHealthModels(lookbackHours = 6, modelLimit = 12) {
  return request<NexusAI.ModelHealthResponse>({
    url: '/admin/observe/health/models',
    params: { lookbackHours, modelLimit }
  });
}

/** 渠道健康监控：按渠道聚合 + 下挂模型明细（故障排前），每渠道模型 Top modelLimit。 */
export function fetchHealthChannels(lookbackHours = 6, modelLimit = 12) {
  return request<NexusAI.ChannelHealthResponse>({
    url: '/admin/observe/health/channels',
    params: { lookbackHours, modelLimit }
  });
}

/** 性能详情钻取（借鉴 new-api perf_metrics）：逐桶 TPS/延迟/TTFT/成功率曲线 + 汇总 + 分渠道/分模型表。 */
export function fetchHealthSeries(dim: 'model' | 'channel', key: string, lookbackHours = 24) {
  return request<NexusAI.PerfSeriesResponse>({
    url: '/admin/observe/health/series',
    params: { dim, key, lookbackHours }
  });
}
