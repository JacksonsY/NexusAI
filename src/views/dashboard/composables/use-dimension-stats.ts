import { ref, watch } from 'vue';
import type { DashWindow } from '@/service/api';
import { fetchDimensionStats } from '@/service/api';

type DimRow = NexusAI.DimensionStat & { name: string };

/** 维度某行 Token 合计（后端 totalTokens 缺省时由三态求和兜底）。 */
export function totalTokensOf(r: NexusAI.DimensionStat): number {
  return Number(r.totalTokens ?? Number(r.inputTokens ?? 0) + Number(r.outputTokens ?? 0) + Number(r.cachedTokens ?? 0));
}

/**
 * 维度分析数据编排（渠道/模型/上游凭证·去钱化·一比一 axonhub）。
 *
 * 与 axonhub `channelTimePeriod` ↔ `channelTokensTimePeriod` 双 state 对齐：
 * 请求量卡与 Token 卡**各持独立时间窗、各自重拉、各自排序**（请求量按 total 降序 / Token 按 totalTokens 降序），
 * 杜绝两卡共用一个窗导致的「改一个串台另一个」，以及 Token 卡沿用请求量排序导致的「序号货不对板」。
 * 组件只消费 rows，不在模板拉数；rows 注入展示名（前端用已拉 channels/keys 列表映射·存储层不查名解耦）。
 */
export function useDimensionStats(dim: 'channel' | 'model' | 'key', resolveName: (key?: string) => string) {
  const reqWindow = ref<DashWindow>('allTime');
  const tokWindow = ref<DashWindow>('allTime');
  const reqRows = ref<DimRow[]>([]);
  const tokRows = ref<DimRow[]>([]);

  async function loadReq() {
    const { data } = await fetchDimensionStats(dim, reqWindow.value, 10);
    reqRows.value = (data ?? [])
      .map(r => ({ ...r, name: resolveName(r.key) }))
      .sort((a, b) => Number(b.total ?? 0) - Number(a.total ?? 0))
      .slice(0, 10);
  }

  async function loadTok() {
    const { data } = await fetchDimensionStats(dim, tokWindow.value, 10);
    tokRows.value = (data ?? [])
      .map(r => ({ ...r, name: resolveName(r.key) }))
      .sort((a, b) => totalTokensOf(b) - totalTokensOf(a))
      .slice(0, 10);
  }

  // 各自时间窗切换即重拉（首次 load 由组件 onMounted 触发）
  watch(reqWindow, loadReq);
  watch(tokWindow, loadTok);

  function load() {
    return Promise.all([loadReq(), loadTok()]);
  }

  return { reqWindow, tokWindow, reqRows, tokRows, load };
}
