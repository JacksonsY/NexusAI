<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  exportSystemConfig,
  fetchProxyNodes,
  fetchSystemConfigs,
  fetchSystemVersion,
  importSystemConfig,
  purgeSystemData,
  saveSystemConfigs
} from '@/service/api';

/**
 * 系统设置（SystemSettings·按 nexusai 纯转发面语义映射）：
 * 站点信息 / 数据管理（导出导入+清空）/ 网络代理 / 基础配置 / 请求记录 /
 * 记录清理策略 / 定时任务 / 运维监控 / 系统信息。
 */
defineOptions({ name: 'Settings' });

// ===== 配置加载 =====
interface Cfg {
  site_name: string;
  site_subtitle: string;
  system_proxy_node_id: string | null;
  enable_format_conversion: boolean;
  request_record_level: 'basic' | 'full';
  max_request_body_kb: number;
  max_response_body_kb: number;
  enable_auto_cleanup: boolean;
  cleanup_time: string;
  log_retention_days: number;
  task_retention_days: number;
  failover_event_retention_days: number;
  proxy_node_event_retention_days: number;
  proxy_node_metric_retention_days: number;
  cleanup_batch_size: number;
  enable_proxy_node_probe: boolean;
  enable_ops_alert_eval: boolean;
  'ops.alert_eval_interval_seconds': number;
  'ops.sla_percent_min': number;
  'ops.error_rate_percent_max': number;
  'ops.p99_latency_ms_max': number;
  'ops.ttft_p99_ms_max': number;
}

const DEFAULTS: Cfg = {
  site_name: 'NexusAI',
  site_subtitle: 'AI Gateway',
  system_proxy_node_id: null,
  enable_format_conversion: false,
  request_record_level: 'full',
  max_request_body_kb: 1024,
  max_response_body_kb: 1024,
  enable_auto_cleanup: true,
  cleanup_time: '03:30',
  log_retention_days: 30,
  task_retention_days: 30,
  failover_event_retention_days: 30,
  proxy_node_event_retention_days: 30,
  proxy_node_metric_retention_days: 30,
  cleanup_batch_size: 1000,
  enable_proxy_node_probe: true,
  enable_ops_alert_eval: true,
  'ops.alert_eval_interval_seconds': 60,
  'ops.sla_percent_min': 95,
  'ops.error_rate_percent_max': 5,
  'ops.p99_latency_ms_max': 30000,
  'ops.ttft_p99_ms_max': 5000
};

const cfg = ref<Cfg>({ ...DEFAULTS });
const saved = ref<Cfg>({ ...DEFAULTS });
const configLoading = ref(true);
const savingSection = ref<string | null>(null);

function pickChanges(keys: (keyof Cfg)[]) {
  const out: Record<string, unknown> = {};
  for (const k of keys) {
    if (cfg.value[k] !== saved.value[k]) out[k] = cfg.value[k];
  }
  return out;
}

function hasChanges(keys: (keyof Cfg)[]) {
  return keys.some(k => cfg.value[k] !== saved.value[k]);
}

const SECTION_KEYS: Record<string, (keyof Cfg)[]> = {
  site: ['site_name', 'site_subtitle'],
  proxy: ['system_proxy_node_id'],
  basic: ['enable_format_conversion'],
  log: ['request_record_level', 'max_request_body_kb', 'max_response_body_kb'],
  cleanup: [
    'enable_auto_cleanup',
    'log_retention_days',
    'task_retention_days',
    'failover_event_retention_days',
    'proxy_node_event_retention_days',
    'proxy_node_metric_retention_days',
    'cleanup_batch_size'
  ],
  ops: [
    'ops.alert_eval_interval_seconds',
    'ops.sla_percent_min',
    'ops.error_rate_percent_max',
    'ops.p99_latency_ms_max',
    'ops.ttft_p99_ms_max'
  ]
};

async function saveSection(section: string) {
  const keys = SECTION_KEYS[section];
  const changes = pickChanges(keys);
  if (Object.keys(changes).length === 0) return;
  savingSection.value = section;
  const { error } = await saveSystemConfigs(changes);
  savingSection.value = null;
  if (error) return;
  for (const k of keys) saved.value[k] = cfg.value[k] as never;
  window.$message?.success('保存成功');
}

