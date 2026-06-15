<script setup lang="ts">
import { computed, ref } from 'vue';
import { NCard, NEmpty, NSpin } from 'naive-ui';
import { fmtCount, fmtTokens, fmtUsd, num } from '../composables/format';

/**
 * 活跃度热图卡（一比一 Aether ActivityHeatmapCard）：GitHub 风格 365 天格子，
 * 主题色 alpha 随当日请求量线性插值（0.08 空 → 0.95 峰值），周列 × 7 行，
 * 月份顶标 + 周一/三/五侧标，悬停跟随 tooltip（日期/请求/tokens/费用 $）。
 */
defineOptions({ name: 'ActivityHeatmapCard' });

const props = defineProps<{
  data: NexusAI.UsageHeatmap | null;
  title: string;
  loading?: boolean;
}>();

type Day = { date: string; requests: number; totalTokens: number; costUsd: number };

const days = computed<Day[]>(() =>
  (props.data?.days || []).map(d => ({
    date: d.date,
    requests: num(d.requests),
    totalTokens: num(d.totalTokens),
    costUsd: num(d.costUsd)
  }))
);

const maxRequests = computed(() => Math.max(num(props.data?.maxRequests), 1));

/** 周列结构：首日前补齐空位对齐星期（周日行 0）。 */
const weeks = computed<(Day | null)[][]>(() => {
  const list = days.value;
  if (!list.length) return [];
  const first = new Date(`${list[0].date}T00:00:00`);
  const pad = first.getDay();
  const cells: (Day | null)[] = [...Array.from({ length: pad }, () => null), ...list];
  const out: (Day | null)[][] = [];
  for (let i = 0; i < cells.length; i += 7) {
    out.push(cells.slice(i, i + 7));
  }
  return out;
});

/** 月份标签（出现新月份的列打标）。 */
const monthLabels = computed(() => {
  const labels: { index: number; text: string }[] = [];
  let lastMonth = -1;
  weeks.value.forEach((week, i) => {
    const firstDay = week.find(d => d != null);
    if (!firstDay) return;
    const m = Number(firstDay.date.slice(5, 7));
    if (m !== lastMonth) {
      labels.push({ index: i, text: `${m}月` });
      lastMonth = m;
    }
  });
  return labels;
});

function alphaOf(requests: number): number {
  if (requests <= 0) return 0.08;
  return 0.2 + 0.75 * Math.min(requests / maxRequests.value, 1);
}

/** 图例 5 档（一比一 Aether 少→多）。 */
const legendAlphas = [0.08, 0.25, 0.45, 0.65, 0.85];

/** 扁平格序列（grid-auto-flow:column 按列填充·每周补满 7 格保持对齐） */
const flatCells = computed<(Day | null)[]>(() =>
  weeks.value.flatMap(week => [...week, ...Array.from({ length: 7 - week.length }, () => null)])
);

// 悬停 tooltip（轻量自绘·365 格不挂 365 个 NTooltip）
const tip = ref<{ day: Day; x: number; y: number } | null>(null);

function onEnter(day: Day | null, e: MouseEvent) {
  if (!day) return;
  const rect = (e.target as HTMLElement).getBoundingClientRect();
  tip.value = { day, x: rect.left + rect.width / 2, y: rect.top };
}

function onLeave() {
  tip.value = null;
}
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper heatmap-card">
    <div class="mb-8px flex items-center justify-between">
      <span class="text-sm font-medium">{{ title }}</span>
      <div class="flex items-center gap-4px text-xs text-gray-400">
        <span>少</span>
        <span
          v-for="a in legendAlphas"
          :key="a"
          class="h-10px w-10px rounded-2px"
          :style="{ backgroundColor: `rgba(var(--heat-rgb), ${a})` }"
        />
        <span>多</span>
      </div>
    </div>
    <NSpin :show="loading">
      <!-- 内容区定高 180px（与间隔时间线卡等高·热图垂直居中） -->
      <div v-if="!loading && !days.length" class="h-180px flex items-center justify-center">
        <NEmpty description="暂无活跃数据" size="small" />
      </div>
      <!-- 自适应网格：横向 1fr 列 × 纵向 1fr 行，格子随卡片拉伸铺满内容区 -->
      <div v-else class="h-180px flex flex-col">
        <!-- 月份顶标（与格子同列网格对齐） -->
        <div class="flex">
          <div class="w-22px flex-shrink-0" />
          <div
            class="grid min-w-0 flex-1 gap-x-2px"
            :style="{ gridTemplateColumns: `repeat(${weeks.length}, 1fr)` }"
          >
            <span
              v-for="(week, i) in weeks"
              :key="`m-${i}`"
              class="overflow-visible whitespace-nowrap text-10px text-gray-400 leading-none"
            >
              {{ monthLabels.find(l => l.index === i)?.text || '' }}
            </span>
          </div>
        </div>
        <div class="mt-4px min-h-0 flex flex-1">
          <!-- 周标（与 7 行 1fr 对齐） -->
          <div class="grid w-22px flex-shrink-0 pr-4px" style="grid-template-rows: repeat(7, 1fr)">
            <span
              v-for="(w, i) in ['', '一', '', '三', '', '五', '']"
              :key="i"
              class="flex items-center text-9px text-gray-400"
            >
              {{ w }}
            </span>
          </div>
          <!-- 格子（auto-flow column 按列填·1fr 占满） -->
          <div
            class="grid min-w-0 flex-1 gap-2px"
            :style="{
              gridAutoFlow: 'column',
              gridTemplateRows: 'repeat(7, 1fr)',
              gridTemplateColumns: `repeat(${weeks.length}, 1fr)`
            }"
          >
            <div
              v-for="(day, i) in flatCells"
              :key="i"
              class="heat-cell rounded-2px"
              :style="day ? { backgroundColor: `rgba(var(--heat-rgb), ${alphaOf(day.requests)})` } : { backgroundColor: 'transparent' }"
              @mouseenter="onEnter(day, $event)"
              @mouseleave="onLeave"
            />
          </div>
        </div>
      </div>
    </NSpin>
    <!-- 悬停 tooltip（Teleport 到 body·跟随格子） -->
    <Teleport to="body">
      <div
        v-if="tip"
        class="pointer-events-none fixed z-9999 translate-x--50% translate-y--105% rounded-6px bg-black/85 px-8px py-6px text-xs text-white shadow-lg"
        :style="{ left: `${tip.x}px`, top: `${tip.y}px` }"
      >
        <div class="font-medium">{{ tip.day.date }}</div>
        <div>{{ fmtCount(tip.day.requests) }} 次请求 · {{ fmtTokens(tip.day.totalTokens) }} tokens</div>
        <div>消耗 {{ fmtUsd(tip.day.costUsd) }}</div>
      </div>
    </Teleport>
  </NCard>
</template>

<style scoped>
.heatmap-card {
  /* 品牌主色 RGB（热图 alpha 插值基色·图例与格子共用） */
  --heat-rgb: 0, 102, 254;
}

.heat-cell {
  transition: transform 0.15s ease;
  cursor: pointer;
}

.heat-cell:hover {
  transform: scale(1.35);
}
</style>
