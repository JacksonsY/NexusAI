<script setup lang="ts">
defineOptions({ name: 'MetricBox' });

/** 指标小格（MetricBox + StatCard 的 hint）：标签 + 值，可选图标/值色/注脚。 */
defineProps<{
  label: string;
  value: string;
  icon?: string;
  valueClass?: string;
  /** 注脚（StatCard hint·如 incidents 计数） */
  hint?: string;
}>();
</script>

<template>
  <div class="border border-gray-500/15 rounded-8px bg-gray-500/5 px-12px py-8px">
    <div class="flex items-center gap-6px text-11px op-60">
      <SvgIcon v-if="icon" :icon="icon" class="text-13px" />
      {{ label }}
    </div>
    <div class="mt-4px text-14px font-semibold tabular-nums" :class="valueClass">{{ value }}</div>
    <div v-if="hint" class="mt-2px text-11px op-50">{{ hint }}</div>
  </div>
</template>
