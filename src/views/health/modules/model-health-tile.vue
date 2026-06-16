<script setup lang="ts">
import { computed } from 'vue';
import { useThemeVars } from 'naive-ui';
import { formatCompact, formatMs, formatPercent, healthLabel, healthTagType, successRateClass } from '../composables/use-health-format';
import HealthTimeline from './health-timeline.vue';
import MetricBox from './metric-box.vue';

defineOptions({ name: 'ModelHealthTile' });

const theme = useThemeVars();

/** 模型健康瓦片（模型卡）：模型 Tab 网格与渠道展开明细共用。点击钻取性能详情。 */
const props = withDefaults(
  defineProps<{
    item: NexusAI.ModelHealth;
    /** 是否展示「N 个渠道」前缀（模型 Tab 展示·渠道明细不展示） */
    showChannelInfo?: boolean;
  }>(),
  { showChannelInfo: false }
);

const emit = defineEmits<{ detail: [model: string] }>();

const metaText = computed(() => {
  const attempts = `${formatCompact(props.item.totalAttempts)} 次请求`;
  if (props.showChannelInfo) return `${props.item.channelCount} 个渠道 / ${attempts}`;
  return attempts;
});
</script>

<template>
  <div
    class="health-tile relative cursor-pointer overflow-hidden border border-gray-500/20 rounded-12px p-16px transition-colors duration-150"
    role="button"
    tabindex="0"
    :aria-label="`查看 ${item.model} 性能详情`"
    @click="emit('detail', item.model)"
    @keydown.enter="emit('detail', item.model)"
  >
    <div
      class="absolute inset-x-0 top-0 h-1px"
      :style="{ background: `linear-gradient(to right, transparent, ${theme.primaryColor}66, transparent)` }"
    />
    <div class="flex items-start justify-between gap-12px">
      <div class="min-w-0 flex items-center gap-12px">
        <div class="h-44px w-44px flex shrink-0 items-center justify-center border border-gray-500/20 rounded-12px bg-gray-500/8">
          <SvgIcon icon="lucide:bot" class="text-20px op-60" />
        </div>
        <h4 class="min-w-0 truncate text-14px font-semibold" :title="item.model">{{ item.model }}</h4>
      </div>
      <NTag :type="healthTagType(item)" size="small" round class="shrink-0">{{ healthLabel(item) }}</NTag>
    </div>

    <div class="mt-16px grid grid-cols-3 gap-8px">
      <MetricBox label="延迟" icon="lucide:gauge" :value="formatMs(item.avgLatencyMs)" />
      <MetricBox label="Ping" icon="lucide:radio" :value="formatMs(item.avgFirstByteMs)" />
      <MetricBox label="可用率" icon="lucide:activity" :value="formatPercent(item.successRate)" :value-class="successRateClass(item.successRate)" />
    </div>

    <div class="mt-16px flex items-center justify-between gap-12px text-11px uppercase tracking-wide op-60">
      <span>History (60pts)</span>
      <span class="truncate normal-case tracking-normal">{{ metaText }}</span>
    </div>

    <HealthTimeline
      class="mt-8px"
      :timeline="item.timeline"
      :time-range-start="item.timeRangeStart"
      :time-range-end="item.timeRangeEnd"
      :height="28"
    />
  </div>
</template>

<style scoped>
.health-tile:hover {
  border-color: v-bind('`${theme.primaryColor}80`');
}
</style>
