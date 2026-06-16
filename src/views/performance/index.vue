<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { fetchChannels, fetchPerfErrors, fetchPerfLive, fetchPerfPercentiles, fetchPerfProviders } from '@/service/api';
import ErrorCharts from './modules/error-charts.vue';
import LiveStatusCard from './modules/live-status-card.vue';
import PercentileCharts from './modules/percentile-charts.vue';
import PerfTrendCharts from './modules/perf-trend-charts.vue';
import ProviderPerfCard from './modules/provider-perf-card.vue';

defineOptions({ name: 'Performance' });

/** 性能分析（PerformanceAnalysis）：实时区 10s 轮询 + 历史区窗口可选（筛选防抖）。 */
const LIVE_REFRESH_INTERVAL_MS = 10_000;
const DEFAULT_SLOW_THRESHOLD_MS = 10_000;

// ===== 历史窗口（providers 用小时·percentiles/errors 用天） =====
const windowOptions = [
  { label: '近 24 小时', value: 24 },
  { label: '近 3 天', value: 72 },
  { label: '近 7 天', value: 168 },
  { label: '近 30 天', value: 720 }
];
const windowHours = ref(168);
const windowDays = computed(() => Math.max(1, Math.round(windowHours.value / 24)));

// ===== 实时区（10s 轮询） =====
const live = ref<NexusAI.PerfLive | null>(null);
const liveLoading = ref(false);
const liveUpdatedAt = ref<Date | null>(null);
let liveTimer: ReturnType<typeof setInterval> | null = null;

async function loadLive() {
  liveLoading.value = true;
  try {
    const { data, error } = await fetchPerfLive();
    if (!error) {
      live.value = data ?? null;
      liveUpdatedAt.value = new Date();
    }
  } finally {
    liveLoading.value = false;
  }
}

const liveUpdatedLabel = computed(() =>
  liveUpdatedAt.value
    ? liveUpdatedAt.value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '尚未刷新'
);

// ===== 渠道性能（筛选防抖） =====
const providers = ref<NexusAI.PerfProvidersResponse | null>(null);
const providersLoading = ref(false);
const filterChannelId = ref<number | null>(null);
const filterModel = ref('');
const filterModality = ref<string | null>('');
const filterStream = ref<'all' | 'true' | 'false'>('all');
const filterSlowMs = ref<number | null>(DEFAULT_SLOW_THRESHOLD_MS);
let providersDebounce: ReturnType<typeof setTimeout> | null = null;

const channelOptions = ref<{ label: string; value: number }[]>([]);

const slowThresholdValue = computed(() => {
  const value = Number(filterSlowMs.value);
  if (!Number.isFinite(value)) return DEFAULT_SLOW_THRESHOLD_MS;
  return Math.min(600_000, Math.max(1, Math.round(value)));
});

async function loadProviders() {
  providersLoading.value = true;
  try {
    const { data, error } = await fetchPerfProviders({
      channelId: filterChannelId.value,
      model: filterModel.value.trim() || null,
      modality: filterModality.value || null,
      stream: filterStream.value === 'all' ? null : filterStream.value === 'true',
      lookbackHours: windowHours.value,
      slowThresholdMs: slowThresholdValue.value,
      limit: 8
    });
    if (!error) {
      providers.value = data ?? null;
    }
  } finally {
    providersLoading.value = false;
  }
}

function resetFilters() {
  filterChannelId.value = null;
  filterModel.value = '';
  filterModality.value = '';
  filterStream.value = 'all';
  filterSlowMs.value = DEFAULT_SLOW_THRESHOLD_MS;
}

// ===== 分位 / 错误 =====
const percentileRows = ref<NexusAI.PercentileDayRow[]>([]);
const percentileLoading = ref(false);
const errors = ref<NexusAI.PerfErrorsResponse | null>(null);
const errorsLoading = ref(false);

async function loadPercentiles() {
  percentileLoading.value = true;
  try {
    const { data, error } = await fetchPerfPercentiles(windowDays.value);
    if (!error) {
      percentileRows.value = data ?? [];
    }
  } finally {
    percentileLoading.value = false;
  }
}

async function loadErrors() {
  errorsLoading.value = true;
  try {
    const { data, error } = await fetchPerfErrors(windowDays.value);
    if (!error) {
      errors.value = data ?? null;
    }
  } finally {
    errorsLoading.value = false;
  }
}

const isRefreshing = computed(
  () => liveLoading.value || providersLoading.value || percentileLoading.value || errorsLoading.value
);

async function refreshAll() {
  await Promise.allSettled([loadLive(), loadProviders(), loadPercentiles(), loadErrors()]);
}

watch(windowHours, () => {
  loadProviders();
  loadPercentiles();
  loadErrors();
});

watch([filterChannelId, filterModel, filterModality, filterStream, filterSlowMs], () => {
  if (providersDebounce) clearTimeout(providersDebounce);
  providersDebounce = setTimeout(() => {
    providersDebounce = null;
    loadProviders();
  }, 180);
});

onMounted(async () => {
  const { data, error } = await fetchChannels();
  if (!error) {
    channelOptions.value = (data ?? []).map(ch => ({ label: ch.name ?? `渠道 ${ch.id}`, value: Number(ch.id) }));
  }
  refreshAll();
  liveTimer = setInterval(loadLive, LIVE_REFRESH_INTERVAL_MS);
});

onUnmounted(() => {
  if (liveTimer) clearInterval(liveTimer);
  if (providersDebounce) clearTimeout(providersDebounce);
});
</script>

<template>
  <div class="flex-col gap-16px pb-16px">
    <!-- 页头（标题 + 实时刷新标识 + 上次更新 + 刷新 + 窗口选择） -->
    <div class="flex flex-col gap-12px xl:flex-row xl:items-center xl:justify-between">
      <div>
        <h1 class="m-0 text-18px font-semibold">性能分析</h1>
        <p class="m-0 mt-2px text-12px op-60">实时性能监控与历史延迟趋势</p>
      </div>
      <div class="flex flex-wrap items-center gap-8px">
        <NTag size="small" round>实时 10s 刷新</NTag>
        <span class="text-12px op-60">上次更新 {{ liveUpdatedLabel }}</span>
        <NButton size="small" circle quaternary :loading="isRefreshing" aria-label="刷新" @click="refreshAll">
          <template #icon>
            <SvgIcon icon="lucide:refresh-cw" />
          </template>
        </NButton>
        <NSelect v-model:value="windowHours" :options="windowOptions" size="small" class="w-128px" aria-label="历史窗口" />
      </div>
    </div>

    <LiveStatusCard :data="live" :loading="liveLoading" />

    <ProviderPerfCard
      v-model:channel-id="filterChannelId"
      v-model:model="filterModel"
      v-model:modality="filterModality"
      v-model:stream="filterStream"
      v-model:slow-ms="filterSlowMs"
      :data="providers"
      :loading="providersLoading"
      :channel-options="channelOptions"
      :slow-threshold-ms="slowThresholdValue"
      @reset="resetFilters"
    />

    <PerfTrendCharts :timeline="providers?.timeline ?? []" :loading="providersLoading" />

    <PercentileCharts :rows="percentileRows" :loading="percentileLoading" />

    <ErrorCharts :data="errors" :loading="errorsLoading" />
  </div>
</template>
