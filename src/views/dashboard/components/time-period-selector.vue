<!-- 时段选择器（TimePeriodSelector：迷你分段 h-7·选中 primary 反色·periods 可裁剪如最快榜只给 月/周/日） -->
<script setup lang="ts">
import { computed } from 'vue';
import { useThemeVars } from 'naive-ui';
import type { DashWindow } from '@/service/api';

defineOptions({ name: 'TimePeriodSelector' });

const props = withDefaults(defineProps<{ periods?: readonly DashWindow[] }>(), {
  periods: () => ['allTime', 'month', 'week', 'day'] as const
});

const value = defineModel<DashWindow>('value', { default: 'allTime' });

const theme = useThemeVars();

const LABELS: Record<DashWindow, string> = { allTime: '全部', month: '月', week: '周', day: '日' };

const options = computed(() => props.periods.map(p => ({ label: LABELS[p], value: p })));
</script>

<template>
  <div class="inline-flex shrink-0 items-center rounded-8px p-2px" :style="{ background: theme.actionColor }">
    <button
      v-for="o in options"
      :key="o.value"
      type="button"
      class="h-24px flex cursor-pointer items-center border-none rounded-6px bg-transparent px-8px text-10px leading-none transition-colors"
      :style="value === o.value ? { background: theme.primaryColor, color: '#fff' } : { color: theme.textColor3 }"
      @click="value = o.value"
    >
      {{ o.label }}
    </button>
  </div>
</template>
