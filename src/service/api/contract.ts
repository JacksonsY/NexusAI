import { request } from '../request';

// ===== 契约① Key 同步（key-sync 运维页用·ruoyi→网关灌同步 Key·PUT upsert / POST 吊销） =====
export function fetchSyncKey(data: NexusAI.KeySync) {
  return request<number>({ url: '/admin/keys', method: 'put', data });
}
export function fetchRevokeKey(id: number) {
  return request<void>({ url: `/admin/keys/${id}/revoke`, method: 'post' });
}
