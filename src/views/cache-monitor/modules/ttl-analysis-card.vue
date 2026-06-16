<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchCacheHitAnalysis, fetchTtlAnalysis } from '@/service/api/ops';
import { fetchIntervalTimeline } from '@/service/api/usage';
import { useEcharts } from '@/hooks/common/echarts';

/**
 * TTL 分析卡（CacheMonitoring TTL 分析区·项目维度）：
 * 时间段选择 + 缓存命中概览网格 + 项目 TTL 分析表（频率/推荐 TTL/说明）+
 * 展开行请求间隔散点图（按间隔分档着色）。
 */
defineOptions({ name: 'TtlAnalysisCard' });

const HOURS_OPTIONS = [
  { value: 12, label: '12 小时' },
  { value: 24, label: '24 小时' },
  { value: 72, label: '3 天' },
  { value: 168, label: '7 天' },
  { value: 336, label: '14 天' },
  { value: 720, label: '30 天' }
];

const analysisHours = ref(24);
const loading = ref(false);
const hit = ref<NexusAI.CacheHitAnalysis | null>(null);
const ttl = ref<NexusAI.TtlAnalysisResponse | null>(null);

const expandedProject = ref<string | null>(null);
const timelineLoading = ref(false);
const timelinePoints = ref<{ x: string; y: number; model?: string }[]>([]);

/** 间隔分档着色（INTERVAL_COLORS 五档） */
function intervalColor(minutes: number): string {
  if (minutes <= 5) return 'rgba(34, 197, 94, 0.6)';
  if (minutes <= 15) return 'rgba(59, 130, 246, 0.6)';
  if (minutes <= 30) return 'rgba(168, 85, 247, 0.6)';
  if (minutes <= 60) return 'rgba(249, 115, 22, 0.6)';
  return 'rgba(239, 68, 68, 0.6)';
}

function frequencyLabel(ttlMin: number) {
  if (ttlMin <= 5) return '高频';
  if (ttlMin <= 15) return '中高频';
  if (ttlMin <= 30) return '中频';
  return '低频';
}

function frequencyClass(ttlMin: number) {
  if (ttlMin <= 5) return 'text-success font-medium';
  if (ttlMin <= 15) return 'text-info font-medium';
  if (ttlMin <= 30) return 'text-gray-400';
  return 'text-error';
}

function ttlTagType(ttlMin: number): 'success' | 'info' | 'default' | 'warning' {
  if (ttlMin <= 5) return 'success';
  if (ttlMin <= 15) return 'info';
  if (ttlMin <= 30) return 'default';
  return 'warning';
}

function intervalDescription(p: NexusAI.TtlAnalysisProject): string {
  const p90 = p.percentiles?.p90;
  if (p90 === null || p90 === undefined) return '-';
  if (p90 < 1) return `90% 请求间隔 < ${Math.round(p90 * 60)} 秒`;
  return `90% 请求间隔 < ${p90.toFixed(1)} 分钟`;
}

const fmt = (n?: number | null) => (n === null || n === undefined ? '0' : Number(n).toLocaleString());
/** 美元费用展示（后端回美元数值·保留 6 位小数）。 */
const fmtUsd = (n?: number | null) => (n === null || n === undefined ? '$0.000000' : `$${Number(n).toFixed(6)}`);

const { domRef, updateOptions } = useEcharts(() => ({
  grid: { left: 48, right: 16, top: 16, bottom: 28 },
  tooltip: {
    trigger: 'item',
    formatter: (p: { value?: [string, number]; data?: { model?: string } }) => {
      const y = Array.isArray(p.value) ? p.value[1] : 0;
      const model = p.data?.model ? `<br/>${p.data.model}` : '';
      return `间隔: ${Number(y).toFixed(1)} 分钟${model}`;
    }
  },
  xAxis: { type: 'time', axisLabel: { hideOverlap: true } },
  yAxis: { type: 'value', name: '分钟', nameTextStyle: { fontSize: 10 } },
  series: [] as unknown[]
}));

function rebuildChart() {
  updateOptions(opts => {
    opts.series = [
      {
        name: '请求间隔',
        type: 'scatter',
        symbolSize: 6,
        data: timelinePoints.value.map(p => ({
          value: [p.x, p.y],
          model: p.model,
          itemStyle: { color: intervalColor(p.y) }
        }))
      }
    ] as never;
    return opts;
  });
}

async function toggleExpand(projectCode: string) {
  if (expandedProject.value === projectCode) {
    expandedProject.value = null;
    return;
  }
  expandedProject.value = projectCode;
  timelineLoading.value = true;
  timelinePoints.value = [];
  const { data, error } = await fetchIntervalTimeline({ hours: analysisHours.value, limit: 5000 });
  if (!error && expandedProject.value === projectCode) {
    timelinePoints.value = (data?.points ?? []).filter(p => p.projectCode === projectCode);
    rebuildChart();
  }
  timelineLoading.value = false;
}

async function load() {
  loading.value = true;
  expandedProject.value = null;
  const [hitRes, ttlRes] = await Promise.all([
    fetchCacheHitAnalysis(analysisHours.value),
    fetchTtlAnalysis(analysisHours.value)
  ]);
  if (!hitRes.error) hit.value = hitRes.data;
  if (!ttlRes.error) ttl.value = ttlRes.data;
  loading.value = false;
}

const hasProjects = computed(() => (ttl.value?.projects?.length ?? 0) > 0);

