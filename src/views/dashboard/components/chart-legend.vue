<!-- 图表序号图例（一比一 axonhub ChartLegend）：01. ● 名称 … 主值/次值·2 列竖向填充(gridAutoFlow column)或单列 -->
<script setup lang="ts">
import { computed } from 'vue';
import { useThemeVars } from 'naive-ui';

defineOptions({ name: 'ChartLegend' });

interface LegendItem {
  name: string;
  index: number;
  color: string;
  primaryValue: string;
  secondaryValue?: string;
}
const props = withDefaults(defineProps<{ items: LegendItem[]; columns?: 1 | 2 }>(), { columns: 2 });

const theme = useThemeVars();

/** 2 列时按 axonhub gridAutoFlow:column + gridTemplateRows 竖向填充（01/02 在左列、后续溢到右列）。 */
const gridStyle = computed(() =>
  props.columns === 2 ? { gridTemplateRows: `repeat(${Math.ceil(props.items.length / 2)}, auto)`, gridAutoFlow: 'column' as const } : undefined
);
</script>

<template>
  <div class="grid gap-x-4 gap-y-4" :style="gridStyle">
    <div v-for="(it, i) in items" :key="`${it.name}-${i}`" class="grid grid-cols-[auto_auto_1fr_auto] items-start gap-3">
      <span class="w-8 text-right text-14px font-semibold tabular-nums" :style="{ color: theme.textColor3 }">
        {{ String(it.index).padStart(2, '0') }}.
      </span>
      <span class="mt-1 h-2.5 w-2.5 shrink-0 rounded-full" :style="{ background: it.color }" />
      <span class="min-w-0 break-words text-14px font-medium">{{ it.name }}</span>
      <div class="text-right leading-tight">
        <div class="text-14px font-medium tabular-nums">{{ it.primaryValue }}</div>
        <div v-if="it.secondaryValue" class="text-12px tabular-nums" :style="{ color: theme.textColor3 }">{{ it.secondaryValue }}</div>
      </div>
    </div>
  </div>
</template>
