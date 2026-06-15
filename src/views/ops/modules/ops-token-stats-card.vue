<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchPerformance } from '@/service/api';

defineOptions({ name: 'OpsTokenStatsCard' });

/**
 * 模型 Token 统计卡（sub2api OpsOpenAITokenStatsCard 对位·去 OpenAI 专属化为全模型）：
 * 按模型聚合 请求数 / 吞吐(tokens/s) / 平均首字 / 输出 tokens / 平均耗时，Top N 可调。
 */
const props = defineProps<{ refreshToken: number }>();

type DashWindow = 'day' | 'week' | 'month';
const windowVal = ref<DashWindow>('day');
const limit = ref(10);
const loading = ref(false);
const rows = ref<NexusAI.PerfStat[]>([]);
/** 视图模式（sub2api Top N / 分页对位·分页为全量拉取后客户端切页） */
const viewMode = ref<'top' | 'page'>('top');
const page = ref(1);
const pageSize = ref(10);

const WINDOW_OPTIONS = [
  { label: '今日', value: 'day' },
  { label: '本周', value: 'week' },
  { label: '本月', value: 'month' }
];
const LIMIT_OPTIONS = [10, 20, 50].map(n => ({ label: `Top ${n}`, value: n }));
const VIEW_MODE_OPTIONS = [
  { label: 'Top N', value: 'top' },
  { label: '分页', value: 'page' }
];

const pagedRows = computed(() => {
  if (viewMode.value === 'top') return rows.value;
  const start = (page.value - 1) * pageSize.value;
  return rows.value.slice(start, start + pageSize.value);
});

function fmtMs(v?: number) {
  if (v == null) return '-';
  return v >= 1000 ? `${(v / 1000).toFixed(1)}s` : `${Math.round(v)}ms`;
}

function fmtCount(v?: number) {
  const n = Number(v ?? 0);
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

async function load() {
  loading.value = true;
  const fetchLimit = viewMode.value === 'top' ? limit.value : 500;
  const { data, error } = await fetchPerformance('model', windowVal.value, fetchLimit);
  if (!error && data) rows.value = data;
  loading.value = false;
}

watch([windowVal, limit, viewMode], () => {
  page.value = 1;
  load();
});
watch(
  () => props.refreshToken,
  () => load()
);

onMounted(load);
</script>

<template>
  <NCard title="模型 Token 统计" :bordered="false" size="small" class="card-wrapper">
    <template #header-extra>
      <div class="flex items-center gap-8px">
        <NSelect v-model:value="windowVal" size="small" :options="WINDOW_OPTIONS" class="w-90px" />
        <NSelect v-model:value="viewMode" size="small" :options="VIEW_MODE_OPTIONS" class="w-90px" />
        <NSelect v-if="viewMode === 'top'" v-model:value="limit" size="small" :options="LIMIT_OPTIONS" class="w-90px" />
        <NButton size="small" quaternary :loading="loading" @click="load">
          <template #icon>
            <SvgIcon icon="carbon:renew" />
          </template>
        </NButton>
      </div>
    </template>
    <NSpin :show="loading">
      <NEmpty v-if="rows.length === 0" description="窗口内无样本" class="py-24px" />
      <NTable v-else size="small" :bordered="false" :single-line="false">
        <thead>
          <tr>
            <th>模型</th>
            <th class="w-90px">请求数</th>
            <th class="w-110px">吞吐（tok/s）</th>
            <th class="w-100px">平均首字</th>
            <th class="w-110px">输出 tokens</th>
            <th class="w-100px">平均耗时</th>
            <th class="w-70px">置信度</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in pagedRows" :key="r.key">
            <td class="font-mono text-xs">{{ r.key }}</td>
            <td class="tabular-nums">{{ fmtCount(r.requestCount) }}</td>
            <td class="tabular-nums">{{ r.throughput != null ? Number(r.throughput).toFixed(1) : '-' }}</td>
            <td class="tabular-nums">{{ fmtMs(r.avgTtftMs) }}</td>
            <td class="tabular-nums">{{ fmtCount(r.tokensCount) }}</td>
            <td class="tabular-nums">{{ fmtMs(r.avgDurationMs) }}</td>
            <td>
              <NTag
                size="tiny"
                :bordered="false"
                :type="r.confidence === 'high' ? 'success' : r.confidence === 'medium' ? 'warning' : 'default'"
              >
                {{ r.confidence ?? '-' }}
              </NTag>
            </td>
          </tr>
        </tbody>
      </NTable>
      <div v-if="viewMode === 'page' && rows.length > 0" class="mt-12px flex items-center justify-between">
        <span class="text-xs text-gray-400">共 {{ rows.length }} 个模型</span>
        <NPagination
          v-model:page="page"
          v-model:page-size="pageSize"
          :item-count="rows.length"
          :page-sizes="[10, 20, 50]"
          show-size-picker
        />
      </div>
    </NSpin>
  </NCard>
</template>

<style scoped></style>
