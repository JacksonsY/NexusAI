<script setup lang="tsx">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import {
  NAlert,
  NButton,
  NCard,
  NCode,
  NDataTable,
  NDatePicker,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NGrid,
  NGridItem,
  NInput,
  NModal,
  NPagination,
  NPopselect,
  NProgress,
  NSelect,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  NTooltip
} from 'naive-ui';
import type { DataTableColumns, DataTableFilterState } from 'naive-ui';
import {
  fetchActiveRequests,
  fetchRequestBody,
  fetchRequestCurl,
  fetchRequestLogs,
  fetchUsageAggregation,
  fetchUsageHeatmap
} from '@/service/api/usage';
import type { RequestLogFilters } from '@/service/api/usage';
import { fetchFailovers, fetchRequestDetail } from '@/service/api/observe';
import { fetchChannels } from '@/service/api/channel';
import { fetchProjects } from '@/service/api/project';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { localStg } from '@/utils/storage';
import ActivityHeatmapCard from './modules/activity-heatmap-card.vue';
import ConversationView from './modules/conversation-view.vue';
import JsonPanel from './modules/json-panel.vue';
import RequestTimeline from './modules/request-timeline.vue';
import IntervalTimelineCard from './modules/interval-timeline-card.vue';
import UsageAggTable from './modules/usage-agg-table.vue';
import ElapsedTime from './modules/elapsed-time.vue';
import { fmtCount, fmtMs, fmtTokens, fmtUsd, formatClientFamily, num } from './composables/format';

defineOptions({ name: 'Usage' });

/** 全局自动刷新间隔（GLOBAL_AUTO_REFRESH_INTERVAL=3s） */
const AUTO_REFRESH_INTERVAL = 3000;
/** 活跃请求轮询间隔（AUTO_REFRESH_INTERVAL=1s·有活跃行时恒走） */
const ACTIVE_POLL_INTERVAL = 1000;
/** 分析面板自动刷新最小间隔（ADMIN_ANALYTICS_REFRESH_INTERVAL=60s） */
const ANALYTICS_REFRESH_INTERVAL = 60_000;
const PAGE_SIZE_KEY = 'usage-page-size';
const COLUMNS_KEY = 'usage-visible-columns-v2';
/** 隐藏 unknown 持久化（usage-hide-unknown-records） */
const HIDE_UNKNOWN_KEY = 'usage-hide-unknown-records';

// ===== 筛选状态（时间范围 + 通用搜索在操作栏，其余筛选内嵌列头漏斗）=====
const dateRange = ref<[number, number] | null>(null);
const search = ref('');
const hideUnknown = ref(localStg.get(HIDE_UNKNOWN_KEY) ?? false);
const filters = reactive<{
  projectCode: string | null;
  channelId: number | null;
  model: string | null;
  apiFormat: string | null;
  resultKey: string | null;
  clientFamily: string | null;
}>({
  projectCode: null,
  channelId: null,
  model: null,
  apiFormat: null,
  resultKey: null,
  clientFamily: null
});

const rangeShortcuts: Record<string, [number, number]> = {
  近1小时: [Date.now() - 3600_000, Date.now()],
  今天: [new Date(new Date().setHours(0, 0, 0, 0)).getTime(), Date.now()],
  近24小时: [Date.now() - 86400_000, Date.now()],
  近7天: [Date.now() - 7 * 86400_000, Date.now()],
  近30天: [Date.now() - 30 * 86400_000, Date.now()]
};

/** 状态筛选（status：流式/标准/活跃/失败/已取消/发生重试/发生转移） */
const statusFilterOptions = [
  { label: '流式', value: 'stream' },
  { label: '标准', value: 'standard' },
  { label: '活跃', value: 'active' },
  { label: '失败', value: 'failed' },
  { label: '已取消', value: 'cancelled' },
  { label: '发生重试', value: 'hasRetry' },
  { label: '发生转移', value: 'hasFallback' }
];

/** API 格式筛选（api_format） */
const apiFormatOptions = [
  { label: 'openai:chat', value: 'openai:chat' },
  { label: 'openai:embeddings', value: 'openai:embeddings' },
  { label: 'openai:images', value: 'openai:images' },
  { label: 'openai:rerank', value: 'openai:rerank' },
  { label: 'claude:messages', value: 'claude:messages' },
  { label: 'gemini:generateContent', value: 'gemini:generateContent' }
];

