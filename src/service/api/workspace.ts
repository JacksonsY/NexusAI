import { request } from '../request';

// ===== 项目内工作区 Workspace（三层身份链·B2C 终端工作区钱包·对接 /admin/workspaces） =====
export function fetchWorkspaces(params: {
  pageNum?: number;
  pageSize?: number;
  projectCode?: string;
  keyword?: string;
}) {
  return request<NexusAI.Page<NexusAI.Workspace>>({ url: '/admin/workspaces', params });
}
/** Upsert（幂等键 projectCode+externalId）：建/改 displayName/unlimited/status；钱包额度走契约③ fetchQuotaOp。 */
export function fetchUpsertWorkspace(data: NexusAI.Workspace) {
  return request<NexusAI.Workspace>({ url: '/admin/workspaces', method: 'post', data });
}
/** 停用/启用（停用即时生效·该工作区全部 Key 下一请求被拒）。 */
export function fetchWorkspaceStatus(id: number | string, status: number) {
  return request<void>({ url: `/admin/workspaces/${id}/status?status=${status}`, method: 'put' });
}
