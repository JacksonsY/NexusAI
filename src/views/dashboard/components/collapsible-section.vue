<!-- 折叠分区（CollapsibleSection）：带边框卡式区头 p-4 + primary/10 图标徽章 + text-lg 标题 + Chevron 旋转·展开态 localStorage 记忆 -->
<script setup lang="ts">
import { useStorage } from '@vueuse/core';
import { useThemeVars } from 'naive-ui';

defineOptions({ name: 'CollapsibleSection' });

interface Props {
  /** 分区标题 */
  title: string;
  /** iconify 图标名（图标徽章） */
  icon: string;
  /** localStorage 持久化键（展开态记忆） */
  storageKey: string;
  /** 默认是否展开 */
  defaultOpen?: boolean;
}

const props = withDefaults(defineProps<Props>(), { defaultOpen: false });

const theme = useThemeVars();

const open = useStorage(`nexusai-dash-section-${props.storageKey}`, props.defaultOpen);
</script>

<template>
  <div>
    <button
      type="button"
      class="section-head w-full flex cursor-pointer items-center justify-between border border-solid rounded-lg p-16px text-left transition-colors"
      :style="{ background: theme.cardColor, borderColor: theme.borderColor, color: theme.textColor1 }"
      @click="open = !open"
    >
      <div class="flex items-center gap-12px">
        <div
          class="h-32px w-32px flex items-center justify-center rounded-md"
          :style="{ background: `${theme.primaryColor}1a`, color: theme.primaryColor }"
        >
          <SvgIcon :icon="icon" class="text-16px" />
        </div>
        <span class="text-18px font-semibold">{{ title }}</span>
      </div>
      <SvgIcon
        icon="carbon:chevron-down"
        class="text-20px transition-transform duration-200"
        :style="{ color: theme.textColor3 }"
        :class="{ 'rotate-180': open }"
      />
    </button>
    <NCollapseTransition :show="open">
      <div class="mt-16px">
        <slot />
      </div>
    </NCollapseTransition>
  </div>
</template>

<style scoped>
/* hover:bg-accent/50 */
.section-head:hover {
  background-color: v-bind('theme.hoverColor') !important;
}
</style>
