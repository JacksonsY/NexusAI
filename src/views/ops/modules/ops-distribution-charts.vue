<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'OpsDistributionCharts' });

/** 延迟直方图 + 错误分布环图 + 错误趋势三线（sub2api 可视化分析 3-up 对位·请求/上游/限流）。 */
const props = defineProps<{
  histogram: NexusAI.OpsLatencyBucket[];
  errorDist: NexusAI.OpsErrorCategory[];
  trend: NexusAI.OpsTrend | null;
  loading: boolean;
}>();

const emit = defineEmits<{ openErrors: [kind: 'all' | 'upstream' | 'limited'] }>();

const PIE_COLORS = ['#ef4444', '#f97316', '#eab308', '#8b5cf6', '#0ea5e9', '#14b8a6', '#ec4899', '#64748b'];

const AXIS = {
  axisLabel: { fontSize: 11, color: '#8c8c8c' },
  axisLine: { lineStyle: { color: 'rgba(140,140,140,0.2)' } },
  axisTick: { show: false }
};
const SPLIT = { lineStyle: { color: 'rgba(140,140,140,0.2)', type: 'dashed' as const } };

const { domRef: histoRef, updateOptions: updateHisto } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 } },
  toolbox: { feature: { saveAsImage: { title: '下载' } }, right: 8, top: -4 },
  grid: { left: 44, right: 16, top: 16, bottom: 24 },
  xAxis: { ...AXIS, type: 'category' as const, data: [] as string[] },
  yAxis: { ...AXIS, type: 'value' as const, minInterval: 1, splitLine: SPLIT },
  series: [] as object[]
}));

const { domRef: pieRef, updateOptions: updatePie } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'item' as const, textStyle: { fontSize: 12 } },
  legend: { textStyle: { fontSize: 12, color: '#8c8c8c' }, orient: 'vertical' as const, right: 8, top: 'center' },
  series: [] as object[]
}));

const { domRef: errTrendRef, updateOptions: updateErrTrend } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'axis' as const, textStyle: { fontSize: 12 } },
  legend: { textStyle: { fontSize: 11, color: '#8c8c8c' }, top: 0 },
  toolbox: { feature: { saveAsImage: { title: '下载' }, dataZoom: { yAxisIndex: 'none' as const } }, right: 8, top: -4 },
  grid: { left: 44, right: 16, top: 28, bottom: 24 },
  xAxis: { ...AXIS, type: 'category' as const, boundaryGap: false, data: [] as string[] },
  yAxis: { ...AXIS, type: 'value' as const, minInterval: 1, splitLine: SPLIT },
  series: [] as object[]
}));

function render() {
  updateHisto(opts => {
    (opts.xAxis as { data: string[] }).data = props.histogram.map(b => b.bucket);
    opts.series = [
      {
        name: '请求数',
        type: 'bar',
        barWidth: '55%',
        itemStyle: { color: '#2080f0', borderRadius: [4, 4, 0, 0] },
        data: props.histogram.map(b => Number(b.count))
      }
    ];
    return opts;
  });

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
        data: props.errorDist.map((item, index) => ({
          name: item.category,
          value: Number(item.count),
          itemStyle: { color: PIE_COLORS[index % PIE_COLORS.length] }
        }))
      }
    ];
    return opts;
  });

  const points = props.trend?.points ?? [];
  updateErrTrend(opts => {
    (opts.xAxis as { data: string[] }).data = points.map(p => p.ts.slice(11, 16));
    opts.series = [
      {
        name: '请求错误',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#ef4444' },
        data: points.map(p => Number(p.errorCount))
      },
      {
        name: '上游错误',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#8b5cf6' },
        data: points.map(p => Number(p.upstreamErrorCount))
      },
      {
        name: '限流',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 2, type: 'dashed' as const },
        itemStyle: { color: '#94a3b8' },
        data: points.map(p => Number(p.limitedErrorCount))
      }
    ];
    return opts;
  });
}

watch([() => props.histogram, () => props.errorDist, () => props.trend], () => render(), { deep: false });
</script>

<template>
  <div class="grid grid-cols-1 gap-16px lg:grid-cols-3">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">延迟分布</h3>
      <p class="m-0 mt-2px text-12px op-60">按响应耗时区间分桶</p>
      <div ref="histoRef" class="mt-8px h-240px w-full" />
    </NCard>
    <NCard :bordered="false" size="small" class="card-wrapper">
      <h3 class="m-0 text-14px font-semibold">错误分布</h3>
      <p class="m-0 mt-2px text-12px op-60">按错误分类（error_code / HTTP 状态）</p>
      <div v-if="!errorDist.length" class="flex items-center justify-center py-48px text-13px op-60">
        窗口内没有错误记录
      </div>
      <div v-show="errorDist.length" ref="pieRef" class="mt-8px h-240px w-full" />
    </NCard>
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="flex items-start justify-between">
        <div>
          <h3 class="m-0 text-14px font-semibold">错误趋势</h3>
          <p class="m-0 mt-2px text-12px op-60">请求错误 / 上游错误（≥500）/ 限流（429）</p>
        </div>
        <div class="flex gap-4px">
          <NButton size="tiny" quaternary @click="emit('openErrors', 'all')">详情</NButton>
          <NButton size="tiny" quaternary @click="emit('openErrors', 'upstream')">上游</NButton>
        </div>
      </div>
      <div ref="errTrendRef" class="mt-8px h-240px w-full" />
    </NCard>
  </div>
</template>

<style scoped></style>
