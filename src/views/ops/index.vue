<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  fetchChannels,
  fetchOpsErrorDistribution,
  fetchOpsLatencyHistogram,
  fetchOpsOverview,
  fetchOpsRealtimeTraffic,
  fetchOpsTrend
} from '@/service/api';
import type { OpsQueryParams } from '@/service/api/ops';
import { loadOpsSettings, type OpsPageSettings } from './composables/use-ops-settings';
import OpsTrendCharts from './modules/ops-trend-charts.vue';
import OpsDistributionCharts from './modules/ops-distribution-charts.vue';
import OpsConcurrencyCard from './modules/ops-concurrency-card.vue';
import OpsTokenStatsCard from './modules/ops-token-stats-card.vue';
import AlertEventsCard from './modules/alert-events-card.vue';
import AlertRulesModal from './modules/alert-rules-modal.vue';
import OpsSettingsModal from './modules/ops-settings-modal.vue';
import ErrorDetailsModal from './modules/error-details-modal.vue';
import SlowRequestsModal from './modules/slow-requests-modal.vue';

/**
 * 运维监控（一比一 sub2api OpsDashboard·概念映射 platform/group→渠道、account→渠道 Key）：
 * Header（健康分+诊断+实时 QPS/TPS+概览+系统资源+渠道筛选/时间范围/自定义区间/全屏/设置）→
 * 并发卡+故障转移率+吞吐趋势 → 延迟直方图+错误分布+错误趋势三线（详情钻取）→
 * 模型 Token 统计 → 告警事件 → 告警规则/阈值/页面设置弹窗。
 */
defineOptions({ name: 'Ops' });

const RANGE_OPTIONS = [
  { label: '最近 5 分钟', value: 5 },
  { label: '最近 30 分钟', value: 30 },
  { label: '最近 1 小时', value: 60 },
  { label: '最近 6 小时', value: 360 },
  { label: '最近 24 小时', value: 1440 },
  { label: '自定义', value: -1 }
];

const route = useRoute();
const router = useRouter();

/** 实时流量窗口档（sub2api 1min/5min/30min/1h 对位） */
const REALTIME_WINDOWS = [
  { label: '1 分钟', value: 60 },
  { label: '5 分钟', value: 300 },
  { label: '30 分钟', value: 1800 },
  { label: '1 小时', value: 3600 }
];

const settings = ref<OpsPageSettings>(loadOpsSettings());
const rangeMinutes = ref(60);
const customRange = ref<[number, number] | null>(null);
const channelId = ref<number | null>(null);
const channelOptions = ref<{ label: string; value: number }[]>([]);
const loading = ref(false);
const lastUpdated = ref<Date | null>(null);
const autoRefresh = ref(true);
const countdown = ref(settings.value.autoRefreshSeconds);
const fullscreen = ref(false);

const overview = ref<NexusAI.OpsOverview | null>(null);
const trend = ref<NexusAI.OpsTrend | null>(null);
const histogram = ref<NexusAI.OpsLatencyBucket[]>([]);
const errorDist = ref<NexusAI.OpsErrorCategory[]>([]);
const realtimeWindow = ref(60);
const realtime = ref<NexusAI.OpsRealtimeTraffic | null>(null);

const showRules = ref(false);
const showSettings = ref(false);
const showCustomRange = ref(false);
const showErrors = ref(false);
const showSlow = ref(false);
const errorKind = ref<'all' | 'upstream' | 'limited'>('all');
const refreshToken = ref(0);

const healthColor = computed(() => {
  const s = overview.value?.healthScore ?? 100;
  if (s >= 90) return '#18a058';
  if (s >= 60) return '#f0a020';
  return '#d03050';
});

const statusLabel = computed(() => {
  const s = overview.value?.healthScore ?? 100;
  if (s >= 90) return '健康';
  if (s >= 60) return '降级';
  return '严重';
});

