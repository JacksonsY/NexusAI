<!-- 性能多曲线面积图（PerformanceChart）：逐日每模型/渠道一条渐变面积曲线 ·
     右上 tok/s|TTFT 迷你切换 · Y 轴 P90 自适应封顶(吞吐1000/TTFT 60s) · 缺数日记 0 ·
     下方可点击图例按钮（加权均值·点击 solo 单条） -->
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useThemeVars } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import { DIM_COLORS, chartBase } from '../composables/chart-base';
import { formatDuration } from '../composables/use-dashboard';

defineOptions({ name: 'PerformanceChart' });

/** 日点（父组件已注入展示名）。 */
interface Row {
  date: string;
  id: string;
  name: string;
  throughput: number;
  ttftMs: number;
  requestCount: number;
}
const props = withDefaults(defineProps<{ rows: Row[]; emptyText?: string }>(), { emptyText: '暂无数据' });

const theme = useThemeVars();

type Mode = 'throughput' | 'ttft';
const mode = ref<Mode>('throughput');
/** solo：点击图例按钮只看单条（activeSeries）。 */
const activeId = ref<string | null>(null);

// MAX_CHART_THROUGHPUT / MAX_CHART_TTFT_MS：极端值封顶防一条曲线压扁全图
const CAP = { throughput: 1000, ttft: 60000 } as const;

const dates = computed(() => [...new Set(props.rows.map(r => r.date))].sort());
const ids = computed(() => [...new Set(props.rows.map(r => r.id))].sort());

const statsMap = computed(() => {
  const m: Record<string, Record<string, Row>> = {};
  for (const r of props.rows) {
    (m[r.date] ??= {})[r.id] = r;
  }
  return m;
});

const colorOf = (id: string) => DIM_COLORS[Math.max(ids.value.indexOf(id), 0) % DIM_COLORS.length];

/** 图例按钮（请求量加权均值·按名称排序——legendItems）。 */
const legendItems = computed(() =>
  ids.value
    .map(id => {
      const list = props.rows.filter(r => r.id === id);
      const total = list.reduce((s, r) => s + r.requestCount, 0);
      const wTput = total > 0 ? list.reduce((s, r) => s + r.throughput * r.requestCount, 0) / total : 0;
      const wTtft = total > 0 ? list.reduce((s, r) => s + r.ttftMs * r.requestCount, 0) / total : 0;
      return { id, name: list[0]?.name ?? id, color: colorOf(id), avgThroughput: wTput, avgTtft: wTtft };
    })
    .sort((a, b) => a.name.localeCompare(b.name))
);

/** hex → rgba（面积渐变 0.3→0，照抄 linearGradient stop）。 */
function hexA(hex: string, alpha: number) {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16);
  // eslint-disable-next-line no-bitwise
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/** Y 轴上限（照抄 ）：吞吐取 P90×1.1（样本>10）否则最大×1.1，下限 10；TTFT 取最大×1.1，下限 100；再按 CAP 封顶。 */
function yMax(m: Mode) {
  if (m === 'throughput') {
    const vals = props.rows.map(r => r.throughput).filter(v => v > 0).sort((a, b) => a - b);
    const base = vals.length > 10 ? (vals[Math.floor(vals.length * 0.9)] ?? vals[vals.length - 1]) : (vals[vals.length - 1] ?? 0);
    return Math.min(Math.max(10, Math.ceil(base * 1.1)), CAP.throughput);
  }
  const max = props.rows.reduce((s, r) => Math.max(s, r.ttftMs > 0 ? r.ttftMs : 0), 0);
  return Math.min(Math.max(100, Math.ceil(max * 1.1)), CAP.ttft);
}

