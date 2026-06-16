import { request } from '../request';

// ===== 渠道 Channels =====
export function fetchChannels() {
  return request<NexusAI.Channel[]>({ url: '/admin/channels' });
}
/** 渠道汇总（提供商页·providers summary）：配置 + 凭证/模型水位 + 近 24h 流量 + 运行时健康。 */
export function fetchChannelSummary() {
  return request<NexusAI.ChannelSummary[]>({ url: '/admin/channels/summary' });
}
export function fetchAddChannel(data: NexusAI.Channel) {
  return request<number>({ url: '/admin/channels', method: 'post', data });
}
export function fetchUpdateChannel(id: number, data: NexusAI.Channel) {
  return request<void>({ url: `/admin/channels/${id}`, method: 'put', data });
}
export function fetchDeleteChannel(id: number) {
  return request<void>({ url: `/admin/channels/${id}`, method: 'delete' });
}
/** 渠道测速探活（G3）：探上游连通性 + 鉴权 + 延迟。 */
export function fetchTestChannel(id: number) {
  return request<NexusAI.ChannelTestResult>({ url: `/admin/channels/${id}/test`, method: 'post' });
}
/** 批量更新渠道优先级（优先级管理对话框·拖拽排序一次保存）。 */
export function fetchUpdateChannelPriorities(items: { id: number; priority: number }[]) {
  return request<void>({ url: '/admin/channels/priorities', method: 'put', data: items });
}
/** 渠道余额监控（余额列·上游 OpenAI 兼容计费端点·后端缓存 5 分钟）。 */
export function fetchChannelBalance(id: number, force = false) {
  return request<NexusAI.ChannelBalance>({ url: `/admin/channels/${id}/balance`, params: { force } });
}

/** 批量启停（批量操作）。 */
export function fetchBatchChannelStatus(ids: number[], status: number) {
  return request<void>({ url: '/admin/channels/batch-status', method: 'put', data: { ids, status } });
}
/** 批量删除（连带凭证与路由索引）。 */
export function fetchBatchDeleteChannels(ids: number[]) {
  return request<number>({ url: '/admin/channels/batch-delete', method: 'post', data: { ids } });
}
/** 渠道复制（CopyChannel·新渠道默认停用）。 */
export function fetchCopyChannel(id: number) {
  return request<number>({ url: `/admin/channels/${id}/copy`, method: 'post' });
}
/** 从上游拉取模型列表（FetchUpstreamModels·与已注册对照）。 */
export function fetchChannelUpstreamModels(id: number) {
  return request<NexusAI.ChannelUpstreamModels>({ url: `/admin/channels/${id}/upstream-models` });
}
/** 批量注册勾选的上游模型为本渠道 ability（default 组·已存在跳过）。 */
export function fetchRegisterUpstreamModels(id: number, models: string[]) {
  return request<number>({ url: `/admin/channels/${id}/upstream-models/register`, method: 'post', data: { models } });
}

// ===== 渠道密钥 ChannelKeys =====
export function fetchChannelKeys(channelId?: number) {
  return request<NexusAI.ChannelKey[]>({ url: '/admin/channel-keys', params: { channelId } });
}
export function fetchAddChannelKey(data: NexusAI.ChannelKey) {
  return request<number>({ url: '/admin/channel-keys', method: 'post', data });
}
export function fetchUpdateChannelKey(id: number, data: NexusAI.ChannelKey) {
  return request<void>({ url: `/admin/channel-keys/${id}`, method: 'put', data });
}
export function fetchDeleteChannelKey(id: number) {
  return request<void>({ url: `/admin/channel-keys/${id}`, method: 'delete' });
}
