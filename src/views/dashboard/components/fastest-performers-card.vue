<!-- 最快榜卡（一比一 axonhub FastestPerformersCard）：自治时段(月/周/日·默认月) ·
     横向柱(柱长=吞吐 tokens/s·Top5 异色) + 单列序号图例(主值 tok/s·次值请求数) -->
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useThemeVars } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import type { DashWindow } from '@/service/api';
import { fetchPerformance } from '@/service/api';
import { DIM_COLORS } from '../composables/chart-base';
import { formatCompact } from '../composables/use-dashboard';
import TimePeriodSelector from './time-period-selector.vue';
import ChartLegend from './chart-legend.vue';
import DashCard from './dash-card.vue';

defineOptions({ name: 'FastestPerformersCard' });

interface Props {
  title: string;
  /** 描述里的维度词（模型/渠道）。 */
  typeLabel: string;
  dim: 'model' | 'channel';
  /** key → 展示名（channel 查列表·model 取自身）。 */
  resolveName?: (k?: string) => string;
}
const props = withDefaults(defineProps<Props>(), { resolveName: (k?: string) => k ?? '—' });

const theme = useThemeVars();

const window = ref<DashWindow>('month');
const rows = ref<NexusAI.PerfStat[]>([]);

async function load() {
  const { data } = await fetchPerformance(props.dim, window.value, 5);
  rows.value = data ?? [];
}
watch(window, load);
onMounted(load);

/** 榜单行（一比一 axonhub：吞吐降序 Top5·色板按显示索引）。 */
const items = computed(() =>
  rows.value
    .slice(0, 5)
    .map(r => ({
      name: props.resolveName(r.key),
      throughput: Number(r.throughput ?? 0),
      requestCount: Number(r.requestCount ?? 0)
    }))
    .sort((a, b) => b.throughput - a.throughput)
    .map((r, i) => ({ ...r, color: DIM_COLORS[i % DIM_COLORS.length] }))
);

const totalThroughput = computed(() => items.value.reduce((s, r) => s + r.throughput, 0));
const totalRequests = computed(() => items.value.reduce((s, r) => s + r.requestCount, 0));

const { domRef, updateOptions } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    textStyle: { fontSize: 12 },
    formatter: (params: { dataIndex?: number }[]) => {
      const it = items.value[params?.[0]?.dataIndex ?? -1];
      if (!it) {
        return '';
      }
      const pct = totalThroughput.value > 0 ? (it.throughput / totalThroughput.value) * 100 : 0;
      return (
        `<div style="font-weight:500">${it.name}</div>` +
        `<div style="color:#8c8c8c">${Math.round(it.throughput)} tokens/s (${Math.round(pct)}%)</div>` +
        `<div style="color:#8c8c8c;font-size:11px">${it.requestCount} requests</div>`
      );
    }
  },
  grid: { left: 20, right: 24, top: 10, bottom: 10 },
  // axonhub HorizontalBarChart：X 数值轴隐藏·Y 名称轴隐藏（靠下方序号图例对应）·首位在顶
  xAxis: { type: 'value', show: false },
  yAxis: { type: 'category', data: [] as string[], inverse: true, show: false },
  series: [
    {
      type: 'bar',
      barWidth: 24,
      itemStyle: { borderRadius: [0, 4, 4, 0] },
      data: [] as { value: number; itemStyle: { color: string } }[]
    }
  ]
}));

watch(
  items,
  () => {
    updateOptions(opts => {
      opts.yAxis.data = items.value.map(r => r.name);
      opts.series[0].data = items.value.map(r => ({ value: r.throughput, itemStyle: { color: r.color } }));
      return opts;
    });
  },
  { deep: true, immediate: true }
);
</script>

<template>
  <DashCard class="h-full">
    <div class="flex flex-row items-center justify-between gap-2 px-24px">
      <div class="flex flex-col gap-4px">
        <span class="text-16px font-semibold leading-none">{{ title }}</span>
        <span class="text-14px" :style="{ color: theme.textColor3 }">
          最快的{{ typeLabel }} by tokens/second · {{ formatCompact(totalRequests) }} 次请求
        </span>
      </div>
      <TimePeriodSelector v-model:value="window" :periods="['month', 'week', 'day']" />
    </div>
    <div class="px-24px">
      <div v-if="!items.length" class="flex items-center justify-center text-14px h-250px" :style="{ color: theme.textColor3 }">
        暂无数据
      </div>
      <template v-else>
        <div ref="domRef" class="h-280px w-full" />
        <ChartLegend
          :items="
            items.map((it, i) => ({
              name: it.name,
              index: i + 1,
              color: it.color,
              primaryValue: `${Math.round(it.throughput)} tok/s`,
              secondaryValue: `${formatCompact(it.requestCount)} req`
            }))
          "
          :columns="1"
          class="mt-16px"
        />
      </template>
    </div>
  </DashCard>
</template>
