<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NTag } from 'naive-ui';
import { fetchProxyNodeEvents, fetchProxyNodeMetrics } from '@/service/api';

/**
 * 节点数据展开面板（ProxyNodeDataPanel·手动节点适配）：
 * 24h 汇总格 + 在线率采样条形（1h 桶·颜色随在线率/错误）+ 最近事件 + 实时快照。
 */
defineOptions({ name: 'NodeDataPanel' });

const props = defineProps<{ node: NexusAI.ProxyNode }>();

const loading = ref(false);
const metrics = ref<NexusAI.ProxyNodeMetrics | null>(null);
const events = ref<NexusAI.ProxyNodeEvent[]>([]);
const loadedAt = ref<number | null>(null);

const buckets = computed(() => (metrics.value?.items ?? []).slice(-24));
const summary = computed(() => metrics.value?.summary ?? null);

const fmtNum = (n?: number | string | null) => (n === null || n === undefined ? '0' : Number(n).toLocaleString());

function fmtPercent(v: number | null | undefined) {
  if (v === null || v === undefined || !Number.isFinite(v)) return '-';
  return `${(v * 100).toFixed(v >= 0.995 || v === 0 ? 0 : 1)}%`;
}

function fmtMs(v: number | null | undefined) {
  if (v === null || v === undefined || !Number.isFinite(v) || v <= 0) return '-';
  if (v >= 1000) return `${(v / 1000).toFixed(1)}s`;
  return `${Math.round(v)}ms`;
}

function fmtTime(iso?: string | null) {
  if (!iso) return '-';
  const d = new Date(iso);
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff >= 0 && diff < 60) return '刚刚';
  if (diff >= 0 && diff < 3600) return `${Math.floor(diff / 60)}分钟前`;
  if (diff >= 0 && diff < 86400) return `${Math.floor(diff / 3600)}小时前`;
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function uptimeTone(v: number | null | undefined) {
  if (v === null || v === undefined) return '';
  if (v < 0.95) return 'text-error';
  if (v < 0.99) return 'text-warning';
  return 'text-primary';
}

function bucketColor(b: NexusAI.ProxyNodeMetricBucket) {
  if (b.samples <= 0) return 'rgba(148, 163, 184, 0.35)';
  if (b.errors > 0 && (b.uptimeRatio ?? 0) < 0.5) return 'rgba(220, 38, 38, 0.86)';
  if ((b.uptimeRatio ?? 0) < 0.98 || b.errors > 0) return 'rgba(217, 119, 6, 0.86)';
  return 'rgba(22, 163, 74, 0.86)';
}

function bucketHeight(b: NexusAI.ProxyNodeMetricBucket) {
  if (b.samples <= 0 || b.uptimeRatio === null) return '10%';
  return `${Math.max(10, Math.round(b.uptimeRatio * 100))}%`;
}

function bucketTitle(b: NexusAI.ProxyNodeMetricBucket) {
  const t = new Date(b.bucketStart).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit' });
  return `${t}，在线率 ${fmtPercent(b.uptimeRatio)}，延迟 ${fmtMs(b.latencyAvgMs)}，错误 ${b.errors}`;
}

function eventLabel(type: string) {
  if (type === 'connected') return '连接';
  if (type === 'disconnected') return '断开';
  if (type === 'error') return '错误';
  return type;
}

function eventTagType(type: string): 'success' | 'error' | 'default' {
  if (type === 'connected') return 'success';
  if (type === 'disconnected' || type === 'error') return 'error';
  return 'default';
}

const summaryStats = computed(() => {
  const s = summary.value;
  return [
    {
      label: '在线率',
      value: fmtPercent(s?.uptimeRatio),
      hint: `${fmtNum(s?.okSamples)}/${fmtNum(s?.samples)} 样本`,
      tone: uptimeTone(s?.uptimeRatio)
    },
    { label: '探测延迟', value: fmtMs(s?.latencyAvgMs), hint: `峰值 ${fmtMs(s?.latencyMaxMs)}`, tone: '' },
    { label: '探测失败', value: fmtNum(s?.errors), hint: '24h 累计', tone: (s?.errors ?? 0) > 0 ? 'text-error' : '' },
    { label: '总请求', value: fmtNum(props.node.totalRequests), hint: '经此代理透传', tone: '' },
    {
      label: '失败请求',
      value: fmtNum(props.node.failedRequests),
      hint: '透传 5xx/连接失败',
      tone: Number(props.node.failedRequests ?? 0) > 0 ? 'text-warning' : ''
    },
    { label: '延迟 EMA', value: fmtMs(props.node.avgLatencyMs), hint: '探测移动平均', tone: '' }
  ];
});

const snapshotItems = computed(() => [
  { label: '当前状态', value: props.node.status === 'online' ? '在线' : '离线' },
  { label: '最后探测', value: fmtTime(props.node.lastProbeAt) },
  { label: '最近在线', value: fmtTime(props.node.lastOkAt) },
  { label: '区域', value: props.node.region || '-' },
  { label: '代理地址', value: props.node.proxyUrl },
  { label: '创建时间', value: fmtTime(props.node.createDt) }
]);

