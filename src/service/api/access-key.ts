import { request } from '../request';

// ===== 独立密钥（standalone Access Key·控制台签发·/admin/access-keys·一比一 Aether AccessKeys） =====

/** 独立密钥分页列表（standalone 由后端过滤·true=仅独立 Key） */
export function fetchAccessKeys(params: {
  pageNum?: number;
  pageSize?: number;
  projectCode?: string;
  standalone?: boolean;
}) {
  return request<NexusAI.Page<NexusAI.AccessKey>>({ url: '/admin/access-keys', params });
}

/** 签发独立密钥（明文只返回一次） */
export function fetchCreateAccessKey(data: NexusAI.AccessKeyCreateRequest) {
  return request<NexusAI.AccessKeyCreateResponse>({ url: '/admin/access-keys', method: 'post', data });
}

/** 更新独立密钥元数据 */
export function fetchUpdateAccessKey(id: number | string, data: NexusAI.AccessKeyUpdateRequest) {
  return request<void>({ url: `/admin/access-keys/${id}`, method: 'put', data });
}

/** 轮转 keyHash（旧 Key 立即失效·返回新明文） */
export function fetchRotateAccessKey(id: number | string) {
  return request<NexusAI.AccessKeyRotateResponse>({ url: `/admin/access-keys/${id}/rotate`, method: 'post' });
}

/** 启停 */
export function fetchUpdateAccessKeyStatus(id: number | string, status: number) {
  return request<void>({ url: `/admin/access-keys/${id}/status`, method: 'put', params: { status } });
}

/** 删除 */
export function fetchDeleteAccessKey(id: number | string) {
  return request<void>({ url: `/admin/access-keys/${id}`, method: 'delete' });
}

/** 额度调整（调 Key 子限额 quotaUsd·USD·null=不设上限·仅受项目池约束） */
export function fetchAdjustAccessKeyQuota(id: number | string, quotaUsd: number | null) {
  return request<void>({ url: `/admin/access-keys/${id}/quota`, method: 'post', data: { quotaUsd } });
}

/** 查该 Key 所属项目池/工作区实时可用额度（额度抽屉只读展示） */
export function fetchAccessKeyBalance(id: number | string) {
  return request<NexusAI.QuotaBalance>({ url: `/admin/access-keys/${id}/balance` });
}
