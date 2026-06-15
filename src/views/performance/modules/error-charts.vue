<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'ErrorCharts' });

/** 错误分布（环形）+ 错误趋势（红线）（一比一 Aether ErrorDistributionChart + 错误趋势 LineChart）。 */
const props = defineProps<{
  data: NexusAI.PerfErrorsResponse | null;
  loading: boolean;
}>();

const PIE_COLORS = ['#ef4444', '#f97316', '#eab308', '#8b5cf6', '#0ea5e9', '#14b8a6', '#ec4899', '#64748b'];

const AXIS = {
  axisLabel: { fontSize: 11, color: '#8c8c8c' },
  axisLine: { lineStyle: { color: 'rgba(140,140,140,0.2)' } },
  axisTick: { show: false }
};
const SPLIT = { lineStyle: { color: 'rgba(140,140,140,0.2)', type: 'dashed' as const } };

const { domRef: pieRef, updateOptions: updatePie } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'item' as const, textStyle: { fontSize: 12 } },
  legend: { textStyle: { fontSize: 12, color: '#8c8c8c' }, orient: 'vertical' as const, right: 8, top: 'center' },
  series: [] as object[]
}));

const { domRef: trendRef, updateOptions: updateTrend } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 } },
  grid: { left: 44, right: 16, top: 16, bottom: 24 },
  xAxis: { ...AXIS, type: 'category' as const, boundaryGap: false, data: [] as string[] },
  yAxis: { ...AXIS, type: 'value' as const, minInterval: 1, splitLine: SPLIT },
  series: [] as object[]
}));

function render() {
  const distribution = props.data?.distribution ?? [];
  updatePie(opts => {
    opts.series = [
      {
        name: '错误分布',
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderWidth: 2, borderColor: 'rgba(255,255,255,0.6)' },
        label: { show: false },
        data: distribution.map((item, index) => ({
          name: item.category,
          value: Number(item.count),
          itemStyle: { color: PIE_COLORS[index % PIE_COLORS.length] }
        }))
      }
    ];
    return opts;
  });

  const trend = props.data?.trend ?? [];
  updateTrend(opts => {
    (opts.xAxis as { data: string[] }).data = trend.map(t => t.date);
    opts.series = [
      {
        name: '错误数',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#ef4444' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#ef44442e' },
              { offset: 1, color: '#ef444405' }
            ]
          }
        },
        data: trend.map(t => Number(t.total))
      }
    ];
    return opts;
  });
}

watch(
  () => props.data,
  () => render(),
  { deep: false }
);
</script>

<template>
  <div class="grid grid-cols-1 gap-16px lg:grid-cols-2">
    <NCard :bordered="true" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">错误分布</h3>
      <p class="m-0 mt-2px text-12px op-60">按错误分类统计（error_code / HTTP 状态）</p>
      <div v-if="loading" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      </div>
      <div v-else-if="!data?.distribution.length" class="flex items-center justify-center py-48px text-13px op-60">
        窗口内没有错误记录。
      </div>
      <div v-show="!loading && data?.distribution.length" ref="pieRef" class="mt-8px h-260px w-full" />
    </NCard>
    <NCard :bordered="true" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">错误趋势</h3>
      <p class="m-0 mt-2px text-12px op-60">按日错误数</p>
      <div v-if="loading" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
      </div>
      <div v-show="!loading" ref="trendRef" class="mt-8px h-260px w-full" />
    </NCard>
  </div>
</template>
