import { request } from '../request';

// ===== 模型目录 Models（Aether GlobalModel 形态：列表聚合/详情/批量/关联/链路） =====

/** 分页列表（每行 = model + 渠道计数 + 近 30 天调用次数）。 */
export function fetchModels(params?: { pageNum?: number; pageSize?: number; search?: string; status?: number }) {
  return request<NexusAI.Page<NexusAI.ModelRow>>({ url: '/admin/models', params });
}

/** 单模型详情（含统计）。 */
export function fetchModelDetail(id: number | string) {
  return request<NexusAI.ModelRow>({ url: `/admin/models/${id}` });
}

/** 缺失模型检测（new-api GetMissingModels）：路由已登记但目录未建 → 计费口径缺口。 */
export function fetchMissingModels() {
  return request<string[]>({ url: '/admin/models/missing' });
}

/** models.dev 目录（创建对话框左侧目录树·后端代理+1h 缓存）。 */
export function fetchModelsDevCatalog() {
  return request<NexusAI.ModelsDevCatalog>({ url: '/admin/models/external' });
}

export function fetchAddModel(data: NexusAI.Model) {
  return request<number>({ url: '/admin/models', method: 'post', data });
}
export function fetchUpdateModel(id: number | string, data: NexusAI.Model) {
  return request<void>({ url: `/admin/models/${id}`, method: 'put', data });
}
export function fetchDeleteModel(id: number | string) {
  return request<void>({ url: `/admin/models/${id}`, method: 'delete' });
}

/** 批量删除（批量管理对话框）。 */
export function fetchBatchDeleteModels(ids: Array<number | string>) {
  return request<number>({ url: '/admin/models/batch-delete', method: 'post', data: { ids } });
}

/** 模型的关联渠道列表（详情抽屉）。 */
export function fetchModelChannels(modelKey: string) {
  return request<NexusAI.ModelChannelLink[]>({
    url: `/admin/models/${encodeURIComponent(modelKey)}/channels`
  });
}

/** 渠道关联声明式对账（选中集合即目标态·缺补多删）。 */
export function fetchAssignModelChannels(modelKey: string, channelIds: Array<number | string>) {
  return request<{ added: number; removed: number }>({
    url: `/admin/models/${encodeURIComponent(modelKey)}/channels`,
    method: 'put',
    data: { channelIds }
  });
}

/** 同步预览（new-api SyncUpstreamPreview）：缺失模型 + 字段冲突 diff。 */
export function fetchModelSyncPreview() {
  return request<NexusAI.ModelSyncPreview>({ url: '/admin/models/sync/preview' });
}

/** 执行同步：勾选建缺失模型 + 勾选字段覆盖。 */
export function fetchModelSync(data: { createKeys: string[]; overwrite: Record<string, string[]> }) {
  return request<{ created: number; updated: number; skipped: string[] }>({
    url: '/admin/models/sync',
    method: 'post',
    data
  });
}

/** 链路预览（详情抽屉「链路控制」Tab·两级调度真实顺序）。 */
export function fetchModelRouting(modelKey: string) {
  return request<NexusAI.ModelRouting>({
    url: `/admin/models/${encodeURIComponent(modelKey)}/routing`
  });
}
