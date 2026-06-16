<script setup lang="tsx">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { fetchCancelTask, fetchChannels, fetchTaskDetail, fetchTaskStats, fetchTasks } from '@/service/api';

defineOptions({ name: 'AsyncTasks' });

// ===== 异步任务（多模态生成任务·AsyncTasks）=====
// 列表/统计 + 详情抽屉自动刷新 + 取消任务（后端 markFailed 幂等终态闸门·自动退冻结 USD）。

const loading = ref(false);
const rows = ref<NexusAI.Task[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(10);
const stats = ref<NexusAI.TaskStats>({});

// 筛选
const statusFilter = ref<string | null>(null);
const modalityFilter = ref<string | null>(null);
const taskIdFilter = ref('');

const statusOptions = [
  { label: '已提交（SUBMITTED）', value: 'SUBMITTED' },
  { label: '处理中（IN_PROGRESS）', value: 'IN_PROGRESS' },
  { label: '成功（SUCCESS）', value: 'SUCCESS' },
  { label: '失败（FAILED）', value: 'FAILED' }
];

const modalityOptions = [
  { label: '图像（image）', value: 'image' },
  { label: '视频（video）', value: 'video' },
  { label: 'Midjourney', value: 'midjourney' },
  { label: '音频（audio）', value: 'audio' }
];

// 渠道 id → 名称（用户/渠道列展示）
const channelNames = ref<Record<number, string>>({});

const RUNNING_STATUSES = ['SUBMITTED', 'IN_PROGRESS'];

function isRunning(status?: string) {
  return Boolean(status && RUNNING_STATUSES.includes(status));
}

function statusTagType(status?: string): 'default' | 'info' | 'success' | 'warning' | 'error' {
  switch (status) {
    case 'SUCCESS':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'IN_PROGRESS':
      return 'info';
    case 'SUBMITTED':
      return 'warning';
    default:
      return 'default';
  }
}

function statusLabel(status?: string) {
  switch (status) {
    case 'SUCCESS':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'IN_PROGRESS':
      return '处理中';
    case 'SUBMITTED':
      return '已提交';
    default:
      return status ?? '-';
  }
}

// 进度文本（如 "45" / "45%"）→ 数值
function progressPercent(progress?: string): number {
  if (!progress) return 0;
  const n = Number.parseFloat(String(progress).replace('%', ''));
  if (Number.isNaN(n)) return 0;
  return Math.max(0, Math.min(100, Math.round(n)));
}

function formatUsd(v?: number | string) {
  // 后端回美元数值/字符串·保留 6 位小数（冻结额度可能很小）
  const n = typeof v === 'string' ? Number(v) : v;
  return typeof n === 'number' && !Number.isNaN(n) ? `$${n.toFixed(6)}` : '-';
}

/** ISO LocalDateTime（2026-06-11T06:17:18）→ 友好显示。 */
function fmtTime(v?: string) {
  return v ? v.replace('T', ' ') : '-';
}

async function loadStats() {
  const { data } = await fetchTaskStats();
  stats.value = data ?? {};
}

async function load() {
  loading.value = true;
  try {
    const { data } = await fetchTasks({
      pageNum: page.value,
      pageSize: pageSize.value,
      status: statusFilter.value || undefined,
      modality: modalityFilter.value || undefined,
      taskId: taskIdFilter.value.trim() || undefined
    });
    rows.value = data?.records ?? [];
    total.value = data?.total ?? 0;
  } finally {
    loading.value = false;
  }
}

async function loadChannels() {
  const { data } = await fetchChannels();
  const map: Record<number, string> = {};
  (data ?? []).forEach(c => {
    if (c.id != null) map[c.id] = c.name ?? `#${c.id}`;
  });
  channelNames.value = map;
}

function channelName(id?: number) {
  if (id == null) return '-';
  return channelNames.value[id] ?? `#${id}`;
}

function refreshOverview() {
  loadStats();
  load();
}

// 筛选变化时回到第一页
function onFilterChange() {
  page.value = 1;
  load();
}

function onPageChange(p: number) {
  page.value = p;
  load();
}

function onPageSizeChange(size: number) {
  pageSize.value = size;
  page.value = 1;
  load();
}

// ===== 自动刷新（同款：有进行中任务且页面可见时 5 秒轮询）=====
const AUTO_REFRESH_INTERVAL = 5000;
const isPageVisible = ref(true);
let listTimer: ReturnType<typeof setInterval> | null = null;

const hasProcessingTasks = computed(() => rows.value.some(t => isRunning(t.status)));

function startListAutoRefresh() {
  if (listTimer) return;
  listTimer = setInterval(() => {
    if (isPageVisible.value) {
      loadStats();
      load();
    }
  }, AUTO_REFRESH_INTERVAL);
}

function stopListAutoRefresh() {
  if (listTimer) {
    clearInterval(listTimer);
    listTimer = null;
  }
}

watch(hasProcessingTasks, has => {
  if (has) startListAutoRefresh();
  else stopListAutoRefresh();
});

function onVisibilityChange() {
  isPageVisible.value = document.visibilityState === 'visible';
}

// ===== 详情抽屉 =====
const showDetail = ref(false);
const detail = ref<NexusAI.Task | null>(null);
const detailAutoRefresh = ref(true);
let detailTimer: ReturnType<typeof setInterval> | null = null;

const submitBodyPretty = computed(() => {
  const body = detail.value?.submitBody;
  if (!body) return '';
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
});

const isVideoResult = computed(() => {
  const url = detail.value?.resultUrl ?? '';
  return /\.(mp4|webm|mov)(\?|$)/i.test(url);
});

async function openDetail(row: NexusAI.Task) {
  detail.value = row;
  showDetail.value = true;
  if (row.taskId) {
    const { data } = await fetchTaskDetail(row.taskId);
    if (data) detail.value = data;
  }
  startDetailAutoRefresh();
}

async function refreshDetail() {
  const id = detail.value?.taskId;
  if (!id) return;
  const { data } = await fetchTaskDetail(id);
  if (data) detail.value = data;
}

function startDetailAutoRefresh() {
  stopDetailAutoRefresh();
  detailTimer = setInterval(() => {
    if (isPageVisible.value && showDetail.value && detailAutoRefresh.value && isRunning(detail.value?.status)) {
      refreshDetail();
    }
  }, AUTO_REFRESH_INTERVAL);
}

function stopDetailAutoRefresh() {
  if (detailTimer) {
    clearInterval(detailTimer);
    detailTimer = null;
  }
}

watch(showDetail, open => {
  if (!open) stopDetailAutoRefresh();
});

// ===== 取消任务 =====
async function cancelTask(task: NexusAI.Task | null) {
  if (!task?.taskId) return;
  const { error } = await fetchCancelTask(task.taskId);
  if (!error) {
    window.$message?.success('已取消任务，冻结额度（$）已退还');
    refreshOverview();
    if (showDetail.value) refreshDetail();
  }
}

function canCancel(status?: string) {
  return isRunning(status);
}

async function copyText(text: string) {
  await navigator.clipboard.writeText(text);
  window.$message?.success('已复制');
}

const columns: DataTableColumns<NexusAI.Task> = [
  {
    title: '任务',
    key: 'taskId',
    minWidth: 240,
    render: row => (
      <div class="flex flex-col gap-2px">
        <div class="flex items-center gap-6px">
          <SvgIcon icon="carbon:video" class="shrink-0 text-14px text-gray-400" />
          <NText code class="max-w-260px truncate text-12px">
            {row.taskId ?? '-'}
          </NText>
        </div>
        <NText depth="3" class="pl-20px text-12px">
          {row.model || '-'}
          {row.action ? ` · ${row.action}` : ''}
        </NText>
      </div>
    )
  },
  {
    title: '用户 / 渠道',
    key: 'channelId',
    width: 160,
    render: row => (
      <div class="flex flex-col gap-2px text-12px">
        <div class="flex items-center gap-4px">
          <SvgIcon icon="carbon:user" class="text-12px text-gray-400" />
          <span>{row.projectCode ?? '-'}</span>
        </div>
        <div class="flex items-center gap-4px">
          <SvgIcon icon="carbon:bare-metal-server" class="text-12px text-gray-400" />
          <NText depth="3">{channelName(row.channelId)}</NText>
        </div>
      </div>
    )
  },
  {
    title: '状态',
    key: 'status',
    width: 150,
    render: row => {
      const pct = progressPercent(row.progress);
      return (
        <div class="flex flex-col gap-4px">
          <NTag size="small" type={statusTagType(row.status)} class="w-fit">
            {isRunning(row.status) ? <SvgIcon icon="carbon:rotate" class="mr-4px animate-spin text-12px" /> : null}
            {statusLabel(row.status)}
          </NTag>
          {isRunning(row.status) && pct > 0 ? (
            <NProgress type="line" percentage={pct} height={6} show-indicator={false} processing />
          ) : null}
        </div>
      );
    }
  },
  {
    title: '参数',
    key: 'modality',
    width: 140,
    render: row => (
      <div class="flex flex-col gap-2px text-12px">
        <span>{row.modality ?? '-'}</span>
        <NText depth="3">冻结 {formatUsd(row.frozenUsd)}</NText>
      </div>
    )
  },
  {
    title: '时间',
    key: 'createDt',
    width: 180,
    render: row => (
      <div class="flex flex-col gap-2px text-12px">
        <div class="flex items-center gap-4px">
          <SvgIcon icon="carbon:time" class="text-12px text-gray-400" />
          <span>{fmtTime(row.createDt)}</span>
        </div>
        {row.status === 'SUCCESS' || row.status === 'FAILED' ? (
          <div class="flex items-center gap-4px">
            <SvgIcon icon="carbon:checkmark-outline" class="text-12px text-emerald-500" />
            <NText depth="3">{fmtTime(row.updateDt)}</NText>
          </div>
        ) : null}
      </div>
    )
  },
  {
    title: '操作',
    key: 'actions',
    width: 110,
    render: row => (
      <NSpace size={4}>
        <NTooltip>
          {{
            trigger: () => (
              <NButton text size="small" onClick={() => openDetail(row)}>
                <SvgIcon icon="carbon:view" class="text-16px" />
              </NButton>
            ),
            default: () => '查看详情'
          }}
        </NTooltip>
        {canCancel(row.status) ? (
          <NPopconfirm onPositiveClick={() => cancelTask(row)}>
            {{
              trigger: () => (
                <NButton text size="small" type="error">
                  <SvgIcon icon="carbon:close-outline" class="text-16px" />
                </NButton>
              ),
              default: () => '确定取消该任务？冻结的额度（$）将全额退还。'
            }}
          </NPopconfirm>
        ) : null}
      </NSpace>
    )
  }
];

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange);
  loadChannels();
  refreshOverview();
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange);
  stopListAutoRefresh();
  stopDetailAutoRefresh();
});
</script>