function fmtLocal(ts: number): string {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function buildFilters(): RequestLogFilters {
  const f: RequestLogFilters = {};
  if (dateRange.value) {
    f.beginTime = fmtLocal(dateRange.value[0]);
    f.endTime = fmtLocal(dateRange.value[1]);
  }
  if (search.value.trim()) f.search = search.value.trim();
  if (hideUnknown.value) f.hideUnknown = true;
  if (filters.projectCode) f.projectCode = filters.projectCode;
  if (filters.channelId != null) f.channelId = filters.channelId;
  if (filters.model) f.model = filters.model;
  if (filters.apiFormat) f.apiFormat = filters.apiFormat;
  if (filters.clientFamily) f.clientFamily = filters.clientFamily;
  if (filters.resultKey === 'failed') f.success = false;
  if (filters.resultKey === 'stream') f.streamMode = true;
  if (filters.resultKey === 'standard') f.streamMode = false;
  if (filters.resultKey === 'cancelled') f.statusFilter = 'cancelled';
  if (filters.resultKey === 'hasRetry') f.statusFilter = 'hasRetry';
  if (filters.resultKey === 'hasFallback') f.statusFilter = 'hasFallback';
  // resultKey === 'active' 不传后端：活跃行来自进程内注册表（displayRecords 切换数据源）
  return f;
}

// ===== 渠道映射（id → name· 正例：批量映射不 JOIN）=====
const channelMap = ref<Record<string, string>>({});
async function loadChannels() {
  const { data, error } = await fetchChannels();
  if (!error && data) {
    const map: Record<string, string> = {};
    for (const c of data as NexusAI.Channel[]) {
      if (c.id != null) map[String(c.id)] = c.name || `#${c.id}`;
    }
    channelMap.value = map;
  }
}
const channelOptions = computed(() =>
  Object.entries(channelMap.value).map(([id, name]) => ({ label: name, value: Number(id) }))
);

function channelLabel(channelId?: number | string | null): string {
  if (channelId == null) return '-';
  return channelMap.value[String(channelId)] || `渠道 #${channelId}`;
}

// ===== 用量分析面板（热图 + 间隔时间线 + 模型/渠道/API格式三聚合表）=====
const heatmap = ref<NexusAI.UsageHeatmap | null>(null);
const heatmapLoading = ref(false);
async function loadHeatmap() {
  heatmapLoading.value = true;
  const { data, error } = await fetchUsageHeatmap();
  if (!error) heatmap.value = data;
  heatmapLoading.value = false;
}

const modelAgg = ref<NexusAI.UsageAggItem[]>([]);
const channelAgg = ref<NexusAI.UsageAggItem[]>([]);
const apiFormatAgg = ref<NexusAI.UsageAggItem[]>([]);
const aggLoading = ref(false);
let lastAnalyticsAt = 0;

async function loadAggregations(force = false) {
  if (!force && Date.now() - lastAnalyticsAt < ANALYTICS_REFRESH_INTERVAL) return;
  lastAnalyticsAt = Date.now();
  aggLoading.value = true;
  const base = buildFilters();
  const [m, c, a] = await Promise.all([
    fetchUsageAggregation({ ...base, groupBy: 'model', limit: 20 }),
    fetchUsageAggregation({ ...base, groupBy: 'channel', limit: 20 }),
    fetchUsageAggregation({ ...base, groupBy: 'apiFormat', limit: 20 })
  ]);
  if (!m.error && m.data) modelAgg.value = m.data;
  if (!c.error && c.data) channelAgg.value = c.data;
  if (!a.error && a.data) apiFormatAgg.value = a.data;
  aggLoading.value = false;
}

/** 摘要统计 4 卡（sub2api UsageStatsCards 对位·由模型聚合前端求和） */
const summaryStats = computed(() => {
  const rows = modelAgg.value;
  const requests = rows.reduce((s, i) => s + num(i.requests), 0);
  const promptTokens = rows.reduce((s, i) => s + num(i.promptTokens), 0);
  const completionTokens = rows.reduce((s, i) => s + num(i.completionTokens), 0);
  const cacheTokens = rows.reduce((s, i) => s + num(i.cacheTokens), 0);
  const tokens = rows.reduce((s, i) => s + num(i.totalTokens), 0);
  const costUsd = rows.reduce((s, i) => s + num(i.costUsd), 0);
  const durWeighted = rows.reduce((s, i) => s + num(i.avgDurationMs) * num(i.requests), 0);
  return {
    requests,
    tokens,
    promptTokens,
    completionTokens,
    cacheTokens,
    costUsd,
    avgDurationMs: requests > 0 ? Math.round(durWeighted / requests) : 0
  };
});

// ===== 导出 Excel（sub2api exportToExcel 对位：带筛选分页拉全量 + 进度弹窗 + 可取消）=====
const exporting = ref(false);
const exportProgress = reactive({ show: false, current: 0, total: 0, percent: 0 });
let exportAborted = false;

async function exportToExcel() {
  if (exporting.value) return;
  exporting.value = true;
  exportAborted = false;
  exportProgress.show = true;
  exportProgress.current = 0;
  exportProgress.percent = 0;
  exportProgress.total = pagination.total;
  try {
    const XLSX = await import('xlsx');
    const headers = [
      '时间', '请求ID', '项目', '渠道', '模型', '目标模型', '模态', 'API格式', '客户端', '流式',
      '输入Tokens', '输出Tokens', '缓存Tokens', '总Tokens', '费用(USD)',
      'HTTP状态', '错误码', '重试', '故障转移', '首字ms', '耗时ms', '客户端IP'
    ];
    const aoa: (string | number)[][] = [headers];
    const base = buildFilters();
    const pageSize = 200;
    let page = 1;
    let total = exportProgress.total;
    for (;;) {
      if (exportAborted) break;
      const { data, error } = await fetchRequestLogs({ ...base, pageNum: page, pageSize });
      if (error || !data) break;
      if (page === 1) {
        total = Number(data.total || 0);
        exportProgress.total = total;
      }
      const rows = data.records ?? [];
      for (const r of rows) {
        aoa.push([
          String(r.createDt ?? '').replace('T', ' '),
          r.requestId ?? '',
          projectLabel(r.projectCode as string),
          channelLabel(r.channelId as number),
          r.model ?? '',
          r.targetModel ?? '',
          r.modality ?? '',
          r.apiFormat ?? '',
          r.clientFamily ?? '',
          r.streamMode ? '是' : '否',
          num(r.promptTokens),
          num(r.completionTokens),
          num(r.cacheTokens),
          num(r.totalTokens),
          num(r.costUsd),
          num(r.httpStatus),
          r.errorCode ?? '',
          num(r.retryCount),
          num(r.fallbackCount),
          num(r.ttftMs),
          num(r.durationMs),
          r.clientIp ?? ''
        ]);
      }
      exportProgress.current += rows.length;
      exportProgress.percent = total > 0 ? Math.min(100, Math.round((exportProgress.current / total) * 100)) : 100;
      if (exportProgress.current >= total || rows.length < pageSize) break;
      page += 1;
    }
    if (!exportAborted && aoa.length > 1) {
      const ws = XLSX.utils.aoa_to_sheet(aoa);
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Usage');
      const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
      XLSX.writeFile(wb, `usage-${stamp}.xlsx`);
      window.$message?.success(`已导出 ${exportProgress.current} 条记录`);
    }
  } finally {
    exporting.value = false;
    exportProgress.show = false;
  }
}

function cancelExport() {
  exportAborted = true;
  exportProgress.show = false;
}

/** 间隔时间线小时窗（随时间范围预设联动·getIntervalTimelineHours·钳 1~720） */
const intervalHours = computed(() => {
  if (!dateRange.value) return 24;
  const hours = Math.ceil((dateRange.value[1] - dateRange.value[0]) / 3600_000);
  return Math.min(720, Math.max(1, hours));
});
const intervalTimelineTitle = computed(() => {
  const h = intervalHours.value;
  const win = h === 24 ? '最近24小时' : h % 24 === 0 ? `最近${h / 24}天` : `最近${h}小时`;
  return `请求间隔时间线（${win}）`;
});

// ===== 活跃请求（/usage/active：进程内注册表·1s 轮询·完成后落库行接棒）=====
const activeRows = ref<NexusAI.ActiveRequest[]>([]);
let knownActiveIds = new Set<string>();

async function loadActive() {
  const { data, error } = await fetchActiveRequests();
  if (error || !data) return;
  const nextIds = new Set(data.map(a => a.requestId || ''));
  // 有活跃行消失 = 刚完成 → 拉一次列表接棒（落库日志行替代注册表行）
  const finished = [...knownActiveIds].some(id => !nextIds.has(id));
  knownActiveIds = nextIds;
  activeRows.value = data;
  syncActivePoll();
  if (finished) loadRecords();
}

/** 注册表行 → 表格行（套用当前筛选·liveStatus 标记驱动脉冲徽章与活计时） */
const activeAsRows = computed<NexusAI.RequestLog[]>(() => {
  return activeRows.value
    .filter(a => {
      if (filters.projectCode && a.projectCode !== filters.projectCode) return false;
      if (filters.channelId != null && num(a.channelId) !== filters.channelId) return false;
      if (filters.model && a.model !== filters.model) return false;
      if (filters.apiFormat && a.apiFormat !== filters.apiFormat) return false;
      if (filters.clientFamily && a.clientFamily !== filters.clientFamily) return false;
      if (filters.resultKey === 'stream' && !a.streamMode) return false;
      if (filters.resultKey === 'standard' && a.streamMode) return false;
      if (['failed', 'cancelled', 'hasRetry', 'hasFallback'].includes(filters.resultKey || '')) return false;
      const kw = search.value.trim().toLowerCase();
      if (kw && !`${a.requestId} ${a.model}`.toLowerCase().includes(kw)) return false;
      return true;
    })
    .map(a => ({
      requestId: a.requestId,
      projectCode: a.projectCode,
      channelId: a.channelId,
      keyId: a.keyId,
      model: a.model,
      targetModel: a.targetModel,
      modality: a.modality,
      streamMode: a.streamMode,
      apiFormat: a.apiFormat,
      endpointApiFormat: a.endpointApiFormat,
      clientIp: a.clientIp,
      userAgent: a.userAgent,
      clientFamily: a.clientFamily,
      httpStatus: a.httpStatus,
      ttftMs: a.ttftMs,
      createDt: a.createDt,
      liveStatus: a.status,
      elapsedMs: num(a.elapsedMs)
    }));
});

// ===== 列表 =====
const loading = ref(false);
const records = ref<NexusAI.RequestLog[]>([]);
const pagination = reactive({
  page: 1,
  pageSize: localStg.get(PAGE_SIZE_KEY) || 20,
  total: 0
});
let requestSeq = 0;

async function loadRecords() {
  const seq = (requestSeq += 1);
  loading.value = true;
  const { data, error } = await fetchRequestLogs({
    ...buildFilters(),
    pageNum: pagination.page,
    pageSize: pagination.pageSize
  });
  if (seq !== requestSeq) return; // 过期响应丢弃（requestId 防竞态）
  if (!error && data) {
    records.value = data.records || [];
    pagination.total = Number(data.total || 0);
  }
  loading.value = false;
}

/** 表格数据源：活跃筛选=纯注册表行；第一页=活跃行置顶合并（去重·活跃行在列表内） */
const displayRecords = computed<NexusAI.RequestLog[]>(() => {
  if (filters.resultKey === 'active') return activeAsRows.value;
  if (pagination.page === 1 && activeAsRows.value.length) {
    const landed = new Set(records.value.map(r => r.requestId));
    return [...activeAsRows.value.filter(a => !landed.has(a.requestId)), ...records.value];
  }
  return records.value;
});

function reload() {
  pagination.page = 1;
  loadRecords();
  loadAggregations(true);
}

function refreshAll() {
  loadRecords();
  loadActive();
  loadAggregations();
}

// ===== 表格列（列序：时间/项目/模型/渠道/API格式/类型/Tokens/费用/性能/客户端/IP/UA）=====
function isSuccess(row: NexusAI.RequestLog): boolean {
  return (row.httpStatus ?? 0) < 400;
}

function isCancelled(row: NexusAI.RequestLog): boolean {
  return row.usageSource === 'client-abort';
}

/** 输出速率 tokens/s（formatOutputRate：输出 tokens ÷ 生成耗时） */
function outputRate(row: NexusAI.RequestLog): string {
  const out = num(row.completionTokens);
  const dur = num(row.durationMs);
  if (!out || !dur) return '-';
  const genMs = num(row.ttftMs) > 0 && num(row.ttftMs) < dur ? dur - num(row.ttftMs) : dur;
  return `${(out / (genMs / 1000)).toFixed(1)} tok/s`;
}

function fmtTimePair(dt?: string): [string, string] {
  if (!dt) return ['-', ''];
  const s = dt.replace('T', ' ');
  return [s.slice(11, 19) || s, s.slice(0, 10)];
}

function startedAtMs(row: NexusAI.RequestLog): number | null {
  if (!row.createDt) return null;
  const t = new Date(row.createDt).getTime();
  return Number.isNaN(t) ? null : t;
}

/** 类型徽章（优先级：失败 > 等待中 > 传输中 > 已取消 > 流式/标准） */
function renderStatusBadge(row: NexusAI.RequestLog) {
  if (row.liveStatus === 'pending') {
    return (
      <NTag size="small" type="default" bordered class="animate-pulse">
        等待中
      </NTag>
    );
  }
  if (row.liveStatus === 'streaming') {
    return (
      <NTag size="small" type="info" bordered class="animate-pulse">
        传输中
      </NTag>
    );
  }
  if (!isSuccess(row)) {
    return (
      <div class="leading-tight">
        <NTag size="small" type="error" bordered={false}>
          失败
        </NTag>
        <div class="mt-0.5 text-xs text-gray-400">HTTP {row.httpStatus ?? '-'}</div>
      </div>
    );
  }
  if (isCancelled(row)) {
    return (
      <NTag size="small" type="warning" bordered>
        已取消
      </NTag>
    );
  }
  return (
    <NTag size="small" type={row.streamMode ? 'info' : 'default'} bordered={false}>
      {row.streamMode ? '流式' : '标准'}
    </NTag>
  );
}

/** 列头筛选项：项目（全量项目目录）/模型 从数据萃取（available*） */
const projectMap = ref<Record<string, string>>({});
async function loadProjects() {
  const { data, error } = await fetchProjects();
  if (!error && data) {
    const map: Record<string, string> = {};
    for (const pj of data as NexusAI.Project[]) {
      if (pj.projectCode) map[pj.projectCode] = pj.name || pj.projectCode;
    }
    projectMap.value = map;
  }
}
function projectLabel(code?: string | null): string {
  if (!code) return '-';
  return projectMap.value[code] || code;
}
const projectFilterOptions = computed(() =>
  Object.entries(projectMap.value).map(([code, name]) => ({ label: name, value: code }))
);

const modelFilterOptions = computed(() => {
  const set = new Set<string>();
  for (const item of modelAgg.value) if (item.key) set.add(item.key);
  for (const r of records.value) if (r.model) set.add(r.model);
  if (filters.model) set.add(filters.model);
  return [...set].sort().map(v => ({ label: v, value: v }));
});

const clientFamilyFilterOptions = computed(() => {
  const set = new Set<string>();
  for (const r of records.value) if (r.clientFamily) set.add(r.clientFamily);
  for (const a of activeRows.value) if (a.clientFamily) set.add(a.clientFamily);
  if (filters.clientFamily) set.add(filters.clientFamily);
  return [...set].sort().map(v => ({ label: formatClientFamily(v), value: v }));
});

/** 列头漏斗筛选（remote·SortableTableHead 内嵌 TableFilterMenu）。 */
function onFiltersChange(state: DataTableFilterState) {
  const single = (v: unknown): string | null => {
    const raw = Array.isArray(v) ? v[0] : v;
    return raw == null || raw === '' ? null : String(raw);
  };
  filters.projectCode = single(state.projectCode);
  filters.channelId = single(state.channelId) == null ? null : Number(single(state.channelId));
  filters.model = single(state.model);
  filters.apiFormat = single(state.apiFormat);
  filters.resultKey = single(state.status);
  filters.clientFamily = single(state.clientFamily);
  reload();
}

/** 全部列定义（管理员列序·筛选内嵌列头·无排序） */
const allColumns = computed<DataTableColumns<NexusAI.RequestLog>>(() => [
  {
    title: '时间',
    key: 'createDt',
    width: 100,
    render: row => {
      const [t, d] = fmtTimePair(row.createDt);
      return (
        <div class="leading-tight">
          <div class="text-sm tabular-nums">{t}</div>
          <div class="text-xs text-gray-400 tabular-nums">{d}</div>
        </div>
      );
    }
  },
  {
    title: '项目',
    key: 'projectCode',
    width: 110,
    filter: true,
    filterMultiple: false,
    filterOptionValue: filters.projectCode,
    filterOptions: projectFilterOptions.value,
    render: row => <span class="text-xs">{projectLabel(row.projectCode as string)}</span>
  },
  {
    title: '模型',
    key: 'model',
    minWidth: 160,
    filter: true,
    filterMultiple: false,
    filterOptionValue: filters.model,
    filterOptions: modelFilterOptions.value,
    render: row => {
      const mapped = row.targetModel && row.targetModel !== row.model;
      return (
        <div class="leading-tight">
          <div class="flex items-center gap-1 text-sm">
            <span class="font-medium">{row.model || '-'}</span>
            {mapped ? <span class="text-xs text-gray-400">→</span> : null}
          </div>
          {mapped ? <div class="text-xs text-gray-400">{row.targetModel}</div> : null}
        </div>
      );
    }
  },
  {
    title: '渠道',
    key: 'channelId',
    width: 140,
    filter: true,
    filterMultiple: false,
    filterOptionValue: filters.channelId == null ? null : String(filters.channelId),
    filterOptions: channelOptions.value.map(o => ({ label: o.label, value: String(o.value) })),
    render: row => (
      <div class="leading-tight">
        <div class="flex items-center gap-1 text-xs">
          <span class="truncate">{channelLabel(row.channelId)}</span>
          {num(row.fallbackCount) > 0 ? (
            <NTooltip>
              {{
                trigger: () => <SvgIcon icon="carbon:shuffle" class="text-12px text-warning" />,
                default: () => `此请求发生了渠道故障转移（${num(row.fallbackCount)} 次）`
              }}
            </NTooltip>
          ) : null}
          {num(row.retryCount) > 0 ? (
            <NTooltip>
              {{
                trigger: () => <SvgIcon icon="carbon:renew" class="text-12px text-info" />,
                default: () => `此请求发生了重试（${num(row.retryCount)} 次）`
              }}
            </NTooltip>
          ) : null}
        </div>
        <div class="text-xs text-gray-400">{row.keyId != null ? `key ${row.keyId}` : '-'}</div>
      </div>
    )
  },
  {
    title: 'API格式',
    key: 'apiFormat',
    width: 150,
    filter: true,
    filterMultiple: false,
    filterOptionValue: filters.apiFormat,
    filterOptions: apiFormatOptions,
    render: row => {
      const inFmt = row.apiFormat || '-';
      const outFmt = row.endpointApiFormat;
      const converted = Boolean(outFmt && row.apiFormat && outFmt.toLowerCase() !== row.apiFormat.toLowerCase());
      if (converted) {
        return (
          <NTooltip>
            {{
              trigger: () => (
                <div class="leading-tight">
                  <div class="text-xs">{inFmt} →</div>
                  <div class="text-xs text-gray-400">{outFmt}</div>
                </div>
              ),
              default: () => '发生协议格式转换：客户端格式 → 上游端点格式'
            }}
          </NTooltip>
        );
      }
      return <span class="text-xs">{inFmt}</span>;
    }
  },
  {
    title: '类型',
    key: 'status',
    width: 90,
    align: 'center',
    filter: true,
    filterMultiple: false,
    filterOptionValue: filters.resultKey,
    filterOptions: statusFilterOptions,
    render: row => renderStatusBadge(row)
  },
  {
    title: 'Tokens',
    key: 'totalTokens',
    width: 120,
    align: 'center',
    render: row => (
      <div class="leading-tight">
        <div class="text-sm tabular-nums">
          {fmtTokens(row.promptTokens)} <span class="text-gray-400">/</span> {fmtTokens(row.completionTokens)}
        </div>
        <div class="text-xs text-gray-400 tabular-nums">
          {num(row.cacheTokens) > 0 ? fmtTokens(row.cacheTokens) : '-'}
        </div>
      </div>
    )
  },
  {
    title: '费用($)',
    key: 'costUsd',
    width: 110,
    align: 'right',
    render: row =>
      row.liveStatus ? (
        <span class="text-xs text-gray-400">计量中…</span>
      ) : (
        <span class="text-sm text-primary font-medium tabular-nums">{fmtUsd(row.costUsd)}</span>
      )
  },
  {
    title: () => (
      <div class="leading-tight">
        <div>首字/总耗时</div>
        <div class="text-xs text-gray-400 font-normal">输出速度</div>
      </div>
    ),
    key: 'durationMs',
    width: 130,
    align: 'right',
    render: row => {
      if (row.liveStatus) {
        return (
          <div class="leading-tight">
            <div class="text-sm tabular-nums">
              {num(row.ttftMs) > 0 ? `${fmtMs(row.ttftMs)} / ` : ''}
              <ElapsedTime startedAt={startedAtMs(row)} status={row.liveStatus} />
            </div>
            <div class="text-xs text-gray-400">进行中</div>
          </div>
        );
      }
      const ttft = num(row.ttftMs);
      const dur = num(row.durationMs);
      const genMs = ttft > 0 && ttft < dur ? dur - ttft : dur;
      return (
        <NTooltip>
          {{
            trigger: () => (
              <div class="leading-tight">
                <div class="text-sm tabular-nums">
                  {ttft > 0 ? `${fmtMs(ttft)} / ` : ''}
                  {fmtMs(dur)}
                </div>
                <div class="text-xs text-gray-400">{outputRate(row)}</div>
              </div>
            ),
            default: () => (
              <div class="text-xs leading-relaxed">
                <div>首字: {fmtMs(ttft)}</div>
                <div>总耗时: {fmtMs(dur)}</div>
                <div>生成耗时: {fmtMs(genMs)}</div>
                <div>输出速度: {outputRate(row)}</div>
              </div>
            )
          }}
        </NTooltip>
      );
    }
  },
  {
    title: '客户端',
    key: 'clientFamily',
    width: 110,
    filter: true,
    filterMultiple: false,
    filterOptionValue: filters.clientFamily,
    filterOptions: clientFamilyFilterOptions.value,
    render: row => (
      <NTooltip disabled={!row.userAgent}>
        {{
          trigger: () => (
            <NTag size="small" bordered class="max-w-100px">
              <span class="truncate">{formatClientFamily(row.clientFamily)}</span>
            </NTag>
          ),
          default: () => row.userAgent
        }}
      </NTooltip>
    )
  },
  {
    title: 'IP 地址',
    key: 'clientIp',
    width: 110,
    render: row => <span class="text-xs text-gray-400">{row.clientIp || '-'}</span>
  },
  {
    title: 'User-Agent',
    key: 'userAgent',
    width: 180,
    render: row => {
      const ua = row.userAgent || '-';
      return <span class="text-xs text-gray-400">{ua.length > 48 ? `${ua.slice(0, 48)}...` : ua}</span>;
    }
  }
]);

// ===== 列显示配置（「显示列」MultiSelect·localStorage 持久化）=====
const columnOptions = [
  { label: '时间', value: 'createDt' },
  { label: '项目', value: 'projectCode' },
  { label: '模型', value: 'model' },
  { label: '渠道', value: 'channelId' },
  { label: 'API格式', value: 'apiFormat' },
  { label: '类型', value: 'status' },
  { label: 'Tokens', value: 'totalTokens' },
  { label: '费用($)', value: 'costUsd' },
  { label: '性能', value: 'durationMs' },
  { label: '客户端', value: 'clientFamily' },
  { label: 'IP 地址', value: 'clientIp' },
  { label: 'User-Agent', value: 'userAgent' }
];
/** 默认隐藏 UA 列（元数据列·默认不展开全部元数据列） */
const DEFAULT_COLUMNS = columnOptions.map(o => o.value).filter(v => v !== 'userAgent');

function loadVisibleColumns(): string[] {
  // localStg.get 内部已 JSON 解析 + 损坏值返回 null（无需再 try/catch）
  const saved = localStg.get(COLUMNS_KEY);
  if (Array.isArray(saved)) {
    const valid = saved.filter(v => columnOptions.some(o => o.value === v));
    if (valid.length) return valid;
  }
  return DEFAULT_COLUMNS;
}
const visibleColumnKeys = ref<string[]>(loadVisibleColumns());

function onColumnsChange(keys: string[]) {
  if (!keys.length) return; // 至少保留一列
  visibleColumnKeys.value = keys;
  localStg.set(COLUMNS_KEY, keys);
}

const columns = computed(() =>
  allColumns.value.filter(c => 'key' in c && visibleColumnKeys.value.includes(String(c.key)))
);

// ===== 详情抽屉（行点击·钻取 /admin/observe/request/{requestId} + 故障转移链）=====
const drawerVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<{ request: NexusAI.RequestLog; usages: NexusAI.UsageLog[] } | null>(null);
const failoverChain = ref<NexusAI.FailoverEvent[]>([]);
const detailTab = ref('request');
const bodies = ref<{ collecting?: boolean; requestBody?: string | null; responseBody?: string | null } | null>(null);
const curlCopied = ref(false);

type DetailBundle = [
  Awaited<ReturnType<typeof fetchRequestDetail>>,
  Awaited<ReturnType<typeof fetchFailovers>>,
  Awaited<ReturnType<typeof fetchRequestBody>>
];
/** 行按下预取（@mousedown prefetchDetail·点击打开时即取即用） */
const prefetchCache = new Map<string, Promise<DetailBundle>>();

function prefetchDetail(requestId?: string) {
  if (!requestId || prefetchCache.has(requestId)) return;
  if (prefetchCache.size > 30) prefetchCache.clear();
  prefetchCache.set(
    requestId,
    Promise.all([fetchRequestDetail(requestId), fetchFailovers({ requestId }), fetchRequestBody(requestId)])
  );
}

async function openDetail(row: NexusAI.RequestLog) {
  if (!row.requestId || row.liveStatus) return; // 进行中行未落库·暂无详情
  drawerVisible.value = true;
  detailLoading.value = true;
  detailTab.value = 'request';
  failoverChain.value = [];
  bodies.value = null;
  curlInfo.value = null;
  prefetchDetail(row.requestId);
  const [detailRes, failoverRes, bodyRes] = await prefetchCache.get(row.requestId)!;
  prefetchCache.delete(row.requestId);
  if (!detailRes.error) detail.value = detailRes.data;
  if (!failoverRes.error && failoverRes.data) failoverChain.value = failoverRes.data;
  if (!bodyRes.error) bodies.value = bodyRes.data;
  detailLoading.value = false;
}

/** JSON 美化（非 JSON·如流式 SSE 文本则原样返回） */
function tryPretty(raw?: string | null): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/** 刷新详情（头部刷新钮·绕过预取缓存重拉） */
const detailRefreshing = ref(false);
async function refreshDetail() {
  const requestId = detail.value?.request?.requestId;
  if (!requestId || detailRefreshing.value) return;
  detailRefreshing.value = true;
  prefetchCache.delete(requestId);
  const [detailRes, failoverRes, bodyRes] = await Promise.all([
    fetchRequestDetail(requestId),
    fetchFailovers({ requestId }),
    fetchRequestBody(requestId)
  ]);
  if (!detailRes.error) detail.value = detailRes.data;
  if (!failoverRes.error && failoverRes.data) failoverChain.value = failoverRes.data;
  if (!bodyRes.error) bodies.value = bodyRes.data;
  detailRefreshing.value = false;
}

/** 元数据 Tab（metadata：完整字段 JSON 视图） */
const metadataJson = computed(() =>
  detail.value?.request ? JSON.stringify(detail.value.request, null, 2) : ''
);

/** 正文视图模式（JSON↔对话视图切换·仅 body Tab） */
const bodyViewMode = ref<'json' | 'chat'>('json');

/** 当前 Tab 的正文是否可渲染为对话（驱动切换钮显隐） */
const conversationAvailable = computed(() => {
  const raw = detailTab.value === 'request' ? bodies.value?.requestBody : bodies.value?.responseBody;
  if (!raw || (detailTab.value === 'response' && detail.value?.request?.streamMode)) return false;
  try {
    const parsed = JSON.parse(raw);
    if (detailTab.value === 'request') return Array.isArray(parsed?.messages);
    return Array.isArray(parsed?.choices) || Array.isArray(parsed?.content);
  } catch {
    return false;
  }
});

/** 请求头 Tab（request-headers·懒加载 cURL 端点的上游脱敏 headers） */
const curlInfo = ref<{ url?: string; headers?: Record<string, string>; bodyCaptured?: boolean } | null>(null);
const curlInfoLoading = ref(false);

async function ensureCurlHeaders() {
  const requestId = detail.value?.request?.requestId;
  if (!requestId || curlInfo.value || curlInfoLoading.value) return;
  curlInfoLoading.value = true;
  const { data, error } = await fetchRequestCurl(requestId);
  if (!error && data) curlInfo.value = data;
  curlInfoLoading.value = false;
}

function onDetailTabChange(tab: string) {
  if (tab === 'reqHeaders') ensureCurlHeaders();
  bodyViewMode.value = 'json';
}

/** 复制 cURL 重放命令（copyCurlCommand·1.5s 后恢复图标） */
async function copyCurl() {
  const requestId = detail.value?.request?.requestId;
  if (!requestId) return;
  const { data, error } = await fetchRequestCurl(requestId);
  if (error || !data) return;
  await navigator.clipboard.writeText(data.curlCommand);
  window.$message?.success('cURL 重放命令已复制（密钥已脱敏为 $UPSTREAM_API_KEY）');
  curlCopied.value = true;
  setTimeout(() => {
    curlCopied.value = false;
  }, 1500);
}

async function copyText(text?: string | null) {
  if (!text) return;
  await navigator.clipboard.writeText(text);
  window.$message?.success('已复制');
}

const rowProps = (row: NexusAI.RequestLog) => ({
  style: 'cursor: pointer;',
  onMousedown: () => prefetchDetail(row.requestId),
  onClick: () => openDetail(row)
});

// ===== 自动刷新（图标钮=3s 全局 + 活跃行恒走 1s 轮询·页面可见才跑）=====
const autoRefresh = ref(false);
let globalTimer: ReturnType<typeof setInterval> | null = null;
let activeTimer: ReturnType<typeof setInterval> | null = null;
let pageVisible = true;

function syncGlobalTimer() {
  if (globalTimer) {
    clearInterval(globalTimer);
    globalTimer = null;
  }
  if (autoRefresh.value) {
    globalTimer = setInterval(() => {
      if (pageVisible) refreshAll();
    }, AUTO_REFRESH_INTERVAL);
  }
}

/** 活跃行轮询：有活跃行时恒走 1s（不受自动刷新开关控制·活跃行始终自刷新到终态） */
function syncActivePoll() {
  const need = activeRows.value.length > 0;
  if (need && !activeTimer) {
    activeTimer = setInterval(() => {
      if (pageVisible) loadActive();
    }, ACTIVE_POLL_INTERVAL);
  } else if (!need && activeTimer) {
    clearInterval(activeTimer);
    activeTimer = null;
  }
}

function onVisibility() {
  pageVisible = document.visibilityState === 'visible';
  if (pageVisible && autoRefresh.value) refreshAll();
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value;
  if (autoRefresh.value) refreshAll();
  syncGlobalTimer();
}

function toggleHideUnknown() {
  hideUnknown.value = !hideUnknown.value;
  localStg.set(HIDE_UNKNOWN_KEY, hideUnknown.value);
  reload();
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility);
  loadChannels();
  loadProjects();
  loadRecords();
  loadActive();
  loadAggregations(true);
  loadHeatmap();
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility);
  if (globalTimer) clearInterval(globalTimer);
  if (activeTimer) clearInterval(activeTimer);
});

