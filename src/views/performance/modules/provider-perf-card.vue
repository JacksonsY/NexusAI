<script setup lang="tsx">
import { computed } from 'vue';
import type { DataTableColumns } from 'naive-ui';

defineOptions({ name: 'ProviderPerfCard' });

/** 渠道性能聚合卡（一比一 Aether 上游服务性能：筛选 + 8 汇总卡 + 分位表格）。 */
const props = defineProps<{
  data: NexusAI.PerfProvidersResponse | null;
  loading: boolean;
  channelOptions: { label: string; value: number }[];
  slowThresholdMs: number;
}>();

const channelId = defineModel<number | null>('channelId', { default: null });
const model = defineModel<string>('model', { default: '' });
const modality = defineModel<string | null>('modality', { default: null });
const stream = defineModel<'all' | 'true' | 'false'>('stream', { default: 'all' });
const slowMs = defineModel<number | null>('slowMs', { default: 10000 });

const emit = defineEmits<{ reset: [] }>();

const MODALITY_OPTIONS = [
  { label: '全部能力', value: '' },
  { label: 'chat', value: 'chat' },
  { label: 'embedding', value: 'embedding' },
  { label: 'image', value: 'image' },
  { label: 'rerank', value: 'rerank' }
];
const STREAM_OPTIONS = [
  { label: '全部流式', value: 'all' },
  { label: '仅流式', value: 'true' },
  { label: '非流式', value: 'false' }
];

