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

/**
 * 获取当前登录用户信息（GET /admin/userinfo）。
 * 借鉴 snail-ai-admin：重进/刷新时调用做一次服务端 Sa-Token 会话校验——
 * 令牌过期或被吊销时后端返回未授权，前端据此重置并踢回登录页。
 */
export function fetchAdminUserInfo() {
  return request<{ username: string; loginId: string | number | null }>({ url: '/admin/userinfo' });
}