/** 当前生效窗口的小时数（错误详情弹窗复用） */
const effectiveHours = computed(() => {
  if (rangeMinutes.value === -1 && customRange.value) {
    return Math.max(1, Math.ceil((customRange.value[1] - customRange.value[0]) / 3_600_000));
  }
  return Math.max(1, Math.ceil(rangeMinutes.value / 60));
});

function fmtMs(v: number | null | undefined) {
  if (v == null) return '-';
  return v >= 1000 ? `${(v / 1000).toFixed(1)}s` : `${v}ms`;
}

function fmtCount(v: number | null | undefined) {
  const n = Number(v ?? 0);
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

function toLocalIso(ms: number) {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function buildParams(): OpsQueryParams {
  const params: OpsQueryParams = { channelId: channelId.value ?? undefined };
  if (rangeMinutes.value === -1 && customRange.value) {
    params.startTime = toLocalIso(customRange.value[0]);
    params.endTime = toLocalIso(customRange.value[1]);
  } else {
    params.rangeMinutes = rangeMinutes.value;
  }
  return params;
}

async function loadRealtime() {
  const { data, error } = await fetchOpsRealtimeTraffic({
    windowSeconds: realtimeWindow.value,
    channelId: channelId.value ?? undefined
  });
  if (!error && data) realtime.value = data;
}

/** 深链同步（sub2api URL query 对位：tr/channelId·hash 路由 replace 不进历史） */
function syncQuery() {
  const query: Record<string, string> = {};
  if (rangeMinutes.value !== 60 && rangeMinutes.value !== -1) query.tr = String(rangeMinutes.value);
  if (channelId.value != null) query.channelId = String(channelId.value);
  router.replace({ query }).catch(() => {});
}

async function load() {
  loading.value = true;
  syncQuery();
  const params = buildParams();
  const [ov, tr, hist, dist] = await Promise.all([
    fetchOpsOverview(params),
    fetchOpsTrend({ ...params, segments: 48 }),
    fetchOpsLatencyHistogram(params),
    fetchOpsErrorDistribution(params),
    loadRealtime()
  ]);
  if (!ov.error && ov.data) overview.value = ov.data;
  if (!tr.error && tr.data) trend.value = tr.data;
  if (!hist.error && hist.data) histogram.value = hist.data;
  if (!dist.error && dist.data) errorDist.value = dist.data;
  refreshToken.value += 1;
  lastUpdated.value = new Date();
  countdown.value = settings.value.autoRefreshSeconds;
  loading.value = false;
}

function changeRealtimeWindow(v: number) {
  realtimeWindow.value = v;
  loadRealtime();
}

/** Top 渠道芯片点击钻取（sub2api top_groups chip 对位）：设/清全局渠道筛选 */
function drillChannel(id: number) {
  channelId.value = channelId.value === id ? null : id;
  load();
}

function channelName(id: number) {
  return channelOptions.value.find(c => c.value === id)?.label ?? `渠道 #${id}`;
}

function changeRange(v: number) {
  if (v === -1) {
    showCustomRange.value = true;
    return;
  }
  rangeMinutes.value = v;
  customRange.value = null;
  load();
}

function applyCustomRange() {
  if (!customRange.value) return;
  rangeMinutes.value = -1;
  showCustomRange.value = false;
  load();
}

function openErrors(kind: 'all' | 'upstream' | 'limited') {
  errorKind.value = kind;
  showErrors.value = true;
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen?.();
    fullscreen.value = true;
  } else {
    document.exitFullscreen?.();
    fullscreen.value = false;
  }
}

function onSettingsSaved(s: OpsPageSettings) {
  settings.value = s;
  countdown.value = s.autoRefreshSeconds;
}

async function loadChannels() {
  const { data, error } = await fetchChannels();
  if (!error && data) {
    channelOptions.value = (data as { id: number; name: string }[]).map(c => ({
      label: c.name,
      value: Number(c.id)
    }));
  }
}

let timer: ReturnType<typeof setInterval> | null = null;

onMounted(() => {
  // 深链入参（tr=时间范围分钟 / channelId=渠道 / fullscreen=1 进全屏）
  const tr = Number(route.query.tr);
  if (RANGE_OPTIONS.some(o => o.value === tr)) rangeMinutes.value = tr;
  const cid = Number(route.query.channelId);
  if (Number.isFinite(cid) && cid > 0) channelId.value = cid;
  if (route.query.fullscreen === '1') toggleFullscreen();
  load();
  loadChannels();
  timer = setInterval(() => {
    if (!autoRefresh.value || loading.value) return;
    countdown.value -= 1;
    if (countdown.value <= 0) load();
  }, 1000);
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px">
    <!-- Header：健康分 + 实时流量 + 概览 + 系统资源 -->
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="mb-12px flex flex-wrap items-center justify-between gap-8px">
        <div class="flex items-center gap-8px">
          <h3 class="m-0 text-16px font-semibold">运维监控</h3>
          <NTag v-if="lastUpdated" size="tiny" :bordered="false">
            更新于 {{ lastUpdated.toLocaleTimeString() }}{{ autoRefresh ? `（${countdown}s 后刷新）` : '' }}
          </NTag>
        </div>
        <div class="flex flex-wrap items-center gap-8px">
          <NSelect
            v-model:value="channelId"
            size="small"
            :options="channelOptions"
            placeholder="全部渠道"
            clearable
            class="w-150px"
            @update:value="load"
          />
          <NSelect :value="rangeMinutes" size="small" :options="RANGE_OPTIONS" class="w-130px" @update:value="changeRange" />
          <NSwitch v-model:value="autoRefresh" size="small">
            <template #checked>自动</template>
            <template #unchecked>手动</template>
          </NSwitch>
          <NButton size="small" quaternary :loading="loading" @click="load">
            <template #icon>
              <SvgIcon icon="carbon:renew" />
            </template>
          </NButton>
          <NButton size="small" quaternary title="慢请求排查（按耗时倒序）" @click="showSlow = true">
            <template #icon>
              <SvgIcon icon="carbon:hourglass" />
            </template>
            慢请求
          </NButton>
          <NButton size="small" @click="showRules = true">
            <template #icon>
              <SvgIcon icon="carbon:notification" />
            </template>
            告警规则
          </NButton>
          <NButton size="small" quaternary title="页面设置" @click="showSettings = true">
            <template #icon>
              <SvgIcon icon="carbon:settings" />
            </template>
          </NButton>
          <NButton size="small" quaternary :title="fullscreen ? '退出全屏' : '全屏'" @click="toggleFullscreen">
            <template #icon>
              <SvgIcon :icon="fullscreen ? 'carbon:minimize' : 'carbon:maximize'" />
            </template>
          </NButton>
        </div>
      </div>

      <!-- 首载骨架屏（sub2api OpsDashboardSkeleton 对位） -->
      <div v-if="loading && !overview" class="space-y-12px">
        <div class="flex gap-16px">
          <NSkeleton circle class="h-96px w-96px" />
          <div class="flex-1 space-y-8px">
            <NSkeleton text :repeat="2" />
            <NSkeleton text class="w-60%" />
          </div>
        </div>
        <NSkeleton text :repeat="3" />
      </div>
      <NSpin v-else :show="loading">
        <div class="grid grid-cols-1 gap-16px lg:grid-cols-12">
          <!-- 健康分（悬停看完整诊断·sub2api 诊断报告弹窗对位） -->
          <div class="flex items-center gap-16px lg:col-span-4">
            <NPopover trigger="hover" placement="bottom-start">
              <template #trigger>
                <NProgress
                  type="circle"
                  :percentage="overview?.healthScore ?? 100"
                  :color="healthColor"
                  :stroke-width="10"
                  class="w-96px cursor-help"
                >
                  <span class="text-18px font-bold">{{ overview?.healthScore ?? '-' }}</span>
                </NProgress>
              </template>
              <div class="max-w-360px">
                <div class="mb-6px text-13px font-semibold">诊断报告</div>
                <template v-if="overview?.diagnosis?.length">
                  <div
                    v-for="(d, i) in overview.diagnosis"
                    :key="i"
                    class="py-2px text-12px"
                    :class="d.level === 'critical' ? 'text-red-500' : 'text-amber-500'"
                  >
                    {{ d.level === 'critical' ? '🔴' : '🟡' }} {{ d.message }}
                  </div>
                </template>
                <div v-else class="text-12px text-gray-400">各项指标均在阈值内（SLA/错误率/P99/TTFT/熔断/节点）</div>
                <div class="mt-6px border-t border-gray-100 pt-6px text-11px text-gray-400 dark:border-gray-800">
                  健康分 = 100 − 严重×30 − 警告×10；阈值可在「阈值设置」调整
                </div>
              </div>
            </NPopover>
            <div>
              <div class="flex items-center gap-8px">
                <span class="text-14px font-medium">系统健康分</span>
                <NTag size="tiny" :bordered="false" :color="{ color: `${healthColor}22`, textColor: healthColor }">
                  {{ statusLabel }}
                </NTag>
              </div>
              <div v-if="overview?.diagnosis?.length" class="mt-6px space-y-2px">
                <div
                  v-for="(d, i) in overview.diagnosis.slice(0, 3)"
                  :key="i"
                  class="text-12px"
                  :class="d.level === 'critical' ? 'text-red-500' : 'text-amber-500'"
                >
                  {{ d.message }}
                </div>
              </div>
              <p v-else class="mt-6px text-12px text-gray-400">各项指标均在阈值内</p>
              <NTag v-if="Number(overview?.firingAlerts) > 0" size="small" type="error" :bordered="false" class="mt-6px">
                {{ overview?.firingAlerts }} 条告警进行中
              </NTag>
            </div>
          </div>

          <!-- 实时流量 + 概览指标 -->
          <div class="lg:col-span-8">
            <div class="mb-8px flex items-center gap-8px">
              <span class="flex items-center gap-4px text-11px text-gray-400">
                <span class="relative flex h-8px w-8px">
                  <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400 opacity-60" />
                  <span class="relative inline-flex h-8px w-8px rounded-full bg-blue-500" />
                </span>
                实时流量窗口
              </span>
              <NButtonGroup size="tiny">
                <NButton
                  v-for="w in REALTIME_WINDOWS"
                  :key="w.value"
                  :type="realtimeWindow === w.value ? 'primary' : 'default'"
                  @click="changeRealtimeWindow(w.value)"
                >
                  {{ w.label }}
                </NButton>
              </NButtonGroup>
            </div>
            <div class="grid grid-cols-2 gap-12px md:grid-cols-4">
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">QPS（当前 / 峰值 / 均值）</div>
                <div class="mt-2px text-15px font-semibold tabular-nums">
                  {{ realtime?.qps.current ?? overview?.traffic.qps.current ?? '-' }}
                  <span class="text-12px text-gray-400 font-normal">
                    / {{ realtime?.qps.peak ?? '-' }} / {{ realtime?.qps.avg ?? '-' }}
                  </span>
                </div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">TPS（tokens/s·当前 / 峰值 / 均值）</div>
                <div class="mt-2px text-15px font-semibold tabular-nums">
                  {{ realtime?.tps.current ?? overview?.traffic.tps.current ?? '-' }}
                  <span class="text-12px text-gray-400 font-normal">
                    / {{ realtime?.tps.peak ?? '-' }} / {{ realtime?.tps.avg ?? '-' }}
                  </span>
                </div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">请求 / 错误</div>
                <div class="mt-2px text-15px font-semibold tabular-nums">
                  {{ fmtCount(overview?.requestCount) }}
                  <span
                    class="cursor-pointer text-12px font-normal underline-dotted"
                    :class="Number(overview?.errorCount) > 0 ? 'text-red-500' : 'text-gray-400'"
                    @click="openErrors('all')"
                  >
                    / {{ fmtCount(overview?.errorCount) }}
                  </span>
                </div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">SLA / 错误率</div>
                <div class="mt-2px text-15px font-semibold tabular-nums">
                  {{ overview?.successRate ?? '-' }}%
                  <span class="text-12px text-gray-400 font-normal">/ {{ overview?.errorRate ?? '-' }}%</span>
                </div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">延迟 avg / P90 / P99</div>
                <div class="mt-2px text-13px font-semibold tabular-nums">
                  {{ fmtMs(overview?.duration.avg) }} / {{ fmtMs(overview?.duration.p90) }} /
                  {{ fmtMs(overview?.duration.p99) }}
                </div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">TTFT avg / P90 / P99</div>
                <div class="mt-2px text-13px font-semibold tabular-nums">
                  {{ fmtMs(overview?.ttft.avg) }} / {{ fmtMs(overview?.ttft.p90) }} / {{ fmtMs(overview?.ttft.p99) }}
                </div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">Token 消耗</div>
                <div class="mt-2px text-15px font-semibold tabular-nums">{{ fmtCount(overview?.tokenConsumed) }}</div>
              </div>
              <div class="rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
                <div class="text-11px text-gray-400">在途并发（峰值 / 拒绝）</div>
                <div class="mt-2px text-15px font-semibold tabular-nums">
                  {{ overview?.concurrency.inFlight ?? '-' }}
                  <span class="text-12px text-gray-400 font-normal">
                    / {{ overview?.concurrency.peak ?? '-' }} / {{ overview?.concurrency.rejected ?? '-' }}
                  </span>
                </div>
              </div>
            </div>
            <!-- Top 渠道钻取芯片（sub2api top_groups chips 对位·点击设/清全局渠道筛选） -->
            <div v-if="overview?.topChannels?.length" class="mt-10px flex flex-wrap items-center gap-6px">
              <span class="text-11px text-gray-400">Top 渠道</span>
              <NTag
                v-for="c in overview.topChannels"
                :key="c.channelId"
                size="small"
                round
                class="cursor-pointer"
                :type="channelId === Number(c.channelId) ? 'primary' : 'default'"
                :bordered="false"
                @click="drillChannel(Number(c.channelId))"
              >
                {{ channelName(Number(c.channelId)) }}
                <span class="ml-4px tabular-nums op-70">{{ fmtCount(c.total) }}</span>
                <span v-if="Number(c.errors) > 0" class="ml-2px text-red-500 tabular-nums">!{{ fmtCount(c.errors) }}</span>
              </NTag>
            </div>
          </div>
        </div>

        <!-- 系统资源行 -->
        <div class="mt-12px flex flex-wrap items-center gap-x-20px gap-y-6px border-t border-gray-100 pt-10px text-12px dark:border-gray-800">
          <span>
            CPU
            <b class="tabular-nums">{{ overview?.system.cpuUsagePercent ?? '-' }}%</b>
          </span>
          <span>
            堆内存
            <b class="tabular-nums">
              {{ overview?.system.heapUsedMb }}/{{ overview?.system.heapMaxMb }}MB（{{ overview?.system.memoryUsagePercent }}%）
            </b>
          </span>
          <span>
            线程
            <b class="tabular-nums">{{ overview?.system.threadCount ?? '-' }}</b>
          </span>
          <span class="flex items-center gap-4px">
            MySQL
            <NTag size="tiny" :bordered="false" :type="overview?.system.dbOk ? 'success' : 'error'">
              {{ overview?.system.dbOk ? '正常' : '异常' }}
            </NTag>
          </span>
          <span class="flex items-center gap-4px">
            Redis
            <NTag size="tiny" :bordered="false" :type="overview?.system.redisOk ? 'success' : 'error'">
              {{ overview?.system.redisOk ? '正常' : '异常' }}
            </NTag>
          </span>
          <span v-if="overview?.system.dbPool">
            DB 连接池
            <b class="tabular-nums">
              {{ overview.system.dbPool.active }}+{{ overview.system.dbPool.idle }}/{{ overview.system.dbPool.max }}
            </b>
            <NTag v-if="overview.system.dbPool.awaiting > 0" size="tiny" type="warning" :bordered="false" class="ml-2px">
              等待 {{ overview.system.dbPool.awaiting }}
            </NTag>
          </span>
          <span>
            集群节点
            <b class="tabular-nums">{{ overview?.system.onlineNodes }}/{{ overview?.system.totalNodes }} 在线</b>
          </span>
          <span v-if="overview?.system.jobs?.length" class="flex items-center gap-4px">
            后台任务
            <NPopover trigger="hover" placement="bottom">
              <template #trigger>
                <NTag
                  size="tiny"
                  :bordered="false"
                  class="cursor-help"
                  :type="overview.system.jobs.every(j => j.ok) ? 'success' : 'warning'"
                >
                  {{ overview.system.jobs.filter(j => j.ok).length }}/{{ overview.system.jobs.length }} 心跳正常
                </NTag>
              </template>
              <div class="space-y-2px text-12px">
                <div v-for="j in overview.system.jobs" :key="j.name" class="flex items-center gap-8px">
                  <span :class="j.ok ? 'text-green-500' : 'text-amber-500'">{{ j.ok ? '●' : '○' }}</span>
                  <span class="font-mono">{{ j.name }}</span>
                  <span class="text-gray-400 tabular-nums">{{ j.lastRunDt?.replace('T', ' ')?.slice(5, 19) }}</span>
                </div>
              </div>
            </NPopover>
          </span>
        </div>
      </NSpin>
    </NCard>

    <!-- 第一行：并发卡 + 故障转移率 + 吞吐趋势（sub2api 1:1:2 对位） -->
    <OpsTrendCharts :trend="trend" :loading="loading">
      <OpsConcurrencyCard v-if="settings.showConcurrency" :refresh-token="refreshToken" :channel-id="channelId ?? undefined" />
      <div v-else class="hidden" />
    </OpsTrendCharts>

    <!-- 第二行：延迟直方图 + 错误分布 + 错误趋势三线 -->
    <OpsDistributionCharts
      :histogram="histogram"
      :error-dist="errorDist"
      :trend="trend"
      :loading="loading"
      @open-errors="openErrors"
    />

    <!-- 模型 Token 统计 -->
    <OpsTokenStatsCard v-if="settings.showTokenStats" :refresh-token="refreshToken" />

    <!-- 告警事件 -->
    <AlertEventsCard v-if="settings.showAlertEvents" :refresh-token="refreshToken" />

    <!-- 弹窗 -->
    <AlertRulesModal v-model:show="showRules" />
    <OpsSettingsModal v-model:show="showSettings" @saved="onSettingsSaved" @thresholds-saved="load" />
    <ErrorDetailsModal v-model:show="showErrors" :hours="effectiveHours" :channel-id="channelId ?? undefined" :kind="errorKind" />
    <SlowRequestsModal v-model:show="showSlow" :hours="effectiveHours" :channel-id="channelId ?? undefined" />

    <!-- 自定义时间范围 -->
    <NModal v-model:show="showCustomRange" preset="card" title="自定义时间范围" class="max-w-440px">
      <NDatePicker v-model:value="customRange" type="datetimerange" clearable class="w-full" />
      <template #footer>
        <div class="flex justify-end gap-8px">
          <NButton size="small" @click="showCustomRange = false">取消</NButton>
          <NButton size="small" type="primary" :disabled="!customRange" @click="applyCustomRange">应用</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