function buildOption(opts: Record<string, any>) {
  const b = chartBase();
  const m = mode.value;
  const visible = activeId.value ? ids.value.filter(id => id === activeId.value) : ids.value;
  const map = statsMap.value;
  const fmt = (v: number) => (m === 'throughput' ? `${Math.round(v)}` : formatDuration(v));

  opts.textStyle = b.textStyle;
  opts.grid = { left: 56, right: 16, top: 12, bottom: 28 };
  opts.xAxis = {
    ...b.axis,
    type: 'category',
    boundaryGap: false,
    data: dates.value,
    axisLabel: { ...b.axis.axisLabel, formatter: (d: string) => d.slice(5).replace('-', '/') }
  };
  opts.yAxis = {
    ...b.axis,
    type: 'value',
    max: yMax(m),
    splitLine: b.splitLine,
    axisLabel: { ...b.axis.axisLabel, formatter: fmt }
  };
  // tooltip ：标题日期 + 每条目（圆点 名称 / 原始未封顶值），吞吐降序·TTFT 升序，0 值条目隐藏
  opts.tooltip = {
    trigger: 'axis',
    textStyle: { fontSize: 12 },
    formatter: (params: { axisValue?: string }[]) => {
      const date = params?.[0]?.axisValue ?? '';
      const items = visible
        .map(id => {
          const stat = map[date]?.[id];
          const v = m === 'throughput' ? (stat?.throughput ?? 0) : (stat?.ttftMs ?? 0);
          return { id, name: stat?.name ?? id, v };
        })
        .filter(x => x.v > 0)
        .sort((a, x) => (m === 'throughput' ? x.v - a.v : a.v - x.v));
      if (!items.length) {
        return '';
      }
      const lines = items
        .map(
          x =>
            `<div style="margin-top:2px"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${colorOf(x.id)};margin-right:6px"></span>${x.name}</div>` +
            `<div style="margin-left:14px;color:#8c8c8c">${m === 'throughput' ? `${Math.round(x.v)} tok/s` : `TTFT ${formatDuration(x.v)}`}</div>`
        )
        .join('');
      return `<div style="font-weight:500;margin-bottom:2px">${date.slice(5).replace('-', '/')}</div>${lines}`;
    }
  };
  // 每 id 一条渐变面积曲线（缺数日记 0·封顶 CAP——capped dataKey）
  opts.series = visible.map(id => {
    const color = colorOf(id);
    return {
      name: id,
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: dates.value.map(d => {
        const stat = map[d]?.[id];
        const v = m === 'throughput' ? (stat?.throughput ?? 0) : (stat?.ttftMs ?? 0);
        return Math.min(v, CAP[m]);
      }),
      lineStyle: { width: 2, color },
      itemStyle: { color },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0.05, color: hexA(color, 0.3) },
            { offset: 0.95, color: hexA(color, 0) }
          ]
        }
      }
    };
  });
  return opts;
}

const { domRef, updateOptions } = useEcharts(() => buildOption({}) as any);

watch(
  [() => props.rows, mode, activeId],
  () => {
    updateOptions(opts => buildOption(opts as Record<string, any>) as any);
  },
  { deep: true }
);

function toggleSolo(id: string) {
  activeId.value = activeId.value === id ? null : id;
}
</script>

<template>
  <div>
    <!-- 右上 tok/s | TTFT 切换（mini Tabs） -->
    <div class="mb-12px flex items-center justify-end">
      <div class="inline-flex shrink-0 items-center rounded-8px p-2px" :style="{ background: theme.actionColor }">
        <button
          v-for="o in [
            { label: 'tok/s', value: 'throughput' },
            { label: 'TTFT', value: 'ttft' }
          ]"
          :key="o.value"
          type="button"
          class="h-24px flex cursor-pointer items-center border-none rounded-6px bg-transparent px-10px text-12px leading-none transition-colors"
          :style="mode === o.value ? { background: theme.primaryColor, color: '#fff' } : { color: theme.textColor3 }"
          @click="mode = o.value as Mode"
        >
          {{ o.label }}
        </button>
      </div>
    </div>

    <div v-if="!rows.length" class="flex items-center justify-center text-14px h-350px" :style="{ color: theme.textColor3 }">
      {{ emptyText }}
    </div>
    <div v-show="rows.length" ref="domRef" class="h-350px w-full" />

    <!-- 图例按钮列表（点击 solo·加权均值 tok/s + TTFT） -->
    <div class="mt-16px grid gap-8px sm:grid-cols-2 lg:grid-cols-3">
      <button
        v-for="it in legendItems"
        :key="it.id"
        type="button"
        class="flex flex-col cursor-pointer gap-4px border border-solid rounded-md px-8px py-6px text-left text-14px transition-colors 2xl:flex-row 2xl:items-center 2xl:justify-between"
        :style="
          !activeId || activeId === it.id
            ? { borderColor: `${theme.primaryColor}66`, background: `${theme.primaryColor}0d`, color: theme.textColor1 }
            : { borderColor: theme.borderColor, background: 'transparent', color: theme.textColor3 }
        "
        @click="toggleSolo(it.id)"
      >
        <span class="min-w-0 flex items-center gap-8px">
          <span class="h-2.5 w-2.5 shrink-0 rounded-full" :style="{ background: it.color }" />
          <span class="truncate font-medium">{{ it.name }}</span>
        </span>
        <span class="text-12px tabular-nums" :style="{ color: theme.textColor3 }">
          {{ Math.round(it.avgThroughput) }} tok/s · TTFT {{ formatDuration(it.avgTtft) }}
        </span>
      </button>
    </div>
  </div>
</template>
