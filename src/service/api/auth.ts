import { request } from '../request';

/** 账号密码登录（Sa-Token 会话）。 */
export function fetchAdminLogin(username: string, password: string) {
  return request<{ token: string; username: string }>({
    url: '/admin/login',
    method: 'post',
    data: { username, password }
  });
}
export function fetchAdminLogout() {
  return request<void>({ url: '/admin/logout', method: 'post' });
}
