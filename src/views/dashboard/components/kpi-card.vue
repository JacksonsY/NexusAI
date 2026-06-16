<!-- 总请求数卡（像素级复刻 TotalRequestsCard：shadcn Card 结构 + 图标方块 + 大数字 + 环比药丸 + 右下波浪） -->
<script setup lang="ts">
import { computed } from 'vue';
import { useThemeVars } from 'naive-ui';
import DashCard from './dash-card.vue';

defineOptions({ name: 'KpiCard' });

interface Props {
  label: string;
  value: string | number;
  icon?: string;
  growth?: number | null;
  growthLabel?: string;
}

const props = withDefaults(defineProps<Props>(), {
  icon: 'lucide:database',
  growth: null,
  growthLabel: '较上周'
});

const theme = useThemeVars();
const isPositive = computed(() => (props.growth ?? 0) >= 0);
const growthText = computed(() => {
  const g = props.growth ?? 0;
  return `${g >= 0 ? '+' : ''}${g.toFixed(0)}%`;
});
const iconBox = computed(() => ({ background: `${theme.value.primaryColor}1a`, color: theme.value.primaryColor }));
const pill = computed(() =>
  isPositive.value
    ? { background: `${theme.value.primaryColor}1a`, borderColor: `${theme.value.primaryColor}33`, color: theme.value.primaryColor }
    : { background: '#d030501a', borderColor: '#d0305033', color: '#d03050' }
);
</script>

<template>
  <DashCard class="h-full">
    <!-- CardHeader: flex flex-row items-center justify-between px-6 pb-2 -->
    <div class="flex flex-row items-center justify-between px-24px pb-8px">
      <div class="flex items-center gap-8px">
        <div class="flex items-center justify-center rounded-12px p-6px" :style="iconBox">
          <SvgIcon :icon="icon" class="text-16px" />
        </div>
        <span class="text-14px font-medium">{{ label }}</span>
      </div>
    </div>

    <!-- CardContent: px-6 + space-y-2 -->
    <div class="px-24px">
      <div class="flex flex-col gap-8px">
        <div class="break-all font-mono text-30px font-bold leading-9">{{ value }}</div>
        <div v-if="growth != null" class="flex items-center gap-4px text-12px font-medium">
          <span class="border rounded-10px px-6px py-2px" :style="pill">{{ growthText }}</span>
          <span :style="{ color: theme.textColor3 }">{{ growthLabel }}</span>
        </div>
      </div>
    </div>

    <!-- 右下角波浪装饰（text-primary/5 × opacity-50 ≈ 2.5% 总透明度） -->
    <div
      class="pointer-events-none absolute -bottom-16px -right-16px h-96px w-96px"
      :style="{ color: theme.primaryColor, opacity: 0.025 }"
    >
      <svg viewBox="0 0 24 24" fill="none" class="h-full w-full">
        <path d="M3 13.5C3 13.5 7 19 12 19C17 19 21 13.5 21 13.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M3 9C3 9 7 14 12 14C17 14 21 9 21 9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M3 4.5C3 4.5 7 10 12 10C17 10 21 4.5 21 4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>
  </DashCard>
</template>
