<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'OpsTrendCharts' });

/** 吞吐趋势（QPS/TPS 双轴）+ 故障转移率趋势（sub2api 吞吐趋势 + 切换率趋势对位）。 */
const props = defineProps<{
  trend: NexusAI.OpsTrend | null;
  loading: boolean;
}>();

const AXIS = {
  axisLabel: { fontSize: 11, color: '#8c8c8c' },
  axisLine: { lineStyle: { color: 'rgba(140,140,140,0.2)' } },
  axisTick: { show: false }
};
const SPLIT = { lineStyle: { color: 'rgba(140,140,140,0.2)', type: 'dashed' as const } };

const { domRef: throughputRef, updateOptions: updateThroughput } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 } },
  legend: { textStyle: { fontSize: 12, color: '#8c8c8c' }, top: 0 },
  toolbox: { feature: { saveAsImage: { title: '下载' }, dataZoom: { yAxisIndex: 'none' as const } }, right: 8 },
  grid: { left: 48, right: 56, top: 32, bottom: 24 },
  xAxis: { ...AXIS, type: 'category' as const, boundaryGap: false, data: [] as string[] },
  yAxis: [
    { ...AXIS, type: 'value' as const, name: 'QPS', splitLine: SPLIT },
    { ...AXIS, type: 'value' as const, name: 'TPS', splitLine: { show: false } }
  ],
  series: [] as object[]
}));

const { domRef: failoverRef, updateOptions: updateFailover } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 } },
  grid: { left: 44, right: 16, top: 16, bottom: 24 },
  xAxis: { ...AXIS, type: 'category' as const, boundaryGap: false, data: [] as string[] },
  yAxis: { ...AXIS, type: 'value' as const, axisLabel: { ...AXIS.axisLabel, formatter: '{value}%' }, splitLine: SPLIT },
  series: [] as object[]
}));

function tsLabel(ts: string) {
  return ts.slice(11, 16);
}

function render() {
  const points = props.trend?.points ?? [];
  const labels = points.map(p => tsLabel(p.ts));
  updateThroughput(opts => {
    (opts.xAxis as { data: string[] }).data = labels;
    opts.series = [
      {
        name: 'QPS',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        lineStyle: { width: 2 },
        itemStyle: { color: '#2080f0' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#2080f02e' },
              { offset: 1, color: '#2080f005' }
            ]
          }
        },
        data: points.map(p => Number(p.qps))
      },
      {
        name: 'TPS',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        lineStyle: { width: 2 },
        itemStyle: { color: '#18a058' },
        data: points.map(p => Number(p.tps))
      }
    ];
    return opts;
  });
  updateFailover(opts => {
    (opts.xAxis as { data: string[] }).data = labels;
    opts.series = [
      {
        name: '故障转移率',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#f0a020' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#f0a0202e' },
              { offset: 1, color: '#f0a02005' }
            ]
          }
        },
        data: points.map(p => Number(p.failoverRate))
      }
    ];
    return opts;
  });
}

watch(
  () => props.trend,
  () => render(),
  { deep: false }
);
</script>

<template>
  <div class="grid grid-cols-1 gap-16px lg:grid-cols-4">
    <!-- 并发卡（sub2api 第一行 col-1 对位·由父级注入） -->
    <slot />
    <NCard :bordered="false" size="small" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">故障转移率</h3>
      <p class="m-0 mt-2px text-12px op-60">渠道/Key 切换占请求比例（尖峰=上游异常）</p>
      <div ref="failoverRef" class="mt-8px h-260px w-full" />
    </NCard>
    <NCard :bordered="false" size="small" class="card-wrapper lg:col-span-2">
      <h3 class="m-0 text-14px font-semibold">吞吐趋势</h3>
      <p class="m-0 mt-2px text-12px op-60">QPS（请求/秒·左轴）与 TPS（tokens/秒·右轴）</p>
      <div ref="throughputRef" class="mt-8px h-260px w-full" />
    </NCard>
  </div>
</template>

<style scoped></style>
