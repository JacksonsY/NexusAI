<!-- 精确复刻 shadcn Card（card.tsx: flex flex-col gap-6 rounded-xl border py-6 shadow-sm；--radius 0.75rem） -->
<script setup lang="ts">
import { useThemeVars } from 'naive-ui';

defineOptions({ name: 'DashCard' });

const theme = useThemeVars();
</script>

<template>
  <div
    class="dash-card relative flex flex-col gap-24px overflow-hidden border py-24px"
    :style="{ background: theme.cardColor, borderColor: theme.borderColor, color: theme.textColor1 }"
  >
    <slot />
  </div>
</template>

<style scoped>
.dash-card {
  /* shadcn rounded-xl = calc(--radius 0.75rem + 4px) = 16px */
  border-radius: 16px;
  box-shadow:
    0 1px 3px 0 rgb(0 0 0 / 8%),
    0 1px 2px -1px rgb(0 0 0 / 8%);
  transition: all 0.2s ease;
}

/* .hover-card:hover：scale(1.02) + shadow-md + 边框变主题色 */
.dash-card:hover {
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgb(0 0 0 / 10%);
  border-color: v-bind('theme.primaryColor') !important;
}
</style>
