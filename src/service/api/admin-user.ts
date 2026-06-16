import { request } from '../request';

// ===== 控制台运维账号 AdminUsers（双身份·借鉴 snail-ai 用户管理·project=namespace 授权） =====

/** 运维账号列表（含被授权项目·不含口令）。 */
export function fetchAdminUsers() {
  return request<NexusAI.AdminUser[]>({ url: '/admin/admin-users' });
}

/** 新建运维账号（role 1 项目运维 / 2 超管；项目运维带 projects 授权）。 */
export function fetchAddAdminUser(data: NexusAI.AdminUserUpsert) {
  return request<number>({ url: '/admin/admin-users', method: 'post', data });
}

/** 改角色/状态/授权项目（不含口令）。 */
export function fetchUpdateAdminUser(id: number | string, data: NexusAI.AdminUserUpsert) {
  return request<void>({ url: `/admin/admin-users/${id}`, method: 'put', data });
}

/** 重置口令。 */
export function fetchResetAdminUserPassword(id: number | string, password: string) {
  return request<void>({ url: `/admin/admin-users/${id}/password`, method: 'put', data: { password } });
}

export function fetchDeleteAdminUser(id: number | string) {
  return request<void>({ url: `/admin/admin-users/${id}`, method: 'delete' });
}
