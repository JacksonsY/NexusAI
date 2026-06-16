<!-- 维度 Token Top 分组柱图（TokensByChannelChart）：输入/输出/缓存三色并列（非堆叠）·X 轴显示名 -->
<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';
import { formatCompact } from '../composables/use-dashboard';
import { chartBase } from '../composables/chart-base';

defineOptions({ name: 'DimensionTokensChart' });

interface Row {
  name: string;
  inputTokens?: number;
  outputTokens?: number;
  cachedTokens?: number;
}
const props = defineProps<{ rows: Row[] }>();

// TOKEN_COLORS：input/output/cached = chart-1/2/3，此处取清晰可分的蓝/绿/橙
const TOKEN_COLORS = { input: '#2080f0', output: '#18a058', cached: '#f0a020' };

const { domRef, updateOptions } = useEcharts(() => {
  const b = chartBase();
  return {
    textStyle: b.textStyle,
    // tokens tooltip：名称 + 输入/输出/缓存明细 + 分隔线 + Token 总计(占比)。顶部无色块图例（同）
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      textStyle: { fontSize: 12 },
      formatter: (params: { dataIndex?: number }[]) => {
        const rows = props.rows ?? [];
        const r = rows[params?.[0]?.dataIndex ?? -1];
        if (!r) {
          return '';
        }
        const tot = (x: Row) => Number(x.inputTokens ?? 0) + Number(x.outputTokens ?? 0) + Number(x.cachedTokens ?? 0);
        const sum = rows.reduce((s, x) => s + tot(x), 0);
        const pct = sum > 0 ? ((tot(r) / sum) * 100).toFixed(1) : '0.0';
        const line = (label: string, v?: number) =>
          `<div style="display:flex;justify-content:space-between;gap:16px"><span style="color:#8c8c8c">${label}:</span><span style="font-weight:500">${formatCompact(v)}</span></div>`;
        return (
          `<div style="font-weight:500;margin-bottom:4px">${r.name}</div>` +
          line('输入Token', r.inputTokens) +
          line('输出Token', r.outputTokens) +
          line('缓存Token', r.cachedTokens) +
          `<div style="border-top:1px solid rgba(140,140,140,.3);margin-top:4px;padding-top:4px;display:flex;justify-content:space-between;gap:16px">` +
          `<span style="font-weight:500">Token:</span><span style="font-weight:600">${formatCompact(tot(r))} (${pct}%)</span></div>`
        );
      }
    },
    grid: { left: 48, right: 16, top: 16, bottom: 48 },
    xAxis: {
      ...b.axis,
      type: 'category',
      data: [] as string[],
      axisLabel: {
        ...b.axis.axisLabel,
        interval: 0,
        formatter: (s: string) => (s && s.length > 8 ? `${s.slice(0, 7)}…` : s)
      }
    },
    yAxis: {
      ...b.axis,
      type: 'value',
      splitLine: b.splitLine,
      axisLabel: { ...b.axis.axisLabel, formatter: (v: number) => formatCompact(v) }
    },
    series: [
      { name: '输入', type: 'bar', barGap: '10%', barWidth: '22%', itemStyle: { color: TOKEN_COLORS.input, borderRadius: [4, 4, 0, 0] }, data: [] as number[] },
      { name: '输出', type: 'bar', barWidth: '22%', itemStyle: { color: TOKEN_COLORS.output, borderRadius: [4, 4, 0, 0] }, data: [] as number[] },
      { name: '缓存', type: 'bar', barWidth: '22%', itemStyle: { color: TOKEN_COLORS.cached, borderRadius: [4, 4, 0, 0] }, data: [] as number[] }
    ]
  };
});

watch(
  () => props.rows,
  () => {
    const rows = props.rows ?? [];
    updateOptions(opts => {
      opts.xAxis.data = rows.map(r => r.name);
      opts.series[0].data = rows.map(r => Number(r.inputTokens ?? 0));
      opts.series[1].data = rows.map(r => Number(r.outputTokens ?? 0));
      opts.series[2].data = rows.map(r => Number(r.cachedTokens ?? 0));
      return opts;
    });
  },
  { deep: true, immediate: true }
);
</script>

<template>
  <div ref="domRef" class="h-320px w-full" />
</template>