function num(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

function fmtCount(value?: number | string | null) {
  const n = num(value);
  return n === null ? '-' : new Intl.NumberFormat('zh-CN').format(n);
}

/** 毫秒（≥1s 转秒·一比一 Aether formatDurationMs）。 */
function fmtMs(value?: number | string | null, decimals = 0) {
  const n = num(value);
  if (n === null) return '-';
  if (Math.abs(n) >= 1000) return `${(n / 1000).toFixed(2)}s`;
  return `${n.toFixed(decimals)}ms`;
}

function fmtPct(value?: number | null) {
  if (value === null || value === undefined || Number.isNaN(value)) return '-';
  return `${value.toFixed(2)}%`;
}

function fmtTps(value?: number | null) {
  if (value === null || value === undefined || Number.isNaN(value)) return '-';
  return `${value.toFixed(2)} tps`;
}

function fmtErrorRate(successRate?: number | null) {
  if (successRate === null || successRate === undefined || Number.isNaN(successRate)) return '-';
  return `${Math.max(0, 100 - successRate).toFixed(2)}%`;
}

const hasFilters = computed(
  () => channelId.value !== null || model.value.trim() !== '' || Boolean(modality.value) || stream.value !== 'all' || (slowMs.value ?? 10000) !== 10000
);

const subtitle = computed(() => {
  const requests = fmtCount(props.data?.summary.requestCount);
  return `完成窗口内 ${requests} 个渠道请求样本`;
});

const slowLabel = computed(() => {
  const value = props.slowThresholdMs;
  return value >= 1000 ? `${value % 1000 === 0 ? value / 1000 : (value / 1000).toFixed(1)}s` : `${value}ms`;
});

/** 8 汇总卡（一比一 Aether providerPerformanceSummaryCards）。 */
const summaryCards = computed(() => {
  const s = props.data?.summary;
  return [
    { title: '请求样本', value: fmtCount(s?.requestCount), hint: `${props.data?.providers.length ?? 0} 个渠道`, icon: 'lucide:activity', iconClass: 'text-blue-500' },
    { title: '成功率', value: fmtPct(s?.successRate), hint: `错误率 ${fmtErrorRate(s?.successRate)}`, icon: 'lucide:circle-check', iconClass: 'text-emerald-500' },
    { title: 'P99 响应', value: fmtMs(s?.p99ResponseMs), hint: `P90 ${fmtMs(s?.p90ResponseMs)}`, icon: 'lucide:gauge', iconClass: 'text-violet-500' },
    { title: 'P99 首字', value: fmtMs(s?.p99FirstByteMs), hint: `P90 ${fmtMs(s?.p90FirstByteMs)}`, icon: 'lucide:timer', iconClass: 'text-sky-500' },
    { title: '输出 TPS', value: fmtTps(s?.avgOutputTps), hint: `成功样本吞吐`, icon: 'lucide:zap', iconClass: 'text-amber-500' },
    { title: '平均首字', value: fmtMs(s?.avgFirstByteMs, 2), hint: `首字样本 ${fmtCount(s?.firstByteSamples)}`, icon: 'lucide:timer', iconClass: 'text-sky-500' },
    { title: '平均响应', value: fmtMs(s?.avgResponseMs, 2), hint: `响应样本 ${fmtCount(s?.responseSamples)}`, icon: 'lucide:gauge', iconClass: 'text-violet-500' },
    { title: '慢请求', value: fmtCount(s?.slowRequestCount), hint: `响应耗时 ≥ ${slowLabel.value}`, icon: 'lucide:triangle-alert', iconClass: 'text-yellow-500' }
  ];
});

/** 样本覆盖（一比一 Aether providerSampleCoverageText：响应% / 首字%）。 */
function coverageText(row: NexusAI.PerfProviderRow) {
  const total = num(row.requestCount) ?? 0;
  if (!total) return '-';
  const resp = Math.round(((num(row.responseSamples) ?? 0) / total) * 100);
  const fb = Math.round(((num(row.firstByteSamples) ?? 0) / total) * 100);
  return `${resp}% / ${fb}%`;
}

const columns = computed<DataTableColumns<NexusAI.PerfProviderRow>>(() => [
  {
    key: 'channelName',
    title: '渠道',
    minWidth: 150,
    render: row => (
      <div class="min-w-0">
        <div class="truncate text-13px font-medium">{row.channelName}</div>
        <div class="truncate text-12px op-60">#{row.channelId}</div>
      </div>
    )
  },
  { key: 'requestCount', title: '请求', align: 'right', width: 80, render: row => <span class="tabular-nums">{fmtCount(row.requestCount)}</span> },
  { key: 'successRate', title: '成功率', align: 'right', width: 90, render: row => <span class="tabular-nums">{fmtPct(row.successRate)}</span> },
  { key: 'errorRate', title: '错误率', align: 'right', width: 90, render: row => <span class="tabular-nums">{fmtErrorRate(row.successRate)}</span> },
  { key: 'avgOutputTps', title: '输出 TPS', align: 'right', width: 100, render: row => <span class="tabular-nums">{fmtTps(row.avgOutputTps)}</span> },
  { key: 'avgFirstByteMs', title: '平均首字', align: 'right', width: 96, render: row => <span class="tabular-nums">{fmtMs(row.avgFirstByteMs, 2)}</span> },
  { key: 'avgResponseMs', title: '平均响应', align: 'right', width: 96, render: row => <span class="tabular-nums">{fmtMs(row.avgResponseMs, 2)}</span> },
  {
    key: 'p9099Resp',
    title: 'P90/P99 响应',
    align: 'right',
    width: 130,
    render: row => <span class="tabular-nums">{`${fmtMs(row.p90ResponseMs)} / ${fmtMs(row.p99ResponseMs)}`}</span>
  },
  {
    key: 'p9099Fb',
    title: 'P90/P99 首字',
    align: 'right',
    width: 130,
    render: row => <span class="tabular-nums">{`${fmtMs(row.p90FirstByteMs)} / ${fmtMs(row.p99FirstByteMs)}`}</span>
  },
  { key: 'slowRequestCount', title: '慢请求', align: 'right', width: 80, render: row => <span class="tabular-nums">{fmtCount(row.slowRequestCount)}</span> },
  { key: 'coverage', title: '样本覆盖', align: 'right', width: 100, render: row => <span class="text-12px op-60 tabular-nums">{coverageText(row)}</span> }
]);
</script>

<template>
  <NCard :bordered="true" class="card-wrapper">
    <div class="flex flex-col gap-8px sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h3 class="m-0 text-14px font-semibold">渠道性能</h3>
        <p class="m-0 mt-2px text-12px op-60">{{ subtitle }}</p>
      </div>
      <div class="flex items-center gap-8px">
        <NTag size="small">Top {{ data?.providers.length ?? 0 }}</NTag>
        <NButton v-if="hasFilters" size="small" quaternary @click="emit('reset')">
          <template #icon>
            <SvgIcon icon="lucide:filter-x" />
          </template>
          清除
        </NButton>
      </div>
    </div>

    <!-- 筛选行（渠道/模型/能力/流式/慢阈值·一比一 Aether 筛选区·裁掉日志无列的 API 格式/格式转换） -->
    <div class="grid grid-cols-1 mt-12px gap-8px sm:grid-cols-2 2xl:grid-cols-5 xl:grid-cols-3">
      <NSelect v-model:value="channelId" :options="channelOptions" placeholder="全部渠道" size="small" clearable aria-label="渠道筛选" />
      <NInput v-model:value="model" placeholder="模型" size="small" clearable :spellcheck="false" aria-label="模型筛选" />
      <NSelect v-model:value="modality" :options="MODALITY_OPTIONS" placeholder="能力" size="small" aria-label="能力筛选" />
      <NSelect v-model:value="stream" :options="STREAM_OPTIONS" size="small" aria-label="流式筛选" />
      <NInputNumber v-model:value="slowMs" :min="1" :max="600000" size="small" placeholder="慢请求阈值 ms" aria-label="慢请求阈值" />
    </div>

    <div v-if="loading" class="flex items-center justify-center py-32px op-60">
      <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      <span class="ml-8px">加载中...</span>
    </div>

    <div v-else class="mt-16px flex-col gap-16px">
      <div class="grid grid-cols-1 gap-12px sm:grid-cols-2 xl:grid-cols-4">
        <div v-for="card in summaryCards" :key="card.title" class="border border-gray-500/20 rounded-12px px-16px py-12px">
          <div class="flex items-center justify-between gap-12px">
            <span class="text-12px op-60">{{ card.title }}</span>
            <SvgIcon :icon="card.icon" class="text-16px" :class="card.iconClass" />
          </div>
          <div class="mt-10px text-22px font-semibold tracking-tight tabular-nums">{{ card.value }}</div>
          <div class="mt-6px truncate text-12px op-60">{{ card.hint }}</div>
        </div>
      </div>

      <NDataTable
        v-if="data?.providers.length"
        :columns="columns"
        :data="data.providers"
        :bordered="true"
        size="small"
        :scroll-x="1180"
      />
      <div v-else class="border border-gray-500/30 rounded-10px border-dashed px-12px py-16px text-13px op-60">
        当前没有渠道性能数据。
      </div>
    </div>
  </NCard>
</template>