const loadedText = computed(() => {
  if (loading.value) return '正在刷新采样数据';
  if (!loadedAt.value) return '展开后自动读取 24 小时指标和最近关键事件';
  return `数据刷新于 ${new Date(loadedAt.value).toLocaleTimeString('zh-CN')}`;
});

async function load() {
  loading.value = true;
  const [m, e] = await Promise.all([fetchProxyNodeMetrics(props.node.id, 24), fetchProxyNodeEvents(props.node.id, 8)]);
  if (!m.error) metrics.value = m.data;
  if (!e.error) events.value = e.data ?? [];
  loadedAt.value = Date.now();
  loading.value = false;
}

onMounted(load);
</script>

<template>
  <div class="border-t border-gray-100 bg-gray-50 px-16px py-12px dark:border-gray-800 dark:bg-gray-900">
    <div class="mb-12px flex items-center justify-between">
      <div>
        <div class="flex items-center gap-8px">
          <span class="text-sm font-semibold">节点数据</span>
          <NTag size="tiny" :bordered="false">最近 24 小时</NTag>
        </div>
        <p class="mt-2px text-xs text-gray-400">{{ loadedText }}</p>
      </div>
      <NButton size="tiny" quaternary :loading="loading" @click="load">刷新</NButton>
    </div>

    <div class="grid grid-cols-2 gap-8px md:grid-cols-3 xl:grid-cols-6">
      <div
        v-for="item in summaryStats"
        :key="item.label"
        class="border border-gray-100 rounded-8px bg-white px-12px py-8px dark:border-gray-800 dark:bg-gray-800"
      >
        <div class="text-xs text-gray-400">{{ item.label }}</div>
        <div class="mt-2px text-sm font-semibold tabular-nums" :class="item.tone">{{ item.value }}</div>
        <div class="mt-2px text-10px text-gray-400">{{ item.hint }}</div>
      </div>
    </div>

    <div class="mt-12px grid gap-12px lg:grid-cols-2">
      <!-- 在线率采样 -->
      <section class="border border-gray-100 rounded-8px bg-white p-12px dark:border-gray-800 dark:bg-gray-800">
        <div class="mb-8px flex items-center justify-between">
          <div>
            <div class="text-xs font-semibold">在线率采样</div>
            <div class="mt-2px text-10px text-gray-400">1h 桶，颜色随在线率和错误数变化</div>
          </div>
          <span class="text-10px text-gray-400">{{ buckets.length }} 点</span>
        </div>
        <div v-if="buckets.length > 0" class="h-80px flex items-end gap-4px overflow-hidden">
          <div
            v-for="b in buckets"
            :key="b.bucketStart"
            class="flex-1 rounded-t-2px"
            :style="{ height: bucketHeight(b), minHeight: b.samples > 0 ? '6px' : '4px', backgroundColor: bucketColor(b) }"
            :title="bucketTitle(b)"
          />
        </div>
        <div v-else class="h-80px flex items-center justify-center rounded-6px bg-gray-100 text-xs text-gray-400 dark:bg-gray-900">
          暂无采样数据
        </div>
      </section>

      <!-- 最近事件 -->
      <section class="border border-gray-100 rounded-8px bg-white p-12px dark:border-gray-800 dark:bg-gray-800">
        <div class="mb-8px flex items-center justify-between">
          <span class="text-xs font-semibold">最近事件</span>
          <span class="text-10px text-gray-400">{{ events.length }}/8</span>
        </div>
        <div v-if="events.length === 0" class="h-80px flex items-center justify-center rounded-6px bg-gray-100 text-xs text-gray-400 dark:bg-gray-900">
          暂无关键事件
        </div>
        <div v-else class="max-h-112px space-y-6px overflow-y-auto pr-4px">
          <div v-for="e in events" :key="e.id" class="flex items-center gap-8px text-xs">
            <NTag size="tiny" :type="eventTagType(e.eventType)" :bordered="false">{{ eventLabel(e.eventType) }}</NTag>
            <span class="flex-1 truncate text-gray-400" :title="e.detail || '-'">{{ e.detail || '-' }}</span>
            <span class="shrink-0 text-10px text-gray-400">{{ fmtTime(e.createDt) }}</span>
          </div>
        </div>
      </section>
    </div>

    <!-- 实时快照 -->
    <section class="mt-12px border border-gray-100 rounded-8px bg-white p-12px dark:border-gray-800 dark:bg-gray-800">
      <div class="mb-8px text-xs font-semibold">实时快照</div>
      <div class="grid grid-cols-2 gap-x-16px gap-y-8px sm:grid-cols-3 xl:grid-cols-6">
        <div v-for="item in snapshotItems" :key="item.label" class="min-w-0">
          <div class="text-10px text-gray-400">{{ item.label }}</div>
          <div class="mt-2px truncate text-xs font-medium tabular-nums" :title="String(item.value)">{{ item.value }}</div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped></style>
