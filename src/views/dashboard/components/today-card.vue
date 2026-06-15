<!-- 今日请求卡（像素级复刻 axonhub TodayRequestsCard：bg-primary 反色 + Activity + ping 脉冲 + 本周/本月） -->
<script setup lang="ts">
import { useThemeVars } from 'naive-ui';

defineOptions({ name: 'TodayCard' });

interface Props {
  value: string | number;
  weekValue: string | number;
  monthValue: string | number;
}

defineProps<Props>();

const theme = useThemeVars();
</script>

<template>
  <!-- 复刻 shadcn Card 规格（rounded-14 + py-6 + gap-6），bg-primary 反色 -->
  <div
    class="today-card relative h-full flex flex-col gap-24px overflow-hidden py-24px"
    :style="{ background: theme.primaryColor, color: '#fff' }"
  >
    <!-- CardHeader -->
    <div class="flex flex-row items-center justify-between px-24px pb-8px">
      <div class="flex items-center gap-8px">
        <SvgIcon icon="lucide:activity" class="text-16px op-70" />
        <span class="text-14px font-medium op-90">今日请求</span>
      </div>
      <span class="h-8px w-8px animate-ping rounded-full bg-white" />
    </div>

    <!-- CardContent: space-y-4 + 数字 mt-2 -->
    <div class="px-24px">
      <div class="mt-8px font-mono text-36px font-bold leading-10 tracking-tight">{{ value }}</div>
      <div class="mt-16px flex justify-between border-t border-white/10 pt-12px text-12px op-70">
        <span>本周: {{ weekValue }}</span>
        <span>本月: {{ monthValue }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.today-card {
  /* shadcn rounded-xl = calc(--radius 0.75rem + 4px) = 16px */
  border-radius: 16px;
  box-shadow:
    0 1px 3px 0 rgb(0 0 0 / 8%),
    0 1px 2px -1px rgb(0 0 0 / 8%);
  transition: all 0.2s ease;
}

/* 一比一 axonhub .hover-card:hover */
.today-card:hover {
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgb(0 0 0 / 10%);
}
</style>
