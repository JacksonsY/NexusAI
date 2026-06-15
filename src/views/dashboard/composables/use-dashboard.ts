import { computed, ref } from 'vue';
import { fetchChannelKeys, fetchChannels, fetchDashboard } from '@/service/api';

/** 时延人话格式（照抄 axonhub formatDuration）：<1s 整毫秒、<1min 一位小数秒、否则分钟。 */
export function formatDuration(ms?: number): string {
  const v = ms ?? 0;
  if (!Number.isFinite(v) || v <= 0) return '0ms';
  if (v < 1) return `${v.toFixed(3)}ms`;
  if (v < 1000) return `${v.toFixed(0)}ms`;
  if (v < 60000) return `${(v / 1000).toFixed(1)}s`;
  return `${(v / 60000).toFixed(1)}m`;
}

/** 大数缩写（一比一 axonhub formatNumber）：≥1000 即 K/M/B，两位小数去尾零（1950→1.95K、2000→2K），小于千原样。 */
export function formatCompact(n?: number): string {
  const v = n ?? 0;
  const abs = Math.abs(v);
  const suffix = (divisor: number, s: string) => {
    const raw = (v / divisor).toFixed(2);
    // 去尾零（axonhub trim：2.00→2、1.50→1.5）
    const trimmed = raw.replace(/\.?0+$/, '');
    return `${trimmed}${s}`;
  };
  if (abs >= 1e9) return suffix(1e9, 'B');
  if (abs >= 1e6) return suffix(1e6, 'M');
  if (abs >= 1e3) return suffix(1e3, 'K');
  return v.toLocaleString();
}

/**
 * Dashboard 数据编排（去钱化运维总览）：一次并发拉齐 dashboard 聚合 + 渠道/凭证列表（维度展示名映射），
 * 并派生请求量环比 / 成功率环比（百分点）。组件只消费派生值，不在模板算账（vue-best-practices）。
 */
export function useDashboard() {
  const loading = ref(false);
  const online = ref<boolean | null>(null);
  const dash = ref<NexusAI.DashboardStats>({});
  // 渠道/凭证列表供维度分析做 key→名称映射（避免二次查库）
  const channels = ref<NexusAI.Channel[]>([]);
  const channelKeys = ref<NexusAI.ChannelKey[]>([]);

  async function load() {
    loading.value = true;
    const [db, ch, ky] = await Promise.all([fetchDashboard(), fetchChannels(), fetchChannelKeys()]);
    online.value = !db.error;
    dash.value = db.data ?? {};
    channels.value = ch.data ?? [];
    channelKeys.value = ky.data ?? [];
    loading.value = false;
  }

  /** 请求量环比（近7天 vs 前7天·百分比）。 */
  const requestGrowth = computed(() => {
    const cur = dash.value.requestStats?.requestsThisWeek ?? 0;
    const prev = dash.value.requestStats?.requestsLastWeek ?? 0;
    if (prev === 0) return cur > 0 ? 100 : 0;
    return ((cur - prev) / prev) * 100;
  });

  /** 渠道 key（channelId 串）→ 渠道名（维度分析展示名映射）。 */
  const resolveChannelName = (k?: string) => {
    const c = channels.value.find(x => String(x.id) === String(k));
    return c?.name ?? `#${k ?? '—'}`;
  };
  /** 上游凭证 key（keyId 串）→ 凭证名/别名（维度分析展示名映射）。 */
  const resolveKeyName = (k?: string) => {
    const key = channelKeys.value.find(x => String(x.id) === String(k)) as Record<string, unknown> | undefined;
    return (key?.name as string) ?? (key?.alias as string) ?? (key?.label as string) ?? `#${k ?? '—'}`;
  };

  return {
    loading,
    online,
    dash,
    channels,
    channelKeys,
    load,
    requestGrowth,
    resolveChannelName,
    resolveKeyName
  };
}
