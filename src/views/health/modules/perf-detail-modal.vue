<script setup lang="tsx">
import { computed, ref, watch } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NTag, useThemeVars } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import { fetchHealthSeries } from '@/service/api';
import { formatMs, formatPercent, successRateClass } from '../composables/use-health-format';
import HealthTimeline from './health-timeline.vue';
import MetricBox from './metric-box.vue';

defineOptions({ name: 'PerfDetailModal' });

/**
 * 性能详情弹窗（借鉴 new-api 模型广场 ModelDetailsPerformance）：
 * 3 汇总卡（TPS/平均延迟/成功率+故障时段）→ 分渠道/分模型性能表（TPS/TTFT/延迟/成功率 sparkline）
 * → TTFT 趋势曲线 → 可用率曲线。dim=model 看"该模型在各渠道的表现"，dim=channel 看"该渠道各模型的表现"。
 */
const props = defineProps<{
  dim: 'model' | 'channel';
  /** 钻取键：模型名或渠道 id 串 */
  seriesKey: string;
  /** 展示名（渠道名/模型名） */
  name: string;
}>();

const show = defineModel<boolean>('show', { required: true });

const theme = useThemeVars();
const loading = ref(false);
const lookbackHours = ref(24);
const data = ref<NexusAI.PerfSeriesResponse | null>(null);

const LOOKBACK_OPTIONS = [
  { label: '6 小时', value: 6 },
  { label: '12 小时', value: 12 },
  { label: '24 小时', value: 24 },
  { label: '48 小时', value: 48 }
];

function num(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

function formatTps(value?: number | null) {
  if (typeof value !== 'number' || Number.isNaN(value) || value <= 0) return '-';
  return `${value.toFixed(1)} tok/s`;
}

async function loadData() {
  if (!props.seriesKey) return;
  loading.value = true;
  try {
    const { data: res, error } = await fetchHealthSeries(props.dim, props.seriesKey, lookbackHours.value);
    if (!error) {
      data.value = res ?? null;
    }
  } finally {
    loading.value = false;
  }
}

/** 故障时段数（new-api incidents：成功率 < 100% 的桶计 1）。 */
const incidentCount = computed(
  () => (data.value?.series ?? []).filter(p => Number(p.requestCount) > 0 && (p.successRate ?? 1) < 1).length
);

/** 成功率卡色（new-api intent：≥99.9 绿 / ≥99 默认 / 其余黄）。 */
const rateIntentClass = computed(() => {
  const rate = (data.value?.summary.successRate ?? 1) * 100;
  if (rate >= 99.9) return 'text-green-600 dark:text-green-400';
  if (rate >= 99) return '';
  return 'text-amber-600 dark:text-amber-400';
});

const breakdownTitle = computed(() => (props.dim === 'model' ? '分渠道性能' : '分模型性能'));
const breakdownDesc = computed(() =>
  props.dim === 'model' ? '该模型在各渠道上的吞吐、首包、延迟与成功率' : '该渠道上各模型的吞吐、首包、延迟与成功率'
);

const columns = computed<DataTableColumns<NexusAI.PerfBreakdownRow>>(() => [
  {
    key: 'name',
    title: props.dim === 'model' ? '渠道' : '模型',
    minWidth: 160,
    render: row => (
      <div class="flex items-center gap-8px">
        <span class="font-medium">{row.name}</span>
        {row.protocol ? (
          <NTag size="small" class="font-mono">
            {row.protocol}
          </NTag>
        ) : null}
      </div>
    )
  },
  {
    key: 'avgTps',
    title: 'TPS',
    width: 110,
    align: 'right',
    render: row => <span class="tabular-nums font-mono">{formatTps(row.avgTps)}</span>
  },
  {
    key: 'avgTtftMs',
    title: '平均 TTFT',
    width: 110,
    align: 'right',
    render: row => <span class="tabular-nums font-mono">{formatMs(row.avgTtftMs)}</span>
  },
  {
    key: 'avgLatencyMs',
    title: '平均延迟',
    width: 110,
    align: 'right',
    render: row => <span class="tabular-nums font-mono op-70">{formatMs(row.avgLatencyMs)}</span>
  },
  {
    key: 'successRate',
    title: '成功率',
    minWidth: 200,
    render: row => (
      <div class="flex items-center gap-12px">
        <span class={`w-56px shrink-0 text-right tabular-nums font-mono ${successRateClass(row.successRate)}`}>
          {formatPercent(row.successRate)}
        </span>
        <HealthTimeline
          class="min-w-0 flex-1"
          timeline={row.timeline}
          timeRangeStart={row.timeRangeStart}
          timeRangeEnd={row.timeRangeEnd}
          height={12}
          hideLabels
        />
      </div>
    )
  }
]);

function tsLabel(ts: string) {
  const d = new Date(ts);
  const pad = (v: number) => String(v).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 图表轴基础样式（观感对齐 dashboard chart-base：中灰 12px 标签 + 淡虚线网格）。 */
const AXIS = {
  axisLabel: { fontSize: 11, color: '#8c8c8c' },
  axisLine: { lineStyle: { color: 'rgba(140,140,140,0.2)' } },
  axisTick: { show: false }
};
const SPLIT = { lineStyle: { color: 'rgba(140,140,140,0.2)', type: 'dashed' as const } };

const { domRef: ttftChartRef, updateOptions: updateTtftChart } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis', textStyle: { fontSize: 12 } },
  grid: { left: 48, right: 16, top: 16, bottom: 24 },
  xAxis: { ...AXIS, type: 'category', boundaryGap: false, data: [] as string[] },
  yAxis: { ...AXIS, type: 'value', splitLine: SPLIT, axisLabel: { ...AXIS.axisLabel, formatter: '{value} ms' } },
  series: [] as object[]
}));

