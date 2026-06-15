<script setup lang="ts">
import { computed, watch } from 'vue';
import { NCard, NSpin } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import { fmtHitRate, fmtMs, fmtTokens, num } from '../composables/format';

/**
 * 用量维度分析卡（sub2api Model/Group/Endpoint DistributionChart 对位）：
 * 左环图（请求量占比 Top8+其他·中心总量）+ 右精简明细表（名称/请求/Tokens/费用$ + 维度差异列），
 * 数字全缩写防爆宽、卡内禁横向滚动、三卡同高。
 */
defineOptions({ name: 'UsageAggTable' });

/** 美元费用紧凑展示（聚合维度总额·保留 2 位小数防爆宽）。 */
function fmtUsdShort(v: number | string | null | undefined): string {
  return `$${num(v).toFixed(2)}`;
}

const props = defineProps<{
  title: string;
  kind: 'model' | 'channel' | 'apiFormat';
  data: NexusAI.UsageAggItem[];
  loading?: boolean;
}>();

const PIE_COLORS = ['#2080f0', '#18a058', '#f0a020', '#8b5cf6', '#0ea5e9', '#ec4899', '#14b8a6', '#64748b'];

const emptyText = computed(
  () =>
    ({ model: '暂无模型统计数据', channel: '暂无渠道统计数据', apiFormat: '暂无API格式统计数据' })[props.kind]
);

const dimTitle = computed(() => ({ model: '模型', channel: '渠道', apiFormat: 'API格式' })[props.kind]);
const extraTitle = computed(() => ({ model: '缓存命中', channel: '成功率', apiFormat: '平均响应' })[props.kind]);

function displayLabel(item: NexusAI.UsageAggItem): string {
  const label = item.label || item.key || '-';
  return props.kind === 'model' ? label.replace('claude-', '') : label;
}

/** 维度差异列取值（model→缓存命中率 / channel→成功率 / apiFormat→平均响应） */
function extraValue(item: NexusAI.UsageAggItem): string {
  if (props.kind === 'model') return fmtHitRate(item.cacheHitRate);
  if (props.kind === 'channel') return item.successRate != null ? `${item.successRate}%` : '-';
  return fmtMs(item.avgDurationMs);
}

function extraClass(item: NexusAI.UsageAggItem): string {
  if (props.kind === 'channel' && item.successRate != null && item.successRate < 90) return 'text-error';
  return 'text-gray-400';
}

const totalRequests = computed(() => props.data.reduce((s, i) => s + num(i.requests), 0));

/** 占比（行内迷你条） */
function ratio(item: NexusAI.UsageAggItem): number {
  return totalRequests.value > 0 ? (num(item.requests) / totalRequests.value) * 100 : 0;
}

const { domRef: pieRef, updateOptions: updatePie } = useEcharts(() => ({
  textStyle: { fontSize: 12 },
  tooltip: { trigger: 'item' as const, textStyle: { fontSize: 12 } },
  series: [] as object[]
}));

function renderPie() {
  const top = props.data.slice(0, 8);
  const rest = props.data.slice(8).reduce((s, i) => s + num(i.requests), 0);
  const items = top.map((i, idx) => ({
    name: displayLabel(i),
    value: num(i.requests),
    itemStyle: { color: PIE_COLORS[idx % PIE_COLORS.length] }
  }));
  if (rest > 0) items.push({ name: '其他', value: rest, itemStyle: { color: '#9ca3af' } });
  updatePie(opts => {
    opts.series = [
      {
        name: props.title,
        type: 'pie',
        radius: ['58%', '82%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 3, borderWidth: 2, borderColor: 'rgba(255,255,255,0.65)' },
        label: { show: false },
        emphasis: { scaleSize: 4 },
        data: items
      }
    ];
    return opts;
  });
}

watch(
  () => props.data,
  () => renderPie(),
  { deep: false }
);
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <div class="mb-6px flex items-baseline justify-between">
      <span class="text-sm font-medium">{{ title }}</span>
      <span class="text-11px text-gray-400 tabular-nums">{{ data.length }} 项</span>
    </div>
    <NSpin :show="loading">
      <!-- 内容区固定高（行数多寡不影响卡高·三卡绝对等高） -->
      <div v-if="!data.length && !loading" class="h-176px flex flex-col items-center justify-center text-gray-400">
        <span class="text-13px">{{ emptyText }}</span>
      </div>
      <div v-else class="h-176px flex items-center gap-8px">
        <!-- 占比环图（中心总量·sub2api Doughnut 对位） -->
        <div class="relative h-150px w-150px flex-shrink-0">
          <div ref="pieRef" class="h-full w-full" />
          <div class="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
            <span class="text-15px font-bold tabular-nums">{{ fmtTokens(totalRequests) }}</span>
            <span class="text-10px text-gray-400">请求</span>
          </div>
        </div>
        <!-- 精简明细（占比迷你条 + 缩写数字·无横向滚动·区域满高行少留白） -->
        <div class="agg-list h-full min-w-0 flex-1 overflow-y-auto pr-2px">
          <div class="agg-row text-11px text-gray-400">
            <span>{{ dimTitle }}</span>
            <span class="text-right">请求</span>
            <span class="text-right">Tokens</span>
            <span class="text-right">费用($)</span>
            <span class="text-right">{{ extraTitle }}</span>
          </div>
          <div v-for="(item, idx) in data" :key="item.key" class="agg-row group text-12px">
            <span class="flex items-center gap-6px overflow-hidden">
              <span
                class="h-7px w-7px flex-shrink-0 rounded-full"
                :style="{ background: idx < 8 ? PIE_COLORS[idx % PIE_COLORS.length] : '#9ca3af' }"
              />
              <span class="relative min-w-0 flex-1">
                <span class="block truncate font-medium" :title="item.label || item.key">{{ displayLabel(item) }}</span>
                <span
                  class="absolute bottom--3px left-0 h-2px rounded-full opacity-50"
                  :style="{
                    width: `${Math.max(ratio(item), 2)}%`,
                    background: idx < 8 ? PIE_COLORS[idx % PIE_COLORS.length] : '#9ca3af'
                  }"
                />
              </span>
            </span>
            <span class="text-right tabular-nums">{{ fmtTokens(item.requests) }}</span>
            <span class="text-right text-gray-400 tabular-nums" :title="`输入 ${fmtTokens(item.promptTokens)} / 输出 ${fmtTokens(item.completionTokens)}`">
              {{ fmtTokens(item.totalTokens) }}
            </span>
            <span class="text-right text-primary font-medium tabular-nums">{{ fmtUsdShort(item.costUsd) }}</span>
            <span class="text-right tabular-nums" :class="extraClass(item)">{{ extraValue(item) }}</span>
          </div>
        </div>
      </div>
    </NSpin>
  </NCard>
</template>

<style scoped>
.agg-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px 52px 56px 56px;
  gap: 6px;
  align-items: center;
  padding: 5px 0;
}

.agg-row + .agg-row {
  border-top: 1px solid rgba(128, 128, 128, 0.08);
}
</style>
