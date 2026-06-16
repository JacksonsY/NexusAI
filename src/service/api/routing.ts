import { request } from '../request';

// ===== 路由索引 Abilities（两级调度第一级·nai_ability 复合键 upsert/delete）=====
export function fetchAbilities(params?: { model?: string; groupCode?: string; channelId?: number }) {
  return request<NexusAI.Ability[]>({ url: '/admin/abilities', params });
}
export function fetchSaveAbility(data: NexusAI.Ability) {
  return request<void>({ url: '/admin/abilities', method: 'post', data });
}
export function fetchDeleteAbility(params: { groupCode: string; model: string; channelId: number }) {
  return request<void>({ url: '/admin/abilities', method: 'delete', params });
}

/** 读调度配置（调度策略 + 优先级生效层）。 */
export function fetchSchedulingConfig() {
  return request<{ schedulingMode: string; priorityMode: string }>({ url: '/admin/routing/scheduling' });
}
/** 更新调度配置（写入即对选路生效）。 */
export function fetchUpdateScheduling(data: { schedulingMode?: string; priorityMode?: string }) {
  return request<void>({ url: '/admin/routing/scheduling', method: 'put', data });
}

// ===== 调度策略分组 RoutingGroups（调度策略页·routing_groups）=====
export function fetchRoutingGroups() {
  return request<NexusAI.RoutingGroup[]>({ url: '/admin/routing/groups' });
}
export function fetchCreateRoutingGroup(data: NexusAI.RoutingGroup) {
  return request<number | string>({ url: '/admin/routing/groups', method: 'post', data });
}
export function fetchUpdateRoutingGroup(id: number | string, data: NexusAI.RoutingGroup) {
  return request<void>({ url: `/admin/routing/groups/${id}`, method: 'put', data });
}
export function fetchDeleteRoutingGroup(id: number | string) {
  return request<void>({ url: `/admin/routing/groups/${id}`, method: 'delete' });
}
/** 设为系统默认（全局唯一·运行时选路依据）。 */
export function fetchSetDefaultRoutingGroup(id: number | string) {
  return request<void>({ url: `/admin/routing/groups/${id}/default`, method: 'put' });
}