const { domRef: uptimeChartRef, updateOptions: updateUptimeChart } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis', textStyle: { fontSize: 12 }, valueFormatter: (v: unknown) => (v == null ? '-' : `${v}%`) },
  grid: { left: 48, right: 16, top: 16, bottom: 24 },
  xAxis: { ...AXIS, type: 'category', boundaryGap: false, data: [] as string[] },
  yAxis: { ...AXIS, type: 'value', max: 100, splitLine: SPLIT, axisLabel: { ...AXIS.axisLabel, formatter: '{value}%' } },
  series: [] as object[]
}));

function renderCharts() {
  const points = data.value?.series ?? [];
  const labels = points.map(p => tsLabel(p.ts));
  const ttft = points.map(p => num(p.avgTtftMs));
  const rates = points.map(p => (Number(p.requestCount) > 0 && p.successRate != null ? Math.round(p.successRate * 10000) / 100 : null));
  const primary = theme.value.primaryColor;
  const green = '#18a058';

  updateTtftChart(opts => {
    (opts.xAxis as { data: string[] }).data = labels;
    opts.series = [
      {
        name: '平均 TTFT',
        type: 'line',
        smooth: true,
        connectNulls: false,
        showSymbol: false,
        data: ttft,
        lineStyle: { width: 2 },
        itemStyle: { color: primary },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: `${primary}33` },
              { offset: 1, color: `${primary}05` }
            ]
          }
        }
      }
    ];
    return opts;
  });

  const validRates = rates.filter((v): v is number => v != null);
  const yMin = validRates.length ? Math.min(80, Math.floor(Math.min(...validRates) / 5) * 5) : 80;
  updateUptimeChart(opts => {
    (opts.xAxis as { data: string[] }).data = labels;
    (opts.yAxis as { min?: number }).min = yMin;
    opts.series = [
      {
        name: '成功率',
        type: 'line',
        smooth: true,
        connectNulls: false,
        showSymbol: false,
        data: rates,
        lineStyle: { width: 2 },
        itemStyle: { color: green },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: `${green}2e` },
              { offset: 1, color: `${green}05` }
            ]
          }
        }
      }
    ];
    return opts;
  });
}

watch([show, () => props.seriesKey], ([visible]) => {
  if (visible) {
    loadData();
  } else {
    data.value = null;
  }
});
watch(lookbackHours, () => {
  if (show.value) loadData();
});
watch(data, () => {
  if (data.value) renderCharts();
});
</script>

<template>
  <NModal v-model:show="show" preset="card" class="w-920px" :title="`性能详情 · ${name}`" :bordered="false">
    <template #header-extra>
      <NSelect v-model:value="lookbackHours" :options="LOOKBACK_OPTIONS" size="small" class="w-112px" aria-label="回溯时间" />
    </template>

    <NSpin :show="loading">
      <div v-if="!data || Number(data.summary.totalAttempts) === 0" class="flex flex-col items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:line-chart" class="mb-12px text-48px op-30" />
        <p class="m-0">该时间范围内暂无性能数据</p>
      </div>

      <div v-else class="flex-col gap-20px">
        <!-- 汇总三卡（new-api StatCard：TPS / 平均延迟 / 成功率+故障时段） -->
        <div class="grid grid-cols-1 gap-8px sm:grid-cols-3">
          <MetricBox label="TPS" icon="lucide:timer" :value="formatTps(data.summary.avgTps)" hint="持续每秒输出 token" />
          <MetricBox label="平均延迟" icon="lucide:gauge" :value="formatMs(data.summary.avgLatencyMs)" :hint="`平均 TTFT ${formatMs(data.summary.avgTtftMs)}`" />
          <MetricBox
            label="成功率"
            icon="lucide:heart-pulse"
            :value="formatPercent(data.summary.successRate)"
            :value-class="rateIntentClass"
            :hint="incidentCount > 0 ? `近窗 ${incidentCount} 个故障时段` : '近窗无故障时段'"
          />
        </div>

        <!-- 分渠道 / 分模型性能表 -->
        <section>
          <div class="mb-8px flex items-center gap-8px">
            <SvgIcon icon="lucide:heart-pulse" class="text-14px op-60" />
            <div>
              <div class="text-13px font-semibold">{{ breakdownTitle }}</div>
              <p class="m-0 text-12px op-60">{{ breakdownDesc }}</p>
            </div>
          </div>
          <NDataTable :columns="columns" :data="data.breakdown" :bordered="true" size="small" :max-height="280" />
        </section>

        <!-- TTFT 趋势 -->
        <section>
          <div class="mb-8px flex items-center gap-8px">
            <SvgIcon icon="lucide:timer" class="text-14px op-60" />
            <div>
              <div class="text-13px font-semibold">延迟趋势</div>
              <p class="m-0 text-12px op-60">平均首包时间（TTFT）逐时段曲线</p>
            </div>
          </div>
          <div ref="ttftChartRef" class="h-200px w-full" />
        </section>

        <!-- 可用率 -->
        <section>
          <div class="mb-8px flex items-center gap-8px">
            <SvgIcon icon="lucide:activity" class="text-14px op-60" />
            <div>
              <div class="text-13px font-semibold">可用率</div>
              <p class="m-0 text-12px op-60">
                请求成功率逐时段曲线{{ incidentCount > 0 ? `，近窗 ${incidentCount} 个故障时段` : '' }}
              </p>
            </div>
          </div>
          <div ref="uptimeChartRef" class="h-180px w-full" />
        </section>
      </div>
    </NSpin>
  </NModal>
</template>
