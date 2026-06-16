import { request } from '../request';

// ===== 项目 Projects（工作区 + 控制面集成边界·Project） =====
export function fetchProjects() {
  return request<NexusAI.Project[]>({ url: '/admin/projects' });
}
/** 新建项目：未带 token 则服务端生成·返回含一次性明文 token（前端自存·之后只可脱敏查看）。 */
export function fetchAddProject(data: NexusAI.Project) {
  return request<NexusAI.Project>({ url: '/admin/projects', method: 'post', data });
}
export function fetchUpdateProject(id: number | string, data: NexusAI.Project) {
  return request<void>({ url: `/admin/projects/${id}`, method: 'put', data });
}
/** 查看明文令牌（按需揭示·列表默认打码）。 */
export function fetchProjectToken(id: number | string) {
  return request<string>({ url: `/admin/projects/${id}/token` });
}
/** 轮转令牌：旧 token 立即失效·返回明文新 token。 */
export function fetchRotateProjectToken(id: number | string) {
  return request<string>({ url: `/admin/projects/${id}/rotate-token`, method: 'post' });
}
export function fetchDeleteProject(id: number | string) {
  return request<void>({ url: `/admin/projects/${id}`, method: 'delete' });
}

// ===== 项目额度池（契约③ QuotaOperation·项目制 2026-06-12） =====
/** 实时余额：externalWorkspaceId 空=项目池·非空=该项目内工作区钱包（Redis 运行时真值·unlimited 时 balance 为 null）。 */
export function fetchProjectBalance(projectCode: string, externalWorkspaceId?: string) {
  return request<NexusAI.QuotaBalance>({
    url: `/admin/quota/${projectCode}/balance`,
    params: externalWorkspaceId ? { externalWorkspaceId } : undefined
  });
}
/** 额度操作（契约③·externalWorkspaceId 空=项目池/非空=工作区钱包）·返回操作后对应粒度余额。 */
export function fetchQuotaOp(data: {
  projectCode: string;
  externalWorkspaceId?: string;
  op: 'add' | 'subtract';
  usd: number;
  requestId?: string;
}) {
  return request<number>({ url: '/admin/quota/op', method: 'post', data });
}
