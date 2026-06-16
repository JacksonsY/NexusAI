<script setup lang="ts">
defineOptions({ name: 'PolicyModePicker' });

/** 分段按钮组（调度策略页同款：bg-muted 容器内高亮选中项）。 */
const props = defineProps<{
  options: { label: string; value: string; icon?: string }[];
}>();

const model = defineModel<string>({ required: true });

function cols() {
  return props.options.length;
}
</script>

<template>
  <div class="grid gap-1 rounded-8px bg-gray-500/8 p-4px" :style="{ gridTemplateColumns: `repeat(${cols()}, minmax(0, 1fr))` }">
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      class="h-34px flex items-center justify-center gap-6px border-none rounded-6px px-8px text-13px font-medium transition-colors"
      :class="
        model === opt.value
          ? 'bg-primary/10 text-primary shadow-sm cursor-default'
          : 'bg-transparent text-gray-500 cursor-pointer hover:text-gray-700 dark:hover:text-gray-300'
      "
      @click="model = opt.value"
    >
      <SvgIcon v-if="opt.icon" :icon="opt.icon" class="text-14px" />
      {{ opt.label }}
    </button>
  </div>
</template>
