<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'PerfTrendCharts' });

/** 输出 TPS 趋势 + 平均首字趋势（双 LineChart·按渠道多曲线）。 */
const props = defineProps<{
  timeline: NexusAI.PerfProviderTimelinePoint[];
  loading: boolean;
}>();

/** 多渠道曲线色板（PROVIDER_CHART_COLORS）。 */
const COLORS = ['#3b82f6', '#10b981', '#eab308', '#ef4444', '#8b5cf6', '#0ea5e9', '#f97316', '#14b8a6'];

const AXIS = {
  axisLabel: { fontSize: 11, color: '#8c8c8c' },
  axisLine: { lineStyle: { color: 'rgba(140,140,140,0.2)' } },
  axisTick: { show: false }
};
const SPLIT = { lineStyle: { color: 'rgba(140,140,140,0.2)', type: 'dashed' as const } };

function num(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

function tsLabel(ts: string) {
  const d = new Date(ts);
  const pad = (v: number) => String(v).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function baseOption(unitFormatter: string | ((v: number) => string)) {
  return {
    textStyle: { fontSize: 12 },
    tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 } },
    legend: { textStyle: { fontSize: 12, color: '#8c8c8c' }, itemWidth: 12, itemHeight: 8, icon: 'roundRect', top: 0 },
    grid: { left: 52, right: 16, top: 32, bottom: 24 },
    xAxis: { ...AXIS, type: 'category' as const, boundaryGap: false, data: [] as string[] },
    yAxis: { ...AXIS, type: 'value' as const, splitLine: SPLIT, axisLabel: { ...AXIS.axisLabel, formatter: unitFormatter } },
    series: [] as object[]
  };
}

const { domRef: tpsRef, updateOptions: updateTps } = useEcharts(() => baseOption('{value} tps'));
const { domRef: fbRef, updateOptions: updateFb } = useEcharts(() => baseOption((v: number) => (v >= 1000 ? `${(v / 1000).toFixed(1)}s` : `${v}ms`)));

/** timeline 行 → 按渠道多曲线（buildProviderPerformanceChartData：labels 全集 + byDate 缺位 null）。 */
function buildSeries(metric: 'avgOutputTps' | 'avgFirstByteMs') {
  const labels = Array.from(new Set(props.timeline.map(p => tsLabel(p.ts))));
  const channels = new Map<string, string>();
  props.timeline.forEach(p => channels.set(p.channelId, p.channelName));
  const series = Array.from(channels.entries()).map(([id, name], index) => {
    const byLabel = new Map(props.timeline.filter(p => p.channelId === id).map(p => [tsLabel(p.ts), num(p[metric])]));
    return {
      name,
      type: 'line' as const,
      smooth: true,
      connectNulls: false,
      showSymbol: false,
      lineStyle: { width: 2 },
      itemStyle: { color: COLORS[index % COLORS.length] },
      data: labels.map(label => byLabel.get(label) ?? null)
    };
  });
  return { labels, series };
}

function render() {
  const tps = buildSeries('avgOutputTps');
  updateTps(opts => {
    (opts.xAxis as { data: string[] }).data = tps.labels;
    (opts.legend as { data?: string[] }).data = tps.series.map(s => s.name);
    opts.series = tps.series;
    return opts;
  });
  const fb = buildSeries('avgFirstByteMs');
  updateFb(opts => {
    (opts.xAxis as { data: string[] }).data = fb.labels;
    (opts.legend as { data?: string[] }).data = fb.series.map(s => s.name);
    opts.series = fb.series;
    return opts;
  });
}

watch(
  () => props.timeline,
  () => render(),
  { deep: false }
);
</script>

<template>
  <div class="grid grid-cols-1 gap-16px lg:grid-cols-2">
    <NCard :bordered="true" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">输出 TPS 趋势</h3>
      <div v-if="loading" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      </div>
      <div v-show="!loading" ref="tpsRef" class="mt-8px h-260px w-full" />
    </NCard>
    <NCard :bordered="true" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">平均首字趋势</h3>
      <div v-if="loading" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      </div>
      <div v-show="!loading" ref="fbRef" class="mt-8px h-260px w-full" />
    </NCard>
  </div>
</template>
