<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NCard, NEmpty, NSpin } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import { fetchIntervalTimeline } from '@/service/api/usage';

/**
 * 请求间隔时间线卡（IntervalTimelineCard）：近 N 小时按用户分组的
 * 相邻请求间隔散点（y=分钟），多用户分色 + 自定义图例（≤6 项 +N 更多），
 * tooltip「间隔: X.X 分钟」；hours 随页面时间范围预设联动。
 */
defineOptions({ name: 'IntervalTimelineCard' });

const props = withDefaults(defineProps<{ title: string; hours?: number }>(), { hours: 24 });

/** 多分组配色（COLORS 10 色环） */
const COLORS = [
  'rgba(59, 130, 246, 0.7)',
  'rgba(236, 72, 153, 0.7)',
  'rgba(34, 197, 94, 0.7)',
  'rgba(249, 115, 22, 0.7)',
  'rgba(168, 85, 247, 0.7)',
  'rgba(234, 179, 8, 0.7)',
  'rgba(14, 165, 233, 0.7)',
  'rgba(239, 68, 68, 0.7)',
  'rgba(20, 184, 166, 0.7)',
  'rgba(99, 102, 241, 0.7)'
];
const MAX_LEGEND_ITEMS = 6;

const loading = ref(false);
const timeline = ref<NexusAI.IntervalTimeline | null>(null);
let loadSeq = 0;

const hasData = computed(() => (timeline.value?.points?.length ?? 0) > 0);
const multiProject = computed(() => Object.keys(timeline.value?.projects || {}).length > 1);

const legendItems = computed(() => {
  const projects = Object.entries(timeline.value?.projects || {});
  if (projects.length <= 1) return [];
  return projects.map(([code, name], i) => ({ id: code, name: name || code, color: COLORS[i % COLORS.length] }));
});
const displayLegendItems = computed(() => legendItems.value.slice(0, MAX_LEGEND_ITEMS));
const hiddenLegendCount = computed(() => Math.max(0, legendItems.value.length - MAX_LEGEND_ITEMS));

const { domRef, updateOptions } = useEcharts(() => ({
  grid: { left: 44, right: 16, top: 12, bottom: 24 },
  tooltip: {
    trigger: 'item',
    formatter: (p: { seriesName?: string; value?: [string, number]; data?: { model?: string } }) => {
      const y = Array.isArray(p.value) ? p.value[1] : 0;
      const model = p.data?.model ? `<br/>${p.data.model}` : '';
      const head = multiProject.value ? `${p.seriesName}: ` : '间隔: ';
      return `${head}${Number(y).toFixed(1)} 分钟${model}`;
    }
  },
  xAxis: { type: 'time', axisLabel: { hideOverlap: true } },
  yAxis: { type: 'value', name: '分钟', nameTextStyle: { fontSize: 10 }, splitLine: { lineStyle: { opacity: 0.4 } } },
  series: [] as unknown[]
}));

function rebuildChart() {
  const points = timeline.value?.points || [];
  const projects = timeline.value?.projects || {};
  updateOptions(opts => {
    if (multiProject.value) {
      const ids = Object.keys(projects);
      opts.series = ids.map((id, i) => ({
        name: projects[id] || id,
        type: 'scatter',
        symbolSize: 6,
        itemStyle: { color: COLORS[i % COLORS.length] },
        data: points.filter(p => p.projectCode === id).map(p => ({ value: [p.x, p.y], model: p.model }))
      })) as never;
    } else {
      opts.series = [
        {
          name: '请求间隔',
          type: 'scatter',
          symbolSize: 6,
          itemStyle: { color: 'rgba(0, 102, 254, 0.65)' },
          data: points.map(p => ({ value: [p.x, p.y], model: p.model }))
        }
      ] as never;
    }
    return opts;
  });
}

async function load() {
  const seq = (loadSeq += 1);
  loading.value = true;
  const { data, error } = await fetchIntervalTimeline({ hours: props.hours, limit: 1500 });
  if (seq !== loadSeq) return;
  if (!error) {
    timeline.value = data;
    rebuildChart();
  }
  loading.value = false;
}

watch(() => props.hours, load, { immediate: true });

defineExpose({ reload: load });
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <div class="mb-8px flex items-center justify-between">
      <span class="text-sm font-medium">{{ title }}</span>
      <div v-if="displayLegendItems.length" class="flex flex-wrap items-center justify-end gap-8px text-11px">
        <div v-for="item in displayLegendItems" :key="item.id" class="flex items-center gap-4px">
          <span class="h-10px w-10px rounded-full" :style="{ backgroundColor: item.color }" />
          <span class="text-gray-400">{{ item.name }}</span>
        </div>
        <span v-if="hiddenLegendCount > 0" class="text-gray-400">+{{ hiddenLegendCount }} 更多</span>
      </div>
    </div>
    <NSpin :show="loading">
      <div v-show="hasData" ref="domRef" class="h-180px" />
      <div v-if="!hasData && !loading" class="h-180px flex items-center justify-center">
        <NEmpty description="暂无请求间隔数据" size="small" />
      </div>
    </NSpin>
  </NCard>
</template>

<style scoped></style>
