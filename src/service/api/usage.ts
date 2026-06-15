import { request } from '../request';

// ===== 用量 / 请求日志 Usage =====
export function fetchUsageLogs(params: { pageNum?: number; pageSize?: number; projectCode?: string; model?: string }) {
  return request<NexusAI.Page<NexusAI.UsageLog>>({ url: '/admin/usage/logs', params });
}
/** 使用记录筛选参数（多维·借鉴 Aether UsageFilters）。 */
export interface RequestLogFilters {
  /** 项目过滤（身份链=项目→Token→请求） */
  projectCode?: string;
  channelId?: number;
  model?: string;
  requestId?: string;
  modality?: string;
  streamMode?: boolean;
  success?: boolean;
  status?: number;
  /** 入站 API 格式过滤（一比一 Aether api_format） */
  apiFormat?: string;
  /** 客户端家族过滤（一比一 Aether client_family） */
  clientFamily?: string;
  /** 扩展状态过滤：cancelled / hasRetry / hasFallback（一比一 Aether status 扩展项） */
  statusFilter?: string;
  /** 通用搜索（request_id/trace_id/model/target_model/error_code 任一模糊命中） */
  search?: string;
  /** 隐藏 unknown 请求（排除 model 为空的行·一比一 Aether hide_unknown） */
  hideUnknown?: boolean;
  hours?: number;
  beginTime?: string;
  endTime?: string;
}

/** 请求日志（使用记录页·存储无关 SPI 分页·多维筛选）。 */
export function fetchRequestLogs(
  params: RequestLogFilters & { pageNum?: number; pageSize?: number; orderBy?: string; orderDesc?: boolean }
) {
  return request<NexusAI.Page<NexusAI.RequestLog>>({ url: '/admin/usage/requests', params });
}

/** 请求/响应正文（一比一 Aether include_bodies·仅 body.store=rdb 采集期间的请求有值）。 */
export function fetchRequestBody(requestId: string) {
  return request<{
    requestId: string;
    collecting: boolean;
    requestBody: string | null;
    responseBody: string | null;
    createDt: string | null;
  }>({ url: `/admin/usage/requests/${encodeURIComponent(requestId)}/body` });
}

/** cURL 重放命令（一比一 Aether /usage/{id}/curl·对上游·密钥占位脱敏）。 */
export function fetchRequestCurl(requestId: string) {
  return request<{
    curlCommand: string;
    url: string;
    headers: Record<string, string>;
    body: string | null;
    bodyCaptured: boolean;
  }>({ url: `/admin/usage/requests/${encodeURIComponent(requestId)}/curl` });
}

/** 用量维度聚合（用量分析面板·一比一 Aether aggregation/stats·groupBy=model|user|channel|apiFormat）。 */
export function fetchUsageAggregation(params: RequestLogFilters & { groupBy: string; limit?: number }) {
  return request<NexusAI.UsageAggItem[]>({ url: '/admin/usage/requests/aggregation', params });
}

/** 活跃度热图（一比一 Aether /usage/heatmap·固定 365 天·零请求日已补齐）。 */
export function fetchUsageHeatmap() {
  return request<NexusAI.UsageHeatmap>({ url: '/admin/usage/requests/heatmap' });
}

/** 请求间隔时间线（一比一 Aether interval-timeline·按用户分组散点·y=间隔分钟）。 */
export function fetchIntervalTimeline(params: { hours?: number; limit?: number }) {
  return request<NexusAI.IntervalTimeline>({ url: '/admin/usage/requests/interval-timeline', params });
}

/** 进行中请求（一比一 Aether /usage/active·进程内注册表·活跃行 1s 轮询）。 */
export function fetchActiveRequests() {
  return request<NexusAI.ActiveRequest[]>({ url: '/admin/usage/requests/active' });
}