onMounted(load);
defineExpose({ reload: load });
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <div class="mb-8px flex items-center justify-between gap-12px">
      <div class="flex items-center gap-8px">
        <span class="text-sm font-semibold">TTL 分析</span>
        <span class="hidden text-xs text-gray-400 sm:inline">分析项目请求间隔，推荐合适的缓存 TTL</span>
      </div>
      <NSelect
        v-model:value="analysisHours"
        :options="HOURS_OPTIONS"
        size="small"
        class="w-110px"
        @update:value="load"
      />
    </div>

    <!-- 缓存命中概览（5 格·USD 口径） -->
    <div v-if="hit" class="grid grid-cols-2 mb-12px gap-16px border border-gray-100 rounded-8px bg-gray-50 p-16px md:grid-cols-5 dark:border-gray-800 dark:bg-gray-900">
      <div>
        <div class="text-xs text-gray-400">请求命中率</div>
        <div class="text-22px text-success font-bold">{{ hit.requestCacheHitRate }}%</div>
        <div class="text-xs text-gray-400">{{ fmt(hit.requestsWithCacheHit) }} / {{ fmt(hit.totalRequests) }} 请求</div>
      </div>
      <div>
        <div class="text-xs text-gray-400">Token 命中率</div>
        <div class="text-22px font-bold">{{ hit.tokenCacheHitRate }}%</div>
        <div class="text-xs text-gray-400">{{ fmt(hit.totalCacheReadTokens) }} tokens 命中</div>
      </div>
      <div>
        <div class="text-xs text-gray-400">缓存创建费用</div>
        <div class="text-22px font-bold">{{ fmtUsd(hit.cacheCreationCostUsd) }}</div>
        <div class="text-xs text-gray-400">{{ fmt(hit.totalCacheCreationTokens) }} tokens</div>
      </div>
      <div>
        <div class="text-xs text-gray-400">缓存读取费用</div>
        <div class="text-22px font-bold">{{ fmtUsd(hit.cacheReadCostUsd) }}</div>
        <div class="text-xs text-gray-400">{{ fmt(hit.totalCacheReadTokens) }} tokens</div>
      </div>
      <div>
        <div class="text-xs text-gray-400">预估节省</div>
        <div class="text-22px text-success font-bold">{{ fmtUsd(hit.estimatedSavingsUsd) }}</div>
        <div class="text-xs text-gray-400">USD</div>
      </div>
    </div>

    <!-- 项目 TTL 分析表 -->
    <NSpin :show="loading">
      <table v-if="hasProjects" class="w-full border-collapse text-sm">
        <thead>
          <tr class="border-b border-gray-200 text-left text-xs text-gray-400 dark:border-gray-700">
            <th class="w-32px py-8px" />
            <th class="py-8px">项目</th>
            <th class="py-8px text-center">请求数</th>
            <th class="py-8px text-center">使用频率</th>
            <th class="py-8px text-center">推荐 TTL</th>
            <th class="py-8px">说明</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="p in ttl?.projects" :key="p.projectCode">
            <tr
              class="cursor-pointer border-b border-gray-100 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800"
              @click="toggleExpand(p.projectCode)"
            >
              <td class="py-8px text-gray-400">
                <SvgIcon :icon="expandedProject === p.projectCode ? 'carbon:chevron-down' : 'carbon:chevron-right'" />
              </td>
              <td class="py-8px font-medium">{{ p.projectName || p.projectCode }}</td>
              <td class="py-8px text-center">{{ p.requestCount }}</td>
              <td class="py-8px text-center" :class="frequencyClass(p.recommendedTtlMinutes)">
                {{ frequencyLabel(p.recommendedTtlMinutes) }}
              </td>
              <td class="py-8px text-center">
                <NTag size="small" :type="ttlTagType(p.recommendedTtlMinutes)">{{ p.recommendedTtlMinutes }} 分钟</NTag>
              </td>
              <td class="py-8px text-xs text-gray-400">{{ intervalDescription(p) }}</td>
            </tr>
            <tr v-if="expandedProject === p.projectCode" class="bg-gray-50 dark:bg-gray-900">
              <td colspan="6" class="p-0">
                <div class="px-16px py-12px">
                  <div class="mb-8px flex flex-wrap items-center justify-between gap-8px">
                    <span class="text-sm font-medium">请求间隔时间线</span>
                    <div class="flex flex-wrap items-center gap-12px text-xs text-gray-400">
                      <span class="flex items-center gap-4px"><span class="h-8px w-8px rounded-full bg-green-500" />0-5分钟</span>
                      <span class="flex items-center gap-4px"><span class="h-8px w-8px rounded-full bg-blue-500" />5-15分钟</span>
                      <span class="flex items-center gap-4px"><span class="h-8px w-8px rounded-full bg-purple-500" />15-30分钟</span>
                      <span class="flex items-center gap-4px"><span class="h-8px w-8px rounded-full bg-orange-500" />30-60分钟</span>
                      <span class="flex items-center gap-4px"><span class="h-8px w-8px rounded-full bg-red-500" />&gt;60分钟</span>
                      <span v-if="timelinePoints.length">共 {{ timelinePoints.length }} 个数据点</span>
                    </div>
                  </div>
                  <div v-if="timelineLoading" class="h-256px flex items-center justify-center text-sm text-gray-400">
                    加载中...
                  </div>
                  <div v-show="!timelineLoading && timelinePoints.length > 0" ref="domRef" class="h-256px" />
                  <div
                    v-if="!timelineLoading && timelinePoints.length === 0"
                    class="h-256px flex items-center justify-center text-sm text-gray-400"
                  >
                    暂无数据
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-else-if="!loading" class="py-40px text-center">
        <p class="text-sm text-gray-400">未找到符合条件的项目数据</p>
        <p class="mt-4px text-xs text-gray-400">尝试增加分析时长</p>
      </div>
    </NSpin>
  </NCard>
</template>

<style scoped></style>