async function saveSingle(key: keyof Cfg, value: Cfg[typeof key]) {
  cfg.value[key] = value as never;
  const { error } = await saveSystemConfigs({ [key]: value });
  if (error) {
    cfg.value[key] = saved.value[key] as never;
    return;
  }
  saved.value[key] = value as never;
  window.$message?.success('保存成功');
}

async function loadConfigs() {
  configLoading.value = true;
  const { data, error } = await fetchSystemConfigs();
  if (!error && data) {
    const merged: Cfg = { ...DEFAULTS };
    for (const k of Object.keys(DEFAULTS) as (keyof Cfg)[]) {
      if (data[k] !== undefined && data[k] !== null) {
        merged[k] = data[k] as never;
      }
    }
    if (merged.system_proxy_node_id !== null) merged.system_proxy_node_id = String(merged.system_proxy_node_id);
    cfg.value = { ...merged };
    saved.value = { ...merged };
  }
  configLoading.value = false;
}

// ===== 网络代理 =====
const proxyNodes = ref<NexusAI.ProxyNode[]>([]);

const proxyOptions = computed(() => {
  const online = proxyNodes.value.filter(n => n.status === 'online');
  const selected = cfg.value.system_proxy_node_id
    ? proxyNodes.value.find(n => n.id === cfg.value.system_proxy_node_id)
    : null;
  const list = selected && !online.some(n => n.id === selected.id) ? [selected, ...online] : online;
  return [
    { label: '直连（不使用代理）', value: '__direct__' },
    ...list.map(n => ({
      label: `${n.name}${n.region ? ` · ${n.region}` : ''} (${n.proxyUrl})`,
      value: n.id
    }))
  ];
});

// ===== 数据管理 =====
const exportLoading = ref(false);
const importLoading = ref(false);
const importDialogOpen = ref(false);
const importPayload = ref<Record<string, unknown> | null>(null);
const mergeMode = ref<'skip' | 'overwrite'>('skip');
const importResult = ref<NexusAI.ConfigImportStats | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const purgingTarget = ref<string | null>(null);

const importPreview = computed(() => {
  if (!importPayload.value) return [];
  return ['channels', 'channelKeys', 'models', 'abilities', 'routingGroups', 'projects', 'systemConfigs']
    .map(k => ({ key: k, count: Array.isArray(importPayload.value?.[k]) ? (importPayload.value?.[k] as unknown[]).length : 0 }))
    .filter(i => i.count > 0);
});

const ENTITY_LABELS: Record<string, string> = {
  channels: '渠道',
  channelKeys: '渠道 Key',
  models: '模型',
  abilities: '能力',
  routingGroups: '调度分组',
  projects: '项目',
  systemConfigs: '系统配置'
};

