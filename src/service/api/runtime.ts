import { request } from '../request';

// ===== 运行时快照 Runtime（运维监控页并发卡数据源） =====
export function fetchRuntimeKeys(channelId?: number) {
  return request<NexusAI.RuntimeKey[]>({ url: '/admin/runtime/keys', params: { channelId } });
}
export function fetchRuntimeChannels() {
  return request<NexusAI.RuntimeChannel[]>({ url: '/admin/runtime/channels' });
}
