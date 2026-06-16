<!-- 每日概览图（DailyRequestStats：请求量左轴 primary + Token 右轴·渐变面积·去钱化无 cost 轴） -->
<script setup lang="ts">
import { watch } from 'vue';
import { useThemeVars } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import { formatCompact } from '../composables/use-dashboard';
import { chartBase } from '../composables/chart-base';

defineOptions({ name: 'DailyAreaChart' });

const props = defineProps<{ rows: NexusAI.DailyPoint[] }>();

const theme = useThemeVars();

const { domRef, updateOptions } = useEcharts(() => {
  const primary = theme.value.primaryColor;
  const b = chartBase();
  return {
    textStyle: b.textStyle,
    tooltip: { trigger: 'axis', textStyle: { fontSize: 12 } },
    legend: { ...b.legend, data: ['请求量', 'Token'], top: 0 },
    grid: { left: 44, right: 48, top: 36, bottom: 24 },
    xAxis: { ...b.axis, type: 'category', boundaryGap: false, splitLine: { show: false }, data: [] as string[] },
    yAxis: [
      {
        ...b.axis,
        type: 'value',
        name: '请求',
        splitLine: b.splitLine,
        axisLabel: { ...b.axis.axisLabel, formatter: (v: number) => formatCompact(v) }
      },
      {
        ...b.axis,
        type: 'value',
        name: 'Token',
        position: 'right',
        splitLine: { show: false },
        axisLabel: { ...b.axis.axisLabel, formatter: (v: number) => formatCompact(v) }
      }
    ],
    series: [
      {
        name: '请求量',
        type: 'line',
        smooth: true,
        yAxisIndex: 0,
        showSymbol: false,
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
              { offset: 0, color: `${primary}4d` },
              { offset: 1, color: `${primary}00` }
            ]
          }
        },
        data: [] as number[]
      },
      {
        name: 'Token',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        showSymbol: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#18a058' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#18a05833' },
              { offset: 1, color: '#18a05800' }
            ]
          }
        },
        data: [] as number[]
      }
    ]
  };
});

watch(
  () => props.rows,
  () => {
    const rows = props.rows ?? [];
    updateOptions(opts => {
      opts.xAxis.data = rows.map(r => (r.date ?? '').slice(5).replace('-', '/')); // MM/DD（toLocaleDateString）
      opts.series[0].data = rows.map(r => Number(r.total ?? 0));
      opts.series[1].data = rows.map(r => Number(r.totalTokens ?? 0));
      return opts;
    });
  },
  { deep: true, immediate: true }
);
</script>

<template>
  <div ref="domRef" class="h-350px w-full" />
</template>