async function handleExport() {
  exportLoading.value = true;
  const { data, error } = await exportSystemConfig();
  exportLoading.value = false;
  if (error || !data) return;
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `nexusai-config-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
  window.$message?.success('配置已导出');
}

function triggerFileSelect() {
  fileInput.value?.click();
}

async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  try {
    importPayload.value = JSON.parse(await file.text());
    importResult.value = null;
    mergeMode.value = 'skip';
    importDialogOpen.value = true;
  } catch {
    window.$message?.error('文件不是合法 JSON');
  }
}

async function confirmImport() {
  if (!importPayload.value) return;
  importLoading.value = true;
  const { data, error } = await importSystemConfig(importPayload.value, mergeMode.value);
  importLoading.value = false;
  if (error) return;
  importResult.value = data ?? null;
  window.$message?.success('配置导入完成');
}

const purgeItems = [
  { key: 'usage-logs', title: '清空使用记录', description: '删除全部计量用量记录（nai_usage_log），USD 账本不受影响', icon: 'carbon:chart-bar', confirm: '确定要清空全部使用记录吗？所有计量统计数据将被永久删除，操作不可逆。' },
  { key: 'request-logs', title: '清空请求日志', description: '删除全部请求日志（含正文哈希与性能字段），仪表盘历史曲线将清零', icon: 'carbon:document', confirm: '确定要清空全部请求日志吗？操作不可逆。' },
  { key: 'tasks', title: '清空异步任务', description: '删除全部异步任务记录（含已完成/失败的历史任务）', icon: 'carbon:task', confirm: '确定要清空全部异步任务记录吗？操作不可逆。' },
  { key: 'failover-events', title: '清空故障转移事件', description: '删除全部换 Key/换渠道重试链审计事件', icon: 'carbon:warning-alt', confirm: '确定要清空全部故障转移事件吗？操作不可逆。' }
];

async function handlePurge(item: (typeof purgeItems)[number]) {
  window.$dialog?.warning({
    title: item.title,
    content: item.confirm,
    positiveText: '确认清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      purgingTarget.value = item.key;
      const { data, error } = await purgeSystemData(item.key);
      purgingTarget.value = null;
      if (!error) {
        window.$message?.success(`${item.title}完成${data && Number(data.deleted) >= 0 ? `，删除 ${Number(data.deleted).toLocaleString()} 条` : ''}`);
      }
    }
  });
}

// ===== 定时任务 =====
const cleanupHour = computed(() => cfg.value.cleanup_time.split(':')[0] ?? '03');
const cleanupMinute = computed(() => cfg.value.cleanup_time.split(':')[1] ?? '30');
const timeOptions = (n: number) => Array.from({ length: n }, (_, i) => ({ label: String(i).padStart(2, '0'), value: String(i).padStart(2, '0') }));

function updateCleanupTime(hour: string, minute: string) {
  saveSingle('cleanup_time', `${hour}:${minute}`);
}

const scheduledTasks = computed(() => [
  {
    id: 'auto-cleanup',
    icon: 'carbon:clean',
    title: '记录自动清理',
    description: '每日按「记录清理策略」删除过期请求日志/任务/故障转移事件/代理节点指标',
    enabled: cfg.value.enable_auto_cleanup,
    hasTimeConfig: true,
    onToggle: (v: boolean) => saveSingle('enable_auto_cleanup', v)
  },
  {
    id: 'proxy-probe',
    icon: 'carbon:network-3',
    title: '代理节点探测',
    description: '每分钟探测全部代理节点连通性，维护在线状态/延迟 EMA/1h 指标桶',
    enabled: cfg.value.enable_proxy_node_probe,
    hasTimeConfig: false,
    onToggle: (v: boolean) => saveSingle('enable_proxy_node_probe', v)
  }
]);

// ===== 系统信息 =====
const sysVersion = ref<NexusAI.SystemVersion | null>(null);

function fmtUptime(ms?: number) {
  if (!ms) return '-';
  const totalMinutes = Math.floor(ms / 60000);
  if (totalMinutes < 60) return `${totalMinutes} 分钟`;
  const hours = Math.floor(totalMinutes / 60);
  if (hours < 24) return `${hours} 小时 ${totalMinutes % 60} 分钟`;
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
}

onMounted(async () => {
  const [, nodesRes, verRes] = await Promise.all([loadConfigs(), fetchProxyNodes(), fetchSystemVersion()]);
  if (!nodesRes.error) proxyNodes.value = nodesRes.data ?? [];
  if (!verRes.error) sysVersion.value = verRes.data;
});
</script>

<template>
  <div class="relative flex gap-16px">
    <!-- 主内容 -->
    <div class="min-w-0 flex-1 space-y-16px pb-32px" :class="{ 'pointer-events-none opacity-60': configLoading }">
      <div>
        <h3 class="text-base font-semibold">系统设置</h3>
        <p class="mt-2px text-xs text-gray-400">管理系统级别的配置和参数</p>
      </div>

      <div v-if="configLoading" class="border border-gray-100 rounded-8px bg-white px-16px py-12px text-sm text-gray-400 dark:border-gray-800 dark:bg-gray-800">
        系统配置加载中...
      </div>

      <!-- 站点信息 -->
      <NCard id="section-site-info" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px flex items-center justify-between">
          <div>
            <div class="text-sm font-semibold">站点信息</div>
            <div class="mt-2px text-xs text-gray-400">自定义站点名称和副标题，影响控制台导航与登录页显示</div>
          </div>
          <NButton size="small" type="primary" :disabled="!hasChanges(SECTION_KEYS.site)" :loading="savingSection === 'site'" @click="saveSection('site')">
            保存
          </NButton>
        </div>
        <div class="grid grid-cols-1 gap-16px md:grid-cols-2">
          <div>
            <div class="mb-4px text-sm font-medium">站点名称</div>
            <NInput v-model:value="cfg.site_name" placeholder="NexusAI" />
            <p class="mt-4px text-xs text-gray-400">显示在导航栏和登录页标题中</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">站点副标题</div>
            <NInput v-model:value="cfg.site_subtitle" placeholder="AI Gateway" />
            <p class="mt-4px text-xs text-gray-400">显示在品牌名称下方</p>
          </div>
        </div>
      </NCard>

      <!-- 数据管理 -->
      <NCard id="section-data-mgmt" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px">
          <div class="text-sm font-semibold">数据管理</div>
          <div class="mt-2px text-xs text-gray-400">导出、导入或清空系统数据</div>
        </div>

        <div class="mb-8px flex items-center gap-8px">
          <SvgIcon icon="carbon:data-base" class="text-gray-400" />
          <span class="text-sm font-medium">导出 / 导入</span>
        </div>
        <div class="flex flex-col gap-12px border border-gray-100 rounded-8px p-16px dark:border-gray-800">
          <div class="flex items-center gap-8px">
            <SvgIcon icon="carbon:settings" class="text-gray-400" />
            <span class="text-sm font-medium">配置数据</span>
          </div>
          <p class="text-xs text-gray-400">渠道、渠道 Key（AES 密文）、模型、能力、调度分组、项目与系统配置</p>
          <div class="flex gap-8px">
            <NButton size="small" :loading="exportLoading" @click="handleExport">
              <template #icon>
                <SvgIcon icon="carbon:download" />
              </template>
              导出配置
            </NButton>
            <NButton size="small" :loading="importLoading" @click="triggerFileSelect">
              <template #icon>
                <SvgIcon icon="carbon:upload" />
              </template>
              导入配置
            </NButton>
            <input ref="fileInput" type="file" accept=".json" class="hidden" @change="onFileSelected" />
          </div>
        </div>

        <NDivider class="!my-16px" />

        <div class="mb-8px flex items-center gap-8px">
          <SvgIcon icon="carbon:trash-can" class="text-gray-400" />
          <span class="text-sm font-medium">清空数据</span>
        </div>
        <div class="grid grid-cols-1 gap-12px sm:grid-cols-2 lg:grid-cols-4">
          <div v-for="item in purgeItems" :key="item.key" class="flex flex-col gap-8px border border-gray-100 rounded-8px p-16px dark:border-gray-800">
            <div class="flex items-center gap-8px">
              <SvgIcon :icon="item.icon" class="text-gray-400" />
              <span class="text-sm font-medium">{{ item.title }}</span>
            </div>
            <p class="flex-1 text-xs text-gray-400">{{ item.description }}</p>
            <NButton size="small" type="error" ghost :loading="purgingTarget === item.key" @click="handlePurge(item)">
              <template #icon>
                <SvgIcon icon="carbon:trash-can" />
              </template>
              {{ item.title }}
            </NButton>
          </div>
        </div>
      </NCard>

      <!-- 网络代理 -->
      <NCard id="section-proxy" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px flex items-center justify-between">
          <div>
            <div class="text-sm font-semibold">网络代理</div>
            <div class="mt-2px text-xs text-gray-400">配置上游透传请求的默认出口代理，仅影响大模型 API 等上游请求</div>
          </div>
          <NButton size="small" type="primary" :disabled="!hasChanges(SECTION_KEYS.proxy)" :loading="savingSection === 'proxy'" @click="saveSection('proxy')">
            保存
          </NButton>
        </div>
        <div class="max-w-420px">
          <div class="mb-4px text-sm font-medium">默认代理节点</div>
          <NSelect
            :value="cfg.system_proxy_node_id || '__direct__'"
            :options="proxyOptions"
            @update:value="(v: string) => (cfg.system_proxy_node_id = v === '__direct__' ? null : v)"
          />
          <p class="mt-4px text-xs text-gray-400">
            对未单独配置代理（渠道 setting.proxyNodeId）的渠道生效，覆盖上游透传与渠道探测。不影响系统内部接口。
          </p>
        </div>
      </NCard>

      <!-- 基础配置 -->
      <NCard id="section-basic" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px flex items-center justify-between">
          <div>
            <div class="text-sm font-semibold">基础配置</div>
            <div class="mt-2px text-xs text-gray-400">配置系统默认参数</div>
          </div>
          <NButton size="small" type="primary" :disabled="!hasChanges(SECTION_KEYS.basic)" :loading="savingSection === 'basic'" @click="saveSection('basic')">
            保存
          </NButton>
        </div>
        <div class="flex items-center justify-between border border-gray-100 rounded-8px p-16px dark:border-gray-800">
          <div>
            <div class="text-sm font-medium">全局格式转换</div>
            <p class="mt-2px text-xs text-gray-400">
              强制允许跨协议请求（入站格式 ≠ 上游格式时走 Canonical 互转）；关闭时仅 setting.canonical=true 的渠道允许
            </p>
          </div>
          <NSwitch v-model:value="cfg.enable_format_conversion" />
        </div>
      </NCard>

      <!-- 请求记录 -->
      <NCard id="section-request-log" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px flex items-center justify-between">
          <div>
            <div class="text-sm font-semibold">请求记录</div>
            <div class="mt-2px text-xs text-gray-400">控制请求/响应正文的入库方式和大小（正文存储需启动配置 log.body.store=rdb）</div>
          </div>
          <NButton size="small" type="primary" :disabled="!hasChanges(SECTION_KEYS.log)" :loading="savingSection === 'log'" @click="saveSection('log')">
            保存
          </NButton>
        </div>
        <div class="grid grid-cols-1 gap-16px md:grid-cols-3">
          <div>
            <div class="mb-4px text-sm font-medium">记录详细程度</div>
            <NSelect
              v-model:value="cfg.request_record_level"
              :options="[
                { label: 'BASIC - 仅元数据与正文哈希', value: 'basic' },
                { label: 'FULL - 完整请求响应正文', value: 'full' }
              ]"
            />
            <p class="mt-4px text-xs text-gray-400">BASIC 不落正文（只存 sha256 哈希）</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">最大请求体大小 (KB)</div>
            <NInputNumber v-model:value="cfg.max_request_body_kb" :min="1" :max="10240" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">超过此大小的请求体将被截断记录</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">最大响应体大小 (KB)</div>
            <NInputNumber v-model:value="cfg.max_response_body_kb" :min="1" :max="10240" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">超过此大小的响应体将被截断记录</p>
          </div>
        </div>
      </NCard>

      <!-- 记录清理策略 -->
      <NCard id="section-cleanup" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px flex items-center justify-between">
          <div>
            <div class="text-sm font-semibold">记录清理策略</div>
            <div class="mt-2px text-xs text-gray-400">控制各类记录的保留天数，过期记录由每日清理任务删除</div>
          </div>
          <div class="flex items-center gap-12px">
            <div class="flex items-center gap-8px">
              <span class="text-xs text-gray-400">自动清理</span>
              <NSwitch :value="cfg.enable_auto_cleanup" size="small" @update:value="(v: boolean) => saveSingle('enable_auto_cleanup', v)" />
            </div>
            <NButton size="small" type="primary" :disabled="!hasChanges(SECTION_KEYS.cleanup)" :loading="savingSection === 'cleanup'" @click="saveSection('cleanup')">
              保存
            </NButton>
          </div>
        </div>
        <div class="grid grid-cols-1 gap-16px md:grid-cols-3" :class="{ 'opacity-50 pointer-events-none': !cfg.enable_auto_cleanup }">
          <div>
            <div class="mb-4px text-sm font-medium">请求日志保留天数</div>
            <NInputNumber v-model:value="cfg.log_retention_days" :min="1" :max="3650" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">网关最大表（nai_request_log）</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">异步任务保留天数</div>
            <NInputNumber v-model:value="cfg.task_retention_days" :min="1" :max="3650" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">已完结的异步任务记录</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">故障转移事件保留天数</div>
            <NInputNumber v-model:value="cfg.failover_event_retention_days" :min="1" :max="3650" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">换 Key/换渠道重试链审计</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">代理节点事件保留天数</div>
            <NInputNumber v-model:value="cfg.proxy_node_event_retention_days" :min="1" :max="3650" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">连接/断开/错误事件</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">代理节点指标保留天数</div>
            <NInputNumber v-model:value="cfg.proxy_node_metric_retention_days" :min="1" :max="3650" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">1h 采样指标桶</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">清理批次大小</div>
            <NInputNumber v-model:value="cfg.cleanup_batch_size" :min="100" :max="50000" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">每批删除行数（防长事务）</p>
          </div>
        </div>
      </NCard>

      <!-- 定时任务 -->
      <NCard id="section-scheduled" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px">
          <div class="text-sm font-semibold">定时任务</div>
          <div class="mt-2px text-xs text-gray-400">配置系统后台定时任务（进程内守护线程 + 集群 leader 锁）</div>
        </div>
        <div class="space-y-12px">
          <div
            v-for="task in scheduledTasks"
            :key="task.id"
            class="border rounded-10px p-16px transition-all duration-300"
            :class="task.enabled ? 'border-primary/30 bg-primary/[0.02] shadow-sm' : 'border-gray-200 dark:border-gray-700'"
          >
            <div class="flex items-center gap-16px">
              <NSwitch :value="task.enabled" size="small" @update:value="task.onToggle" />
              <div class="flex flex-1 items-center gap-12px min-w-0">
                <div
                  class="h-36px w-36px shrink-0 flex items-center justify-center rounded-8px"
                  :class="task.enabled ? 'bg-primary/10 text-primary' : 'text-gray-400'"
                >
                  <SvgIcon :icon="task.icon" class="text-16px" />
                </div>
                <div class="min-w-0 flex-1">
                  <div class="text-sm font-medium">{{ task.title }}</div>
                  <p class="mt-2px truncate text-xs text-gray-400">{{ task.description }}</p>
                </div>
              </div>
              <div v-if="task.enabled && task.hasTimeConfig" class="flex shrink-0 items-center gap-8px">
                <SvgIcon icon="carbon:time" class="text-gray-400" />
                <NSelect
                  :value="cleanupHour"
                  :options="timeOptions(24)"
                  size="small"
                  class="w-72px"
                  @update:value="(v: string) => updateCleanupTime(v, cleanupMinute)"
                />
                <span class="text-sm text-gray-400">:</span>
                <NSelect
                  :value="cleanupMinute"
                  :options="timeOptions(60)"
                  size="small"
                  class="w-72px"
                  @update:value="(v: string) => updateCleanupTime(cleanupHour, v)"
                />
              </div>
            </div>
          </div>
        </div>
      </NCard>

      <!-- 系统信息 -->
      <!-- 运维监控（与 /ops 页设置弹窗同 KV 源·改任一处即时生效） -->
      <NCard id="section-ops" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px flex items-center justify-between">
          <div>
            <div class="text-sm font-semibold">运维监控</div>
            <div class="mt-2px text-xs text-gray-400">告警评估与健康分阈值（与运维监控页设置弹窗同源）</div>
          </div>
          <NButton
            size="small"
            type="primary"
            :disabled="!hasChanges(SECTION_KEYS.ops)"
            :loading="savingSection === 'ops'"
            @click="saveSection('ops')"
          >
            保存
          </NButton>
        </div>
        <div class="mb-16px flex items-center justify-between border border-gray-100 rounded-8px p-16px dark:border-gray-800">
          <div>
            <div class="text-sm font-medium">告警评估</div>
            <p class="mt-2px text-xs text-gray-400">关闭后评估器空转，不再产生新告警事件（集群 leader 单跑）</p>
          </div>
          <NSwitch v-model:value="cfg.enable_ops_alert_eval" @update:value="(v: boolean) => saveSingle('enable_ops_alert_eval', v)" />
        </div>
        <div class="grid grid-cols-1 gap-16px md:grid-cols-3">
          <div>
            <div class="mb-4px text-sm font-medium">评估间隔（秒）</div>
            <NInputNumber v-model:value="cfg['ops.alert_eval_interval_seconds']" :min="10" :max="3600" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">每轮检查全部启用规则，钳 10~3600</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">SLA 下限（%）</div>
            <NInputNumber v-model:value="cfg['ops.sla_percent_min']" :min="0" :max="100" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">成功率低于此值健康分判严重</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">错误率上限（%）</div>
            <NInputNumber v-model:value="cfg['ops.error_rate_percent_max']" :min="0" :max="100" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">P99 延迟上限（ms）</div>
            <NInputNumber v-model:value="cfg['ops.p99_latency_ms_max']" :min="100" :step="1000" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">TTFT P99 上限（ms）</div>
            <NInputNumber v-model:value="cfg['ops.ttft_p99_ms_max']" :min="100" :step="500" class="w-full" />
          </div>
        </div>
      </NCard>

      <NCard id="section-sysinfo" :bordered="false" size="small" class="card-wrapper">
        <div class="mb-12px">
          <div class="text-sm font-semibold">系统信息</div>
          <div class="mt-2px text-xs text-gray-400">当前系统版本和运行信息</div>
        </div>
        <div class="grid grid-cols-2 gap-x-24px gap-y-12px md:grid-cols-4">
          <div>
            <div class="text-xs text-gray-400">版本</div>
            <div class="mt-2px text-sm font-mono">{{ sysVersion?.version ?? '加载中...' }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">Spring Boot</div>
            <div class="mt-2px text-sm font-mono">{{ sysVersion?.springBoot ?? '-' }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">Java</div>
            <div class="mt-2px text-sm font-mono">{{ sysVersion?.java ?? '-' }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">运行时长</div>
            <div class="mt-2px text-sm font-mono">{{ fmtUptime(sysVersion?.uptimeMs) }}</div>
          </div>
        </div>
      </NCard>
    </div>

    <!-- 导入配置对话框 -->
    <NModal v-model:show="importDialogOpen" preset="card" title="导入配置" class="w-520px" :segmented="{ content: true }">
      <template v-if="!importResult">
        <p class="mb-12px text-xs text-gray-400">将导入以下数据，请选择冲突合并模式后确认</p>
        <div class="mb-16px space-y-6px">
          <div v-for="item in importPreview" :key="item.key" class="flex items-center justify-between rounded-6px bg-gray-50 px-12px py-6px text-sm dark:bg-gray-900">
            <span>{{ ENTITY_LABELS[item.key] || item.key }}</span>
            <span class="font-medium tabular-nums">{{ item.count }} 条</span>
          </div>
          <div v-if="importPreview.length === 0" class="py-16px text-center text-sm text-gray-400">文件中没有可导入的数据</div>
        </div>
        <div class="mb-16px">
          <div class="mb-4px text-sm font-medium">冲突合并模式</div>
          <NSelect
            v-model:value="mergeMode"
            :options="[
              { label: '跳过 - 同 ID 已存在时保留现有数据', value: 'skip' },
              { label: '覆盖 - 同 ID 已存在时用导入数据覆盖', value: 'overwrite' }
            ]"
          />
        </div>
        <div class="flex justify-end gap-8px">
          <NButton size="small" @click="importDialogOpen = false">取消</NButton>
          <NButton size="small" type="primary" :disabled="importPreview.length === 0" :loading="importLoading" @click="confirmImport">
            确认导入
          </NButton>
        </div>
      </template>
      <template v-else>
        <p class="mb-12px text-sm font-medium">导入结果</p>
        <div class="mb-16px space-y-6px">
          <div
            v-for="(stat, entity) in importResult"
            :key="entity"
            class="flex items-center justify-between rounded-6px bg-gray-50 px-12px py-6px text-sm dark:bg-gray-900"
          >
            <span>{{ ENTITY_LABELS[entity] || entity }}</span>
            <span class="text-xs text-gray-400">新增 {{ stat.created }} · 覆盖 {{ stat.updated }} · 跳过 {{ stat.skipped }}</span>
          </div>
        </div>
        <div class="flex justify-end">
          <NButton size="small" type="primary" @click="importDialogOpen = false">完成</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