<template>
  <NSpace vertical :size="16">
    <!-- 顶部统计卡（同款 4 卡） -->
    <NGrid :cols="4" :x-gap="16" responsive="screen" item-responsive>
      <NGridItem span="4 m:2 l:1">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center justify-between">
            <div>
              <NText depth="3" class="text-12px">总任务数</NText>
              <div class="text-24px font-600">{{ stats.total ?? 0 }}</div>
            </div>
            <SvgIcon icon="carbon:task" class="text-28px text-gray-300" />
          </div>
        </NCard>
      </NGridItem>
      <NGridItem span="4 m:2 l:1">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center justify-between">
            <div>
              <NText depth="3" class="text-12px">处理中</NText>
              <div class="text-24px text-blue-500 font-600">{{ stats.running ?? 0 }}</div>
            </div>
            <SvgIcon icon="carbon:rotate" class="text-28px text-blue-300" :class="{ 'animate-spin': (stats.running ?? 0) > 0 }" />
          </div>
        </NCard>
      </NGridItem>
      <NGridItem span="4 m:2 l:1">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center justify-between">
            <div>
              <NText depth="3" class="text-12px">已完成</NText>
              <div class="text-24px text-emerald-500 font-600">{{ stats.succeeded ?? 0 }}</div>
            </div>
            <SvgIcon icon="carbon:checkmark-outline" class="text-28px text-emerald-300" />
          </div>
        </NCard>
      </NGridItem>
      <NGridItem span="4 m:2 l:1">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center justify-between">
            <div>
              <NText depth="3" class="text-12px">已失败</NText>
              <div class="text-24px text-red-500 font-600">{{ stats.failed ?? 0 }}</div>
            </div>
            <SvgIcon icon="carbon:close-outline" class="text-28px text-red-300" />
          </div>
        </NCard>
      </NGridItem>
    </NGrid>

    <!-- 任务表格 -->
    <NCard title="异步任务" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <NSpace align="center" :size="8">
          <NSelect
            v-model:value="statusFilter"
            :options="statusOptions"
            placeholder="全部状态"
            clearable
            size="small"
            class="w-180px"
            @update:value="onFilterChange"
          />
          <NSelect
            v-model:value="modalityFilter"
            :options="modalityOptions"
            placeholder="全部模态"
            clearable
            size="small"
            class="w-150px"
            @update:value="onFilterChange"
          />
          <NInput
            v-model:value="taskIdFilter"
            placeholder="任务 ID 搜索"
            clearable
            size="small"
            class="w-200px"
            @keyup.enter="onFilterChange"
            @clear="onFilterChange"
          />
          <NButton size="small" :loading="loading" @click="refreshOverview">
            <SvgIcon icon="carbon:renew" class="mr-4px" />
            刷新
          </NButton>
        </NSpace>
      </template>

      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :bordered="false"
        size="small"
        :row-key="row => row.id ?? row.taskId"
        :scroll-x="1080"
      >
        <template #empty>
          <NEmpty description="暂无异步任务" />
        </template>
      </NDataTable>

      <div class="mt-3 flex justify-end">
        <NPagination
          v-model:page="page"
          v-model:page-size="pageSize"
          :item-count="total"
          show-quick-jumper
          show-size-picker
          :page-sizes="[10, 20, 50]"
          @update:page="onPageChange"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </NCard>

    <!-- 任务详情抽屉（同款：固定头 + 滚动内容 + 自动刷新 + 取消） -->
    <NDrawer v-model:show="showDetail" :width="560">
      <NDrawerContent closable>
        <template #header>
          <div class="flex items-center gap-8px">
            <span>任务详情</span>
            <NTag v-if="detail" size="small" :type="statusTagType(detail.status)">
              {{ statusLabel(detail.status) }}
            </NTag>
            <NTag v-if="detail?.modality" size="small" type="info">{{ detail.modality }}</NTag>
          </div>
        </template>

        <NSpace v-if="detail" vertical :size="16">
          <!-- 头部信息 -->
          <div class="flex flex-wrap items-center gap-x-16px gap-y-4px text-12px">
            <NText code class="text-12px">{{ detail.taskId }}</NText>
            <NText depth="3">创建 {{ fmtTime(detail.createDt) }}</NText>
            <NSwitch v-model:value="detailAutoRefresh" size="small">
              <template #checked>自动刷新</template>
              <template #unchecked>自动刷新</template>
            </NSwitch>
          </div>

          <!-- 进行中进度 -->
          <NProgress
            v-if="isRunning(detail.status)"
            type="line"
            :percentage="progressPercent(detail.progress)"
            processing
            indicator-placement="inside"
            :height="16"
          />

          <!-- 错误信息 -->
          <NAlert v-if="detail.failReason" type="error" title="失败原因">
            {{ detail.failReason }}
          </NAlert>

          <!-- 产物结果 -->
          <NCard v-if="detail.resultUrl" size="small" title="生成结果" :bordered="true">
            <video v-if="isVideoResult" :src="detail.resultUrl" controls class="w-full rounded-6px" />
            <div class="mt-8px flex items-center gap-8px">
              <NButton text tag="a" :href="detail.resultUrl" target="_blank" type="primary" size="small">
                打开产物链接
              </NButton>
              <NButton text size="small" @click="copyText(detail.resultUrl!)">
                <SvgIcon icon="carbon:copy" class="mr-2px" />
                复制链接
              </NButton>
            </div>
          </NCard>
          <NAlert v-else-if="detail.status === 'SUCCESS'" type="warning">任务成功但未回填产物 URL。</NAlert>

          <!-- 执行信息网格 -->
          <NDescriptions label-placement="left" bordered size="small" :column="2">
            <NDescriptionsItem label="模型">{{ detail.model || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="操作">{{ detail.action || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="项目">{{ detail.projectCode ?? '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="令牌 ID">{{ detail.tokenId ?? '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="渠道">{{ channelName(detail.channelId) }}</NDescriptionsItem>
            <NDescriptionsItem label="Key ID">{{ detail.keyId ?? '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="冻结额度($)">{{ formatUsd(detail.frozenUsd) }}</NDescriptionsItem>
            <NDescriptionsItem label="进度">{{ detail.progress || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="上游任务 ID" :span="2">
              <NText code class="text-12px">{{ detail.upstreamTaskId || '-' }}</NText>
            </NDescriptionsItem>
            <NDescriptionsItem label="创建时间">{{ fmtTime(detail.createDt) }}</NDescriptionsItem>
            <NDescriptionsItem label="更新时间">{{ fmtTime(detail.updateDt) }}</NDescriptionsItem>
          </NDescriptions>

          <!-- 原始请求体 -->
          <NCard v-if="submitBodyPretty" size="small" :bordered="true">
            <template #header>
              <div class="flex items-center justify-between">
                <span>提交请求体</span>
                <NButton text size="small" @click="copyText(submitBodyPretty)">
                  <SvgIcon icon="carbon:copy" class="mr-2px" />
                  复制
                </NButton>
              </div>
            </template>
            <NCode :code="submitBodyPretty" language="json" class="max-h-320px overflow-auto text-12px" />
          </NCard>
        </NSpace>

        <template #footer>
          <NPopconfirm v-if="detail && canCancel(detail.status)" @positive-click="cancelTask(detail)">
            <template #trigger>
              <NButton type="error" ghost size="small">取消任务</NButton>
            </template>
            确定取消该任务？冻结的额度（$）将全额退还。
          </NPopconfirm>
        </template>
      </NDrawerContent>
    </NDrawer>
  </NSpace>
</template>
