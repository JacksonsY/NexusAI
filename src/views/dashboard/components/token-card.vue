<!-- 词元统计卡（像素级复刻 axonhub TokenStatsCard：图标方块 + 全部/月/周/日 迷你分段 + 输入/输出/缓存三列竖线分隔） -->
<script setup lang="ts">
import { computed, ref } from 'vue';
import { useThemeVars } from 'naive-ui';
import { formatCompact } from '../composables/use-dashboard';
import DashCard from './dash-card.vue';

defineOptions({ name: 'TokenCard' });

interface Props {
  /** dashboard.tokenStats（键为 `${period}${Input|Output|Cached}`，period=today/week/month/all） */
  tokens: Record<string, number>;
}

const props = defineProps<Props>();

const theme = useThemeVars();

type Period = 'all' | 'month' | 'week' | 'today';
const period = ref<Period>('today');
const periodOptions = [
  { label: '全部', value: 'all' },
  { label: '月', value: 'month' },
  { label: '周', value: 'week' },
  { label: '日', value: 'today' }
] as const;

const input = computed(() => props.tokens?.[`${period.value}Input`] ?? 0);
const output = computed(() => props.tokens?.[`${period.value}Output`] ?? 0);
const cached = computed(() => props.tokens?.[`${period.value}Cached`] ?? 0);
</script>

<template>
  <DashCard class="h-full">
    <!-- CardHeader: 标题 + 迷你分段 -->
    <div class="flex flex-wrap items-center justify-between gap-8px px-24px pb-8px">
      <div class="flex items-center gap-8px">
        <div class="flex items-center justify-center rounded-10px p-6px" :style="{ background: `${theme.primaryColor}1a`, color: theme.primaryColor }">
          <SvgIcon icon="lucide:bar-chart-4" class="text-16px" />
        </div>
        <span class="text-14px font-medium">词元统计</span>
      </div>
      <div class="inline-flex shrink-0 items-center rounded-8px p-2px" :style="{ background: theme.actionColor }">
        <button
          v-for="o in periodOptions"
          :key="o.value"
          type="button"
          class="h-20px flex cursor-pointer items-center border-none rounded-6px bg-transparent px-8px text-10px leading-none transition-colors"
          :style="period === o.value ? { background: theme.primaryColor, color: '#fff' } : { color: theme.textColor3 }"
          @click="period = o.value"
        >
          {{ o.label }}
        </button>
      </div>
    </div>

    <!-- CardContent: 三列竖线分隔 -->
    <div class="flex items-end justify-between px-24px">
      <div class="flex-1 text-center">
        <div class="text-12px" :style="{ color: theme.textColor3 }">输入</div>
        <div class="mt-4px font-mono text-18px font-bold">{{ formatCompact(input) }}</div>
      </div>
      <div class="h-32px w-1px shrink-0" :style="{ background: theme.dividerColor }" />
      <div class="flex-1 text-center">
        <div class="text-12px" :style="{ color: theme.textColor3 }">输出</div>
        <div class="mt-4px font-mono text-18px font-bold">{{ formatCompact(output) }}</div>
      </div>
      <div class="h-32px w-1px shrink-0" :style="{ background: theme.dividerColor }" />
      <div class="flex-1 text-center">
        <div class="text-12px" :style="{ color: theme.textColor3 }">缓存</div>
        <div class="mt-4px font-mono text-18px font-bold" :style="{ color: theme.textColor3 }">{{ formatCompact(cached) }}</div>
      </div>
    </div>
  </DashCard>
</template>
