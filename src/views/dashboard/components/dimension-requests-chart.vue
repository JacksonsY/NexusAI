<!-- 维度请求量 Top 柱图（一比一 axonhub RequestsByChannelChart·去 cost）：每柱异色·X 轴 hide 靠下方序号图例对应 -->
<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';
import { formatCompact } from '../composables/use-dashboard';
import { DIM_COLORS, chartBase } from '../composables/chart-base';

defineOptions({ name: 'DimensionRequestsChart' });

interface Row {
  name: string;
  total?: number;
}
const props = defineProps<{ rows: Row[] }>();

const { domRef, updateOptions } = useEcharts(() => {
  const b = chartBase();
  return {
    textStyle: b.textStyle,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      textStyle: { fontSize: 12 },
      formatter: (p: { name: string; value: number }[]) => {
        const it = p?.[0];
        return it ? `${it.name}<br/>请求量：${formatCompact(it.value)}` : '';
      }
    },
    grid: { left: 48, right: 16, top: 16, bottom: 8 },
    // axonhub：<XAxis dataKey='name' hide /> —— 隐藏渠道名，靠卡片下方 ChartLegend 序号 + 圆点对应
    xAxis: { type: 'category', data: [] as string[], show: false },
    yAxis: {
      ...b.axis,
      type: 'value',
      splitLine: b.splitLine,
      axisLabel: { ...b.axis.axisLabel, formatter: (v: number) => formatCompact(v) }
    },
    series: [
      {
        name: '请求量',
        type: 'bar',
        barWidth: 32,
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        data: [] as { value: number; itemStyle: { color: string } }[]
      }
    ]
  };
});

watch(
  () => props.rows,
  () => {
    const rows = props.rows ?? [];
    updateOptions(opts => {
      opts.xAxis.data = rows.map(r => r.name);
      opts.series[0].data = rows.map((r, i) => ({ value: Number(r.total ?? 0), itemStyle: { color: DIM_COLORS[i % DIM_COLORS.length] } }));
      return opts;
    });
  },
  { deep: true, immediate: true }
);
</script>

<template>
  <div ref="domRef" class="h-320px w-full" />
</template>