function onPageChange(p: number) {
  pagination.page = p;
  loadRecords();
}

function onPageSizeChange(size: number) {
  pagination.pageSize = size;
  pagination.page = 1;
  localStg.set(PAGE_SIZE_KEY, size);
  loadRecords();
}
</script>

<template>
  <!-- 普通文档流可滚动（space-y/pb 文档流·勿用 overflow-hidden 一屏范式：分析面板超一屏会裁掉记录表） -->
  <div class="flex flex-col gap-12px pb-12px">
    <!-- 用量分析面板（常显·sub2api 摘要 4 卡 → 热图+间隔时间线 2 列 → 三分析卡 3 列） -->
      <!-- 摘要统计卡（sub2api UsageStatsCards 对位·grid 2/4 列） -->
      <div class="grid grid-cols-2 gap-12px lg:grid-cols-4">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center gap-12px">
            <div class="rounded-8px bg-blue-100 p-8px text-blue-600 dark:bg-blue-900/30">
              <SvgIcon icon="carbon:document" class="text-18px" />
            </div>
            <div>
              <p class="m-0 text-xs text-gray-400 font-medium">总请求数</p>
              <p class="m-0 text-18px font-bold tabular-nums">{{ fmtCount(summaryStats.requests) }}</p>
              <p class="m-0 text-11px text-gray-400">在选定范围内</p>
            </div>
          </div>
        </NCard>
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center gap-12px">
            <div class="rounded-8px bg-amber-100 p-8px text-amber-600 dark:bg-amber-900/30">
              <SvgIcon icon="carbon:cube" class="text-18px" />
            </div>
            <div>
              <p class="m-0 text-xs text-gray-400 font-medium">总 Tokens</p>
              <p class="m-0 text-18px font-bold tabular-nums">{{ fmtTokens(summaryStats.tokens) }}</p>
              <p class="m-0 text-11px text-gray-400">
                入 {{ fmtTokens(summaryStats.promptTokens) }} / 出 {{ fmtTokens(summaryStats.completionTokens) }}
                <template v-if="summaryStats.cacheTokens"> · 缓存 {{ fmtTokens(summaryStats.cacheTokens) }}</template>
              </p>
            </div>
          </div>
        </NCard>
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center gap-12px">
            <div class="rounded-8px bg-green-100 p-8px text-green-600 dark:bg-green-900/30">
              <SvgIcon icon="carbon:wallet" class="text-18px" />
            </div>
            <div>
              <p class="m-0 text-xs text-gray-400 font-medium">总费用</p>
              <p class="m-0 text-18px font-bold text-green-600 tabular-nums dark:text-green-400">
                ${{ summaryStats.costUsd.toFixed(2) }}
              </p>
              <p class="m-0 text-11px text-gray-400">USD 口径</p>
            </div>
          </div>
        </NCard>
        <NCard :bordered="false" size="small" class="card-wrapper">
          <div class="flex items-center gap-12px">
            <div class="rounded-8px bg-purple-100 p-8px text-purple-600 dark:bg-purple-900/30">
              <SvgIcon icon="carbon:time" class="text-18px" />
            </div>
            <div>
              <p class="m-0 text-xs text-gray-400 font-medium">平均耗时</p>
              <p class="m-0 text-18px font-bold tabular-nums">{{ fmtMs(summaryStats.avgDurationMs) }}</p>
              <p class="m-0 text-11px text-gray-400">请求量加权</p>
            </div>
          </div>
        </NCard>
      </div>

      <NGrid :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
        <NGridItem span="24 l:12">
          <ActivityHeatmapCard :data="heatmap" :loading="heatmapLoading" title="总体活跃天数" />
        </NGridItem>
        <NGridItem span="24 l:12">
          <IntervalTimelineCard :title="intervalTimelineTitle" :hours="intervalHours" />
        </NGridItem>
      </NGrid>

      <NGrid :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
        <NGridItem span="24 l:8">
          <UsageAggTable title="按模型分析" kind="model" :data="modelAgg" :loading="aggLoading" />
        </NGridItem>
        <NGridItem span="24 l:8">
          <UsageAggTable title="按渠道分析" kind="channel" :data="channelAgg" :loading="aggLoading" />
        </NGridItem>
        <NGridItem span="24 l:8">
          <UsageAggTable title="按API格式分析" kind="apiFormat" :data="apiFormatAgg" :loading="aggLoading" />
        </NGridItem>
      </NGrid>

    <!-- 使用记录卡（TableCard：标题 + 时间范围|搜索|显示列|隐藏unknown|自动刷新 操作栏·筛选内嵌列头） -->
    <NCard :bordered="false" size="small" class="card-wrapper">
      <template #header>
        <span class="text-16px font-medium">使用记录</span>
      </template>
      <template #header-extra>
        <div class="flex items-center gap-8px">
          <NDatePicker
            v-model:value="dateRange"
            type="datetimerange"
            clearable
            size="small"
            class="hidden w-340px sm:flex"
            :shortcuts="rangeShortcuts"
            @update:value="reload"
          />
          <div class="hidden h-16px w-1px bg-gray-200 sm:block dark:bg-gray-700" />
          <NInput
            v-model:value="search"
            placeholder="搜索 请求ID / Trace / 模型 / 错误码"
            clearable
            size="small"
            style="width: 210px"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix>
              <SvgIcon icon="carbon:search" class="text-gray-400" />
            </template>
          </NInput>
          <div class="hidden h-16px w-1px bg-gray-200 sm:block dark:bg-gray-700" />
          <NPopselect
            :value="visibleColumnKeys"
            :options="columnOptions"
            multiple
            trigger="click"
            @update:value="onColumnsChange"
          >
            <NButton size="small" quaternary class="hidden sm:inline-flex">
              <template #icon>
                <SvgIcon icon="carbon:column" />
              </template>
              显示列
            </NButton>
          </NPopselect>
          <div class="hidden h-16px w-1px bg-gray-200 sm:block dark:bg-gray-700" />
          <NTooltip>
            <template #trigger>
              <NButton size="small" quaternary :type="hideUnknown ? 'primary' : 'default'" @click="toggleHideUnknown">
                <template #icon>
                  <SvgIcon :icon="hideUnknown ? 'carbon:view-off' : 'carbon:view'" />
                </template>
              </NButton>
            </template>
            {{ hideUnknown ? '显示 unknown 请求' : '隐藏 unknown 请求' }}
          </NTooltip>
          <NTooltip>
            <template #trigger>
              <NButton
                size="small"
                quaternary
                :type="autoRefresh ? 'primary' : 'default'"
                @click="toggleAutoRefresh"
              >
                <template #icon>
                  <SvgIcon icon="carbon:renew" :class="autoRefresh ? 'animate-spin' : ''" />
                </template>
              </NButton>
            </template>
            {{ autoRefresh ? '点击关闭自动刷新' : '点击开启自动刷新' }}
          </NTooltip>
          <div class="hidden h-16px w-1px bg-gray-200 sm:block dark:bg-gray-700" />
          <NButton size="small" type="primary" :loading="exporting" @click="exportToExcel">
            <template #icon>
              <SvgIcon icon="carbon:document-export" />
            </template>
            导出 Excel
          </NButton>
        </div>
      </template>

      <!-- 移动端筛选网格（<sm·移动端 2 列筛选） -->
      <div class="mb-12px grid grid-cols-2 gap-8px sm:hidden">
        <NDatePicker
          v-model:value="dateRange"
          type="datetimerange"
          clearable
          size="small"
          class="col-span-2"
          :shortcuts="rangeShortcuts"
          @update:value="reload"
        />
        <NSelect
          v-model:value="filters.channelId"
          :options="channelOptions"
          placeholder="全部渠道"
          clearable
          size="small"
          @update:value="reload"
        />
        <NSelect
          v-model:value="filters.model"
          :options="modelFilterOptions"
          placeholder="全部模型"
          clearable
          size="small"
          @update:value="reload"
        />
        <NSelect
          v-model:value="filters.apiFormat"
          :options="apiFormatOptions"
          placeholder="全部格式"
          clearable
          size="small"
          @update:value="reload"
        />
        <NSelect
          v-model:value="filters.resultKey"
          :options="statusFilterOptions"
          placeholder="全部状态"
          clearable
          size="small"
          @update:value="reload"
        />
      </div>

      <!-- 桌面表格（≥sm·：筛选内嵌列头漏斗·无排序·活跃行置顶） -->
      <div class="hidden sm:block">
        <NDataTable
          remote
          :columns="columns"
          :data="displayRecords"
          :loading="loading"
          :row-props="rowProps"
          :row-key="(row: NexusAI.RequestLog) => String(row.id ?? row.requestId)"
          size="small"
          :min-height="360"
          @update:filters="onFiltersChange"
        />
      </div>

      <!-- 移动端卡片视图（<sm·mobile card） -->
      <div class="flex flex-col gap-8px sm:hidden">
        <NSpin :show="loading">
          <NEmpty v-if="!displayRecords.length && !loading" description="暂无请求记录" />
          <div
            v-for="row in displayRecords"
            :key="String(row.id ?? row.requestId)"
            class="border border-gray-200 rounded-8px p-12px dark:border-gray-700"
            @click="openDetail(row)"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm font-medium">{{ row.model || '-' }}</span>
              <span class="text-sm">{{ row.liveStatus ? '计量中…' : fmtUsd(row.costUsd) }}</span>
            </div>
            <div class="mt-4px flex items-center gap-8px text-xs text-gray-400">
              <span>{{ fmtTimePair(row.createDt)[0] }} {{ fmtTimePair(row.createDt)[1] }}</span>
              <component :is="() => renderStatusBadge(row)" />
            </div>
            <div class="mt-4px text-xs text-gray-400">
              {{ fmtMs(row.durationMs) }} · {{ outputRate(row) }} · {{ fmtTokens(row.promptTokens) }}/{{
                fmtTokens(row.completionTokens)
              }}
              tokens
            </div>
          </div>
        </NSpin>
      </div>

      <div v-if="filters.resultKey !== 'active'" class="mt-12px flex justify-end">
        <NPagination
          :page="pagination.page"
          :page-size="pagination.pageSize"
          :item-count="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="onPageChange"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </NCard>

    <!-- 详情抽屉（RequestDetailDrawer：固定头部 → 失败卡 → 费用与性能卡 → 请求链路 → Tabs 卡） -->
    <NDrawer v-model:show="drawerVisible" :width="800" class="max-w-90vw">
      <NDrawerContent closable body-content-class="bg-gray-50 dark:bg-gray-900">
        <template #header>
          <div class="flex flex-col gap-6px">
            <!-- 行1：标题 + 模型映射 chip + 状态/流式徽章 + 刷新 -->
            <div class="flex items-center gap-8px">
              <span class="text-16px font-semibold">请求详情</span>
              <span
                v-if="detail?.request"
                class="flex items-center gap-4px rounded-4px bg-gray-100 px-8px py-2px text-xs font-mono dark:bg-gray-800"
              >
                <span>{{ detail.request.model || '-' }}</span>
                <template v-if="detail.request.targetModel && detail.request.targetModel !== detail.request.model">
                  <span class="text-gray-400">→</span>
                  <span>{{ detail.request.targetModel }}</span>
                </template>
              </span>
              <NTag
                v-if="detail?.request"
                size="small"
                :type="isSuccess(detail.request) ? 'success' : 'error'"
                :bordered="false"
              >
                {{ detail.request.httpStatus ?? '-' }}
              </NTag>
              <NTag v-if="detail?.request" size="small" :bordered="false" :type="detail.request.streamMode ? 'info' : 'default'">
                {{ detail.request.streamMode ? '流式' : '标准' }}
              </NTag>
              <NTooltip>
                <template #trigger>
                  <NButton size="tiny" quaternary @click="refreshDetail">
                    <template #icon>
                      <SvgIcon icon="carbon:renew" :class="detailRefreshing ? 'animate-spin' : ''" />
                    </template>
                  </NButton>
                </template>
                刷新详情
              </NTooltip>
            </div>
            <!-- 行2：关键元信息 ID | 时间 | API格式 | 项目 -->
            <div v-if="detail?.request" class="flex flex-wrap items-center gap-x-8px text-xs text-gray-400 font-normal">
              <span
                class="flex cursor-pointer items-center gap-4px"
                title="点击复制完整 requestId"
                @click="copyText(detail.request.requestId)"
              >
                <span class="text-gray-500 font-medium">ID:</span>
                <span class="font-mono underline-dotted">{{ detail.request.requestId }}</span>
                <SvgIcon icon="carbon:copy" class="text-11px op-60" />
              </span>
              <span class="opacity-40">|</span>
              <span>{{ (detail.request.createDt || '-').replace('T', ' ') }}</span>
              <span class="opacity-40">|</span>
              <span>{{ detail.request.apiFormat || '-' }}</span>
              <span class="opacity-40">|</span>
              <span>项目: {{ projectLabel(detail.request.projectCode as string) }}</span>
            </div>
          </div>
        </template>
        <NSpin :show="detailLoading">
          <template v-if="detail?.request">
            <div class="flex flex-col gap-12px">
              <!-- 失败原因卡（failureNotice 红卡） -->
              <NAlert v-if="detail.request.errorCode || !isSuccess(detail.request)" type="error" title="请求失败">
                <div class="text-sm">{{ detail.request.errorCode || `上游返回 HTTP ${detail.request.httpStatus}` }}</div>
                <div class="mt-4px flex flex-wrap gap-4px">
                  <span class="rounded-full border border-red-200 px-8px py-1px text-11px font-mono">
                    HTTP {{ detail.request.httpStatus ?? '-' }}
                  </span>
                  <span v-if="detail.request.usageSource" class="rounded-full border border-red-200 px-8px py-1px text-11px font-mono">
                    {{ detail.request.usageSource }}
                  </span>
                </div>
              </NAlert>

              <!-- 费用与性能概览卡（顶行 总费用|耗时|输出速度 → Token 计费高亮块） -->
              <NCard :bordered="false" size="small" class="card-wrapper">
                <div class="flex flex-wrap items-center gap-x-8px gap-y-4px text-sm">
                  <span class="rounded-4px bg-gray-100 px-6px py-1px text-10px text-gray-400 dark:bg-gray-800">USD 计费</span>
                  <span class="text-gray-400">|</span>
                  <span class="whitespace-nowrap">
                    <span class="text-gray-400">总费用</span>
                    <span class="ml-4px font-bold text-green-600 dark:text-green-400">{{ fmtUsd(detail.request.costUsd) }}</span>
                  </span>
                  <span class="text-gray-400">|</span>
                  <span class="whitespace-nowrap">
                    <span class="text-gray-400">耗时</span>
                    <span class="ml-4px font-bold">{{ fmtMs(detail.request.ttftMs) }} / {{ fmtMs(detail.request.durationMs) }}</span>
                  </span>
                  <span class="text-gray-400">|</span>
                  <span class="whitespace-nowrap">
                    <span class="text-gray-400">输出速度</span>
                    <span class="ml-4px text-primary font-bold">{{ outputRate(detail.request) }}</span>
                  </span>
                </div>
                <div class="my-12px border-t border-gray-100 dark:border-gray-800" />
                <!-- Token 计费块 -->
                <div class="flex flex-wrap items-center gap-8px text-xs">
                  <span class="text-gray-700 font-medium dark:text-gray-200">Token 计费</span>
                  <span class="font-mono font-medium">{{ fmtUsd(detail.request.costUsd) }}</span>
                  <span class="text-gray-400">
                    (输入 {{ fmtCount(detail.request.promptTokens) }} + 缓存读取 {{ fmtCount(detail.request.cacheTokens) }})
                  </span>
                  <NTag v-if="detail.request.usageSource && detail.request.usageSource !== 'parsed'" size="tiny" type="warning">
                    {{ detail.request.usageSource }}
                  </NTag>
                </div>
                <!-- Token 计费明细（当前阶梯详细计算行式：标签左·数量右对齐） -->
                <div class="mt-8px border border-primary/30 rounded-8px bg-primary/5 p-12px">
                  <div class="token-row">
                    <span class="text-xs text-gray-400">输入</span>
                    <span class="text-13px font-mono font-semibold tabular-nums">{{ fmtCount(detail.request.promptTokens) }}</span>
                  </div>
                  <div class="token-row">
                    <span class="text-xs text-gray-400">输出</span>
                    <span class="text-13px font-mono font-semibold tabular-nums">{{ fmtCount(detail.request.completionTokens) }}</span>
                  </div>
                  <div v-if="num(detail.request.cacheTokens) > 0" class="token-row">
                    <span class="text-xs text-gray-400">缓存读取</span>
                    <span class="text-13px text-sky-600 font-mono font-semibold tabular-nums dark:text-sky-400">
                      {{ fmtCount(detail.request.cacheTokens) }}
                    </span>
                  </div>
                  <div class="token-row border-t border-primary/20 pt-6px">
                    <span class="text-xs text-gray-500 font-medium">总计</span>
                    <span class="text-13px font-mono font-bold tabular-nums">{{ fmtCount(detail.request.totalTokens) }} tokens</span>
                  </div>
                  <div class="token-row">
                    <span class="text-xs text-gray-500 font-medium">费用</span>
                    <span class="text-13px text-green-600 font-mono font-bold tabular-nums dark:text-green-400">
                      {{ fmtUsd(detail.request.costUsd) }}
                    </span>
                  </div>
                </div>
              </NCard>

              <!-- 请求链路追踪（HorizontalRequestTimeline·横向节点流·hover 看原因） -->
              <NCard :bordered="false" size="small" class="card-wrapper">
                <div class="mb-8px flex items-center gap-8px">
                  <span class="text-sm font-medium">请求链路追踪</span>
                  <NTag v-if="failoverChain.length" size="tiny" type="warning" :bordered="false">
                    {{ failoverChain.length }} 次转移/重试
                  </NTag>
                </div>
                <RequestTimeline :request="detail.request" :chain="failoverChain" :channel-label="channelLabel" />
              </NCard>

              <!-- Tabs 卡（请求体/响应体/元数据·工具栏 cURL(请求体)+复制） -->
              <NCard :bordered="false" size="small" class="card-wrapper">
                <NTabs v-model:value="detailTab" type="line" size="small" @update:value="onDetailTabChange">
                  <template #suffix>
                    <div class="flex items-center gap-4px">
                      <NButton
                        v-if="(detailTab === 'request' || detailTab === 'response') && conversationAvailable"
                        size="tiny"
                        quaternary
                        :title="bodyViewMode === 'chat' ? '切换到 JSON 视图' : '切换到对话视图'"
                        :type="bodyViewMode === 'chat' ? 'primary' : 'default'"
                        @click="bodyViewMode = bodyViewMode === 'chat' ? 'json' : 'chat'"
                      >
                        <template #icon>
                          <SvgIcon :icon="bodyViewMode === 'chat' ? 'carbon:code' : 'carbon:chat'" />
                        </template>
                      </NButton>
                      <NTooltip v-if="detailTab === 'request' || detailTab === 'reqHeaders'">
                        <template #trigger>
                          <NButton size="tiny" quaternary :type="curlCopied ? 'success' : 'default'" @click="copyCurl">
                            <template #icon>
                              <SvgIcon :icon="curlCopied ? 'carbon:checkmark' : 'carbon:terminal'" />
                            </template>
                          </NButton>
                        </template>
                        复制 cURL（对上游重放·密钥脱敏 $UPSTREAM_API_KEY）
                      </NTooltip>
                      <NTooltip>
                        <template #trigger>
                          <NButton
                            size="tiny"
                            quaternary
                            @click="
                              copyText(
                                detailTab === 'request'
                                  ? bodies?.requestBody
                                  : detailTab === 'response'
                                    ? bodies?.responseBody
                                    : detailTab === 'reqHeaders'
                                      ? JSON.stringify(curlInfo?.headers ?? {}, null, 2)
                                      : metadataJson
                              )
                            "
                          >
                            <template #icon>
                              <SvgIcon icon="carbon:copy" />
                            </template>
                          </NButton>
                        </template>
                        复制当前内容
                      </NTooltip>
                    </div>
                  </template>

                  <NTabPane name="reqHeaders" tab="请求头">
                    <NSpin :show="curlInfoLoading">
                      <template v-if="curlInfo?.headers && Object.keys(curlInfo.headers).length">
                        <div class="mb-6px text-11px text-gray-400">
                          对上游请求头（鉴权头已脱敏为 $UPSTREAM_API_KEY）· {{ curlInfo.url }}
                        </div>
                        <JsonPanel title="上游请求头" :data="curlInfo.headers" :max-height="420" />
                      </template>
                      <NEmpty v-else-if="!curlInfoLoading" description="无可展示的请求头" />
                    </NSpin>
                  </NTabPane>

                  <NTabPane name="request" tab="请求体">
                    <template v-if="bodies?.requestBody">
                      <ConversationView
                        v-if="bodyViewMode === 'chat'"
                        :body="bodies.requestBody"
                        kind="request"
                        :max-height="500"
                      />
                      <JsonPanel v-else title="请求体" :data="bodies.requestBody" :max-height="500" />
                    </template>
                    <NEmpty
                      v-else
                      :description="
                        bodies?.collecting
                          ? '无请求体信息（采集开启后的新请求才有）'
                          : '正文采集未开启（nexusai.log.body.store=rdb 且 collect=true）'
                      "
                    />
                  </NTabPane>

                  <NTabPane name="response" tab="响应体">
                    <template v-if="bodies?.responseBody">
                      <ConversationView
                        v-if="bodyViewMode === 'chat' && !detail.request.streamMode"
                        :body="bodies.responseBody"
                        kind="response"
                        :max-height="500"
                      />
                      <JsonPanel
                        v-else-if="!detail.request.streamMode"
                        title="响应体"
                        :data="bodies.responseBody"
                        :max-height="500"
                      />
                      <div v-else class="max-h-500px overflow-auto rounded-6px bg-gray-50 p-12px dark:bg-gray-800">
                        <NCode :code="bodies.responseBody" language="text" word-wrap />
                      </div>
                    </template>
                    <NEmpty
                      v-else
                      :description="
                        bodies?.collecting
                          ? '无响应体信息（采集开启后的新请求才有·流式聚合钳 5MB）'
                          : '正文采集未开启（nexusai.log.body.store=rdb 且 collect=true）'
                      "
                    />
                  </NTabPane>

                  <NTabPane name="metadata" tab="元数据">
                    <JsonPanel title="元数据" :data="detail.request" :max-height="500" />
                  </NTabPane>
                </NTabs>
              </NCard>
            </div>
          </template>
          <NEmpty v-else-if="!detailLoading" description="未找到该请求" />
        </NSpin>
      </NDrawerContent>
    </NDrawer>

    <!-- 导出进度弹窗（sub2api UsageExportProgress 对位·可取消） -->
    <NModal :show="exportProgress.show" preset="card" title="正在导出 Excel" class="max-w-380px" :closable="false">
      <div class="space-y-12px">
        <div class="flex items-center justify-between text-13px">
          <span>已导出 {{ exportProgress.current }} / {{ exportProgress.total }} 条</span>
          <span class="font-medium tabular-nums">{{ exportProgress.percent }}%</span>
        </div>
        <NProgress type="line" :percentage="exportProgress.percent" :show-indicator="false" />
        <p class="m-0 text-11px text-gray-400">按当前筛选条件分页拉取全量数据（每页 200 条）</p>
      </div>
      <template #footer>
        <div class="flex justify-end">
          <NButton size="small" @click="cancelExport">取消导出</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.token-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 3px 0;
}
</style>
