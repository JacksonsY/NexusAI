<script setup lang="ts">
import { computed } from 'vue';

defineOptions({ name: 'HealthTimeline' });

/** 状态页式健康时间线（EndpointHealthTimeline 的 timeline 模式）：每格 = 时间段健康判色 + tooltip。 */
const props = withDefaults(
  defineProps<{
    /** 段状态序列（healthy/warning/unhealthy/unknown） */
    timeline: string[];
    timeRangeStart?: string | null;
    timeRangeEnd?: string | null;
    /** 格子条高度（端点 24px / 模型卡 28px / sparkline 12px） */
    height?: number;
    /** 隐藏底部起止时间标签（表格内 sparkline 用） */
    hideLabels?: boolean;
  }>(),
  { timeRangeStart: null, timeRangeEnd: null, height: 24, hideLabels: false }
);

const STATUS_COLOR: Record<string, string> = {
  healthy: 'bg-green-500/85 dark:bg-green-400/90',
  warning: 'bg-amber-400/85 dark:bg-amber-300/85',
  unhealthy: 'bg-red-500/85 dark:bg-red-400/90',
  unknown: 'bg-gray-300 dark:bg-gray-600'
};

const STATUS_LABEL: Record<string, string> = {
  healthy: '健康',
  warning: '波动',
  unhealthy: '异常',
  unknown: '无请求'
};

function formatTimestamp(value?: string | number | null) {
  if (!value) return '未知时间';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '未知时间';
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

const segments = computed(() => {
  const count = props.timeline.length || 1;
  const endMs = props.timeRangeEnd ? new Date(props.timeRangeEnd).getTime() : Date.now();
  const startMs = props.timeRangeStart ? new Date(props.timeRangeStart).getTime() : endMs - 6 * 3600 * 1000;
  const interval = Math.max(endMs - startMs, 1) / count;
  return props.timeline.map((status, index) => ({
    color: STATUS_COLOR[status] ?? STATUS_COLOR.unknown,
    tooltip: `${formatTimestamp(startMs + index * interval)} - ${formatTimestamp(startMs + (index + 1) * interval)}\n状态：${
      STATUS_LABEL[status] ?? '未知'
    }`
  }));
});

const earliestTime = computed(() => formatTimestamp(props.timeRangeStart));
const latestTime = computed(() => (props.timeRangeEnd ? formatTimestamp(props.timeRangeEnd) : formatTimestamp(Date.now())));
</script>

<template>
  <div class="w-full space-y-4px">
    <div class="w-full flex items-center gap-1px" :style="{ height: `${height}px` }">
      <NTooltip v-for="(segment, index) in segments" :key="index" :delay="100" placement="top">
        <template #trigger>
          <div
            class="h-full flex-1 cursor-pointer rounded-2px transition-all duration-150 hover:brightness-110 hover:scale-y-110"
            :class="segment.color"
          />
        </template>
        <div class="whitespace-pre-line text-xs">{{ segment.tooltip }}</div>
      </NTooltip>
    </div>
    <div v-if="!hideLabels" class="flex items-center justify-between text-10px op-60">
      <span>{{ earliestTime }}</span>
      <span>{{ latestTime }}</span>
    </div>
  </div>
</template>
