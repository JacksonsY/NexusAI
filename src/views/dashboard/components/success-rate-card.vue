<!-- 成功率卡（像素级复刻 SuccessRateCard：图标方块 + 大数字 + primary/20 轨道进度条 + 失败请求/平均值） -->
<script setup lang="ts">
import { useThemeVars } from 'naive-ui';
import DashCard from './dash-card.vue';
import { formatCompact } from '../composables/use-dashboard';

defineOptions({ name: 'SuccessRateCard' });

interface Props {
  label: string;
  rate: number;
  failed: number;
}

defineProps<Props>();

const theme = useThemeVars();
</script>

<template>
  <DashCard class="h-full">
    <!-- CardHeader -->
    <div class="flex flex-row items-center justify-between px-24px pb-8px">
      <div class="flex items-center gap-8px">
        <div class="flex items-center justify-center rounded-12px p-6px" :style="{ background: `${theme.primaryColor}1a`, color: theme.primaryColor }">
          <SvgIcon icon="lucide:shield-check" class="text-16px" />
        </div>
        <span class="text-14px font-medium">{{ label }}</span>
      </div>
    </div>

    <!-- CardContent: space-y-3 -->
    <div class="px-24px">
      <div class="flex flex-col gap-12px">
        <div class="font-mono text-30px font-bold leading-9">
          {{ rate.toFixed(1) }}<span class="ml-2px text-18px" :style="{ color: theme.textColor3 }">%</span>
        </div>
        <NProgress
          type="line"
          :percentage="rate"
          :height="8"
          :border-radius="4"
          :show-indicator="false"
          :color="theme.primaryColor"
          :rail-color="`${theme.primaryColor}33`"
        />
        <div class="flex items-center justify-between text-12px">
          <span :style="{ color: theme.textColor3 }">{{ formatCompact(failed) }} 失败请求</span>
          <span class="font-medium" :style="{ color: theme.primaryColor }">平均值</span>
        </div>
      </div>
    </div>
  </DashCard>
</template>
