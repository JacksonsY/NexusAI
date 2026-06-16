<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'PercentileCharts' });

/** 响应延迟百分位 + 首字节延迟百分位（双 PercentileChart：按日 P50/P90/P99 三线）。 */
const props = defineProps<{
  rows: NexusAI.PercentileDayRow[];
  loading: boolean;
}>();

const AXIS = {
  axisLabel: { fontSize: 11, color: '#8c8c8c' },
  axisLine: { lineStyle: { color: 'rgba(140,140,140,0.2)' } },
  axisTick: { show: false }
};
const SPLIT = { lineStyle: { color: 'rgba(140,140,140,0.2)', type: 'dashed' as const } };
const P_COLORS = { p50: '#10b981', p90: '#eab308', p99: '#ef4444' };

function num(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

function baseOption() {
  return {
    textStyle: { fontSize: 12 },
    tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 }, valueFormatter: (v: unknown) => (v == null ? '-' : `${v} ms`) },
    legend: { textStyle: { fontSize: 12, color: '#8c8c8c' }, itemWidth: 12, itemHeight: 8, icon: 'roundRect', top: 0 },
    grid: { left: 56, right: 16, top: 32, bottom: 24 },
    xAxis: { ...AXIS, type: 'category' as const, boundaryGap: false, data: [] as string[] },
    yAxis: {
      ...AXIS,
      type: 'value' as const,
      splitLine: SPLIT,
      axisLabel: { ...AXIS.axisLabel, formatter: (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(1)}s` : `${v}ms`) }
    },
    series: [] as object[]
  };
}

const { domRef: respRef, updateOptions: updateResp } = useEcharts(baseOption);
const { domRef: ttfbRef, updateOptions: updateTtfb } = useEcharts(baseOption);

function line(name: string, color: string, data: (number | null)[]) {
  return { name, type: 'line' as const, smooth: true, connectNulls: false, showSymbol: false, lineStyle: { width: 2 }, itemStyle: { color }, data };
}

function render() {
  const labels = props.rows.map(r => r.date);
  updateResp(opts => {
    (opts.xAxis as { data: string[] }).data = labels;
    opts.series = [
      line('P50', P_COLORS.p50, props.rows.map(r => num(r.p50DurationMs))),
      line('P90', P_COLORS.p90, props.rows.map(r => num(r.p90DurationMs))),
      line('P99', P_COLORS.p99, props.rows.map(r => num(r.p99DurationMs)))
    ];
    return opts;
  });
  updateTtfb(opts => {
    (opts.xAxis as { data: string[] }).data = labels;
    opts.series = [
      line('P50', P_COLORS.p50, props.rows.map(r => num(r.p50TtftMs))),
      line('P90', P_COLORS.p90, props.rows.map(r => num(r.p90TtftMs))),
      line('P99', P_COLORS.p99, props.rows.map(r => num(r.p99TtftMs)))
    ];
    return opts;
  });
}

watch(
  () => props.rows,
  () => render(),
  { deep: false }
);
</script>

<template>
  <div class="grid grid-cols-1 gap-16px lg:grid-cols-2">
    <NCard :bordered="true" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">响应延迟百分位</h3>
      <p class="m-0 mt-2px text-12px op-60">按日 P50 / P90 / P99 响应耗时</p>
      <div v-if="loading" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      </div>
      <div v-show="!loading" ref="respRef" class="mt-8px h-260px w-full" />
    </NCard>
    <NCard :bordered="true" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">首字节延迟百分位</h3>
      <p class="m-0 mt-2px text-12px op-60">按日 P50 / P90 / P99 首包时间（仅流式样本）</p>
      <div v-if="loading" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      </div>
      <div v-show="!loading" ref="ttfbRef" class="mt-8px h-260px w-full" />
    </NCard>
  </div>
</template>
