import { request } from '../request';

// ===== 异步任务 Tasks（全局视图·借鉴 Aether /api/admin/tasks）=====
export function fetchTasks(params: {
  pageNum?: number;
  pageSize?: number;
  status?: string;
  modality?: string;
  taskId?: string;
  model?: string;
  channelId?: number;
  beginTime?: string;
  endTime?: string;
}) {
  return request<NexusAI.Page<NexusAI.Task>>({ url: '/admin/tasks', params });
}

/** 状态聚合统计（顶部统计卡）。 */
export function fetchTaskStats() {
  return request<NexusAI.TaskStats>({ url: '/admin/tasks/stats' });
}

/** 任务详情（含 submitBody 原始请求体）。 */
export function fetchTaskDetail(taskId: string) {
  return request<NexusAI.Task>({ url: `/admin/tasks/${taskId}` });
}

/** 取消任务（仅非终态·后端幂等闸门自动退冻结 USD）。 */
export function fetchCancelTask(taskId: string) {
  return request<void>({ url: `/admin/tasks/${taskId}/cancel`, method: 'post' });
}

// ===== 幂等记录 Idempotents =====
export function fetchIdempotents(params: { pageNum?: number; pageSize?: number }) {
  return request<NexusAI.Page<NexusAI.Idempotent>>({ url: '/admin/idempotents', params });
}
