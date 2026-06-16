<script setup lang="tsx">
import { computed, h, onMounted, ref, watch } from 'vue';
import { NButton, NPopconfirm, NTag, NText } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createProxyNode,
  deleteProxyNode,
  fetchProxyNode,
  fetchProxyNodeEvents,
  fetchProxyNodes,
  testProxyNode,
  testProxyUrl,
  updateProxyNode
} from '@/service/api';
import NodeDataPanel from './modules/node-data-panel.vue';

/**
 * 代理节点（ProxyNodes·手动 HTTP/SOCKS5 出口代理）：
 * 标题栏（搜索/状态筛选/手动添加/刷新）→ 表格（展开数据面板/测试/编辑/事件/删除）→
 * 添加对话框（手动/批量两模式·测试连通性）→ 连接事件对话框。
 */
defineOptions({ name: 'ProxyNodes' });

const loading = ref(false);
const nodes = ref<NexusAI.ProxyNode[]>([]);
const searchQuery = ref('');
const filterStatus = ref<'all' | 'online' | 'offline'>('all');
const page = ref(1);
const pageSize = ref(20);

const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 'online', label: '在线' },
  { value: 'offline', label: '离线' }
];

const filteredNodes = computed(() => {
  let list = [...nodes.value];
  const q = searchQuery.value.trim().toLowerCase();
  if (q) {
    const keywords = q.split(/\s+/).filter(Boolean);
    list = list.filter(n => {
      const text = `${n.name} ${n.proxyUrl} ${n.region || ''}`.toLowerCase();
      return keywords.every(kw => text.includes(kw));
    });
  }
  if (filterStatus.value !== 'all') {
    list = list.filter(n => n.status === filterStatus.value);
  }
  return list;
});

watch([searchQuery, filterStatus], () => {
  page.value = 1;
});

async function refresh() {
  loading.value = true;
  const { data, error } = await fetchProxyNodes();
  if (!error) nodes.value = data ?? [];
  loading.value = false;
}

// ===== 测试连通性 =====
const testingIds = ref(new Set<string>());

function testParts(r: NexusAI.ProxyTestResult) {
  const parts = [`探测: ${shortProbe(r.probeUrl)}`, `超时: ${r.timeoutSecs}s`, `延迟: ${r.latencyMs != null ? `${r.latencyMs}ms` : '暂无样本'}`];
  if (r.exitIp) parts.push(`出口IP: ${r.exitIp}`);
  return parts.join('，');
}

function shortProbe(url?: string) {
  if (!url) return '-';
  try {
    const u = new URL(url);
    return `${u.host}${u.pathname === '/' ? '' : u.pathname}`;
  } catch {
    return url;
  }
}

async function handleTest(node: NexusAI.ProxyNode) {
  if (testingIds.value.has(node.id)) return;
  testingIds.value = new Set([...testingIds.value, node.id]);
  const { data, error } = await testProxyNode(node.id);
  const next = new Set(testingIds.value);
  next.delete(node.id);
  testingIds.value = next;
  if (error || !data) return;
  if (data.success) {
    window.$message?.success(`连通性测试通过，${testParts(data)}`);
  } else {
    window.$message?.error(`连通性测试失败（${testParts(data)}）: ${data.error || '未知错误'}`);
  }
}

// ===== 添加 / 编辑 =====
const showAddModal = ref(false);
const addMode = ref<'manual' | 'batch'>('manual');
const editingId = ref<string | null>(null);
const saving = ref(false);
const testingUrl = ref(false);
const addForm = ref({ name: '', proxyUrl: '', username: '', password: '', region: '' });
const batchContent = ref('');

interface BatchNode {
  name: string;
  proxyUrl: string;
  username?: string;
  password?: string;
}

/** 批量解析（proxy-node-batch：行/逗号分隔·URL 内嵌账密自动拆分·名称=主机:端口） */
const batchParsed = computed<{ nodes: BatchNode[]; errors: string[] }>(() => {
  const entries = batchContent.value
    .split(/[\n,]+/)
    .map(e => e.trim())
    .filter(Boolean);
  const out: BatchNode[] = [];
  const errors: string[] = [];
  entries.forEach((entry, i) => {
    try {
      const url = new URL(entry);
      if (!['http:', 'https:', 'socks5:', 'socks5h:'].includes(url.protocol)) {
        throw new Error('仅支持 http/https/socks5/socks5h 协议');
      }
      if (!url.hostname) throw new Error('缺少主机地址');
      const username = url.username ? decodeURIComponent(url.username) : '';
      const password = url.password ? decodeURIComponent(url.password) : '';
      if (!username && password) throw new Error('包含密码时必须同时包含用户名');
      const port = url.port || (url.protocol.startsWith('socks5') ? '1080' : url.protocol === 'https:' ? '443' : '80');
      out.push({
        name: `${url.hostname}:${port}`,
        proxyUrl: `${url.protocol}//${url.hostname}:${port}`,
        username: username || undefined,
        password: password || undefined
      });
    } catch (err) {
      errors.push(`第 ${i + 1} 条 ${entry}: ${err instanceof Error ? err.message : '格式不正确'}`);
    }
  });
  return { nodes: out, errors };
});

function openAdd() {
  editingId.value = null;
  addMode.value = 'manual';
  addForm.value = { name: '', proxyUrl: '', username: '', password: '', region: '' };
  batchContent.value = '';
  showAddModal.value = true;
}

async function openEdit(node: NexusAI.ProxyNode) {
  const { data, error } = await fetchProxyNode(node.id);
  if (error || !data) return;
  editingId.value = node.id;
  addMode.value = 'manual';
  addForm.value = {
    name: data.name,
    proxyUrl: data.proxyUrl,
    username: data.username || '',
    password: data.password || '',
    region: data.region || ''
  };
  showAddModal.value = true;
}

async function handleTestUrl() {
  if (!addForm.value.proxyUrl || testingUrl.value) return;
  testingUrl.value = true;
  const { data, error } = await testProxyUrl({
    proxyUrl: addForm.value.proxyUrl,
    username: addForm.value.username || undefined,
    password: addForm.value.password || undefined
  });
  testingUrl.value = false;
  if (error || !data) return;
  if (data.success) {
    window.$message?.success(`连通性测试通过，${testParts(data)}`);
  } else {
    window.$message?.error(`连通性测试失败（${testParts(data)}）: ${data.error || '未知错误'}`);
  }
}

async function handleSave() {
  if (!addForm.value.name || !addForm.value.proxyUrl) return;
  saving.value = true;
  const body = {
    name: addForm.value.name,
    proxyUrl: addForm.value.proxyUrl,
    username: addForm.value.username || undefined,
    password: addForm.value.password || undefined,
    region: addForm.value.region || undefined
  };
  const { error } = editingId.value ? await updateProxyNode(editingId.value, body) : await createProxyNode(body);
  saving.value = false;
  if (error) return;
  window.$message?.success(editingId.value ? '代理节点已更新' : '代理节点已添加');
  showAddModal.value = false;
  await refresh();
}

async function handleBatchAdd() {
  const { nodes: parsed, errors } = batchParsed.value;
  if (errors.length > 0) {
    window.$message?.error(`批量输入存在 ${errors.length} 条格式错误，请先修正后再添加`);
    return;
  }
  if (parsed.length === 0) {
    window.$message?.error('请先输入至少一条代理地址');
    return;
  }
  saving.value = true;
  let ok = 0;
  const failures: string[] = [];
  for (const n of parsed) {
    const { error } = await createProxyNode(n);
    if (error) failures.push(n.name);
    else ok += 1;
  }
  saving.value = false;
  await refresh();
  if (failures.length === 0) {
    window.$message?.success(`已添加 ${ok} 个代理节点`);
    showAddModal.value = false;
  } else {
    window.$message?.warning(`已添加 ${ok} 个代理节点，${failures.length} 个失败: ${failures.slice(0, 3).join('、')}`);
  }
}

async function handleDelete(node: NexusAI.ProxyNode) {
  const { data, error } = await deleteProxyNode(node.id);
  if (error) return;
  window.$message?.success(data?.clearedSystemProxy ? '代理节点已删除，系统默认代理已自动清除' : '代理节点已删除');
  await refresh();
}

// ===== 连接事件 =====
const showEventsModal = ref(false);
const eventsNode = ref<NexusAI.ProxyNode | null>(null);
const nodeEvents = ref<NexusAI.ProxyNodeEvent[]>([]);
const loadingEvents = ref(false);

async function openEvents(node: NexusAI.ProxyNode) {
  eventsNode.value = node;
  showEventsModal.value = true;
  loadingEvents.value = true;
  const { data, error } = await fetchProxyNodeEvents(node.id, 50);
  if (!error) nodeEvents.value = data ?? [];
  loadingEvents.value = false;
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

// ===== 表格 =====
const fmtNum = (n?: number | string | null) => (n === null || n === undefined ? '0' : Number(n).toLocaleString());

function failureRate(n: NexusAI.ProxyNode) {
  const total = Number(n.totalRequests ?? 0);
  if (!total) return 0;
  return (Number(n.failedRequests ?? 0) / total) * 100;
}

function fmtFailureRate(n: NexusAI.ProxyNode) {
  const total = Number(n.totalRequests ?? 0);
  if (!total) return '-';
  const rate = failureRate(n);
  if (rate === 0) return '0%';
  if (rate < 0.1) return '<0.1%';
  return `${rate.toFixed(1)}%`;
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

const columns = computed<DataTableColumns<NexusAI.ProxyNode>>(() => [
  {
    type: 'expand',
    width: 36,
    renderExpand: row => h(NodeDataPanel, { node: row })
  },
  {
    key: 'name',
    title: '名称',
    minWidth: 150,
    render: row => (
      <div class="flex items-center gap-6px">
        <span class="text-sm font-semibold">{row.name}</span>
        <NTag size="tiny" bordered={false}>手动</NTag>
      </div>
    )
  },
  {
    key: 'proxyUrl',
    title: '地址',
    minWidth: 190,
    render: row => <NText code class="text-xs">{row.proxyUrl}</NText>
  },
  {
    key: 'region',
    title: '区域',
    width: 100,
    render: row => <NText depth={3}>{row.region || '-'}</NText>
  },
  {
    key: 'status',
    title: '状态',
    width: 90,
    align: 'center',
    render: row => (
      <NTag size="small" type={row.status === 'online' ? 'success' : 'error'} bordered={false}>
        {row.status === 'online' ? '在线' : '离线'}
      </NTag>
    )
  },
  {
    key: 'totalRequests',
    title: '总请求',
    width: 100,
    align: 'center',
    render: row => <span class="tabular-nums">{fmtNum(row.totalRequests)}</span>
  },
  {
    key: 'failedRate',
    title: '失败率',
    width: 100,
    align: 'center',
    render: row => (
      <span class={`tabular-nums ${failureRate(row) > 5 ? 'text-error font-medium' : ''}`}>{fmtFailureRate(row)}</span>
    )
  },
  {
    key: 'avgLatencyMs',
    title: '延迟',
    width: 90,
    align: 'center',
    render: row => <span class="tabular-nums">{row.avgLatencyMs != null ? `${Math.round(row.avgLatencyMs)}ms` : '-'}</span>
  },
  {
    key: 'lastProbeAt',
    title: '最后探测',
    width: 130,
    render: row => <span class="text-xs text-gray-400">{fmtTime(row.lastProbeAt)}</span>
  },
  {
    key: 'actions',
    title: '操作',
    width: 170,
    align: 'center',
    render: row => (
      <div class="flex items-center justify-center gap-2px">
        <NButton size="tiny" quaternary loading={testingIds.value.has(row.id)} title="测试连通性" onClick={() => handleTest(row)}>
          {{ icon: () => <SvgIcon icon="carbon:activity" /> }}
        </NButton>
        <NButton size="tiny" quaternary title="编辑" onClick={() => openEdit(row)}>
          {{ icon: () => <SvgIcon icon="carbon:edit" /> }}
        </NButton>
        <NButton size="tiny" quaternary title="连接事件" onClick={() => openEvents(row)}>
          {{ icon: () => <SvgIcon icon="carbon:recently-viewed" /> }}
        </NButton>
        <NPopconfirm onPositiveClick={() => handleDelete(row)}>
          {{
            default: () => `确定要删除代理节点 "${row.name}" 吗？`,
            trigger: () => (
              <NButton size="tiny" quaternary type="error" title="删除">
                {{ icon: () => <SvgIcon icon="carbon:trash-can" /> }}
              </NButton>
            )
          }}
        </NPopconfirm>
      </div>
    )
  }
]);

onMounted(refresh);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <!-- 标题和筛选器（顶栏） -->
      <div class="mb-12px flex flex-wrap items-center justify-between gap-12px">
        <span class="text-base font-semibold">代理节点</span>
        <div class="flex flex-wrap items-center gap-8px">
          <NInput v-model:value="searchQuery" size="small" placeholder="搜索..." clearable class="w-200px">
            <template #prefix>
              <SvgIcon icon="carbon:search" class="text-gray-400" />
            </template>
          </NInput>
          <NSelect v-model:value="filterStatus" size="small" :options="statusOptions" class="w-110px" />
          <NButton size="small" type="primary" @click="openAdd">
            <template #icon>
              <SvgIcon icon="carbon:add" />
            </template>
            添加
          </NButton>
          <NButton size="small" quaternary :loading="loading" @click="refresh">
            <template #icon>
              <SvgIcon icon="carbon:renew" />
            </template>
          </NButton>
        </div>
      </div>

      <NDataTable
        :columns="columns"
        :data="filteredNodes"
        :loading="loading"
        :row-key="(row: NexusAI.ProxyNode) => row.id"
        size="small"
        :pagination="{
          page,
          pageSize,
          itemCount: filteredNodes.length,
          showSizePicker: true,
          pageSizes: [10, 20, 50],
          onUpdatePage: (p: number) => (page = p),
          onUpdatePageSize: (ps: number) => {
            pageSize = ps;
            page = 1;
          }
        }"
        class="sm:h-full"
      >
        <template #empty>
          <div class="py-48px text-center text-sm text-gray-400">{{ loading ? '加载中...' : '暂无代理节点' }}</div>
        </template>
      </NDataTable>
    </NCard>

    <!-- 添加/编辑对话框（手动/批量两模式） -->
    <NModal
      v-model:show="showAddModal"
      preset="card"
      :title="editingId ? '编辑代理节点' : '添加代理节点'"
      class="w-560px"
      :segmented="{ content: true }"
    >
      <p v-if="!editingId" class="mb-12px text-xs text-gray-400">手动或批量添加已有 HTTP/SOCKS 代理，添加后由探测任务每分钟检查连通性</p>

      <NTabs v-if="!editingId" v-model:value="addMode" type="segment" size="small" class="mb-16px">
        <NTab name="manual">手动添加</NTab>
        <NTab name="batch">批量添加</NTab>
      </NTabs>

      <template v-if="addMode === 'manual' || editingId">
        <NForm label-placement="top" size="small">
          <NFormItem label="名称 *">
            <NInput v-model:value="addForm.name" placeholder="例如: 美西 VPN 代理" />
          </NFormItem>
          <NFormItem label="代理地址 *">
            <NInput v-model:value="addForm.proxyUrl" placeholder="http://proxy:port 或 socks5://proxy:port" />
          </NFormItem>
          <div class="grid grid-cols-2 gap-12px">
            <NFormItem label="用户名">
              <NInput v-model:value="addForm.username" placeholder="可选" />
            </NFormItem>
            <NFormItem label="密码">
              <NInput
                v-model:value="addForm.password"
                type="password"
                show-password-on="click"
                :input-props="{ autocomplete: 'new-password' }"
                placeholder="可选"
              />
            </NFormItem>
          </div>
          <NFormItem label="区域">
            <NInput v-model:value="addForm.region" placeholder="可选，例如: US-West" />
          </NFormItem>
        </NForm>
        <div class="flex items-center justify-between">
          <NButton size="small" :disabled="testingUrl || !addForm.proxyUrl" :loading="testingUrl" @click="handleTestUrl">
            {{ testingUrl ? '测试中...' : '测试' }}
          </NButton>
          <div class="flex items-center gap-8px">
            <NButton size="small" @click="showAddModal = false">取消</NButton>
            <NButton size="small" type="primary" :disabled="saving || !addForm.name || !addForm.proxyUrl" :loading="saving" @click="handleSave">
              {{ editingId ? '保存' : '添加' }}
            </NButton>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="mb-12px border border-gray-100 rounded-8px bg-gray-50 p-12px text-xs text-gray-400 dark:border-gray-800 dark:bg-gray-900">
          支持一行一个，或使用英文逗号分隔。URL 中的用户名和密码会自动拆分存储，节点名称自动使用主机和端口。
        </div>
        <NInput
          v-model:value="batchContent"
          type="textarea"
          :rows="7"
          class="font-mono text-xs"
          placeholder="socks5://username:password@1.2.3.4:1080&#10;http://username:password@5.6.7.8:8080"
        />
        <div v-if="batchParsed.errors.length" class="mt-8px border border-red-200 rounded-8px bg-red-50 p-12px text-xs text-red-600 dark:border-red-900 dark:bg-red-950">
          <div class="font-medium">有 {{ batchParsed.errors.length }} 条格式错误</div>
          <ul class="mt-4px space-y-2px">
            <li v-for="msg in batchParsed.errors.slice(0, 3)" :key="msg">{{ msg }}</li>
          </ul>
          <div v-if="batchParsed.errors.length > 3" class="mt-4px">还有 {{ batchParsed.errors.length - 3 }} 条错误未显示</div>
        </div>
        <p v-else-if="batchContent.trim()" class="mt-8px text-xs text-gray-400">已识别 {{ batchParsed.nodes.length }} 个代理节点。</p>
        <div class="mt-16px flex items-center justify-between">
          <span class="text-xs text-gray-400">
            {{ batchContent.trim() ? `待添加 ${batchParsed.nodes.length} 个` : '等待输入代理地址' }}
          </span>
          <div class="flex items-center gap-8px">
            <NButton size="small" @click="showAddModal = false">取消</NButton>
            <NButton
              size="small"
              type="primary"
              :disabled="saving || !batchContent.trim() || batchParsed.errors.length > 0 || batchParsed.nodes.length === 0"
              :loading="saving"
              @click="handleBatchAdd"
            >
              批量添加
            </NButton>
          </div>
        </div>
      </template>
    </NModal>

    <!-- 连接事件对话框 -->
    <NModal v-model:show="showEventsModal" preset="card" title="连接事件" class="w-640px" :segmented="{ content: true }">
      <p class="mb-12px text-xs text-gray-400">{{ eventsNode ? `${eventsNode.name} 的连接历史` : '' }}</p>
      <div v-if="eventsNode" class="grid grid-cols-3 mb-12px gap-12px text-sm">
        <div class="rounded-8px bg-gray-50 px-12px py-8px text-center dark:bg-gray-900">
          <span class="block text-xs text-gray-400">失败请求</span>
          <span class="font-medium tabular-nums">{{ fmtNum(eventsNode.failedRequests) }}</span>
        </div>
        <div class="rounded-8px bg-gray-50 px-12px py-8px text-center dark:bg-gray-900">
          <span class="block text-xs text-gray-400">总请求</span>
          <span class="font-medium tabular-nums">{{ fmtNum(eventsNode.totalRequests) }}</span>
        </div>
        <div class="rounded-8px bg-gray-50 px-12px py-8px text-center dark:bg-gray-900">
          <span class="block text-xs text-gray-400">探测延迟</span>
          <span class="font-medium tabular-nums">{{ eventsNode.avgLatencyMs != null ? `${Math.round(eventsNode.avgLatencyMs)}ms` : '-' }}</span>
        </div>
      </div>
      <div v-if="loadingEvents" class="py-32px text-center text-sm text-gray-400">加载中...</div>
      <div v-else-if="nodeEvents.length === 0" class="py-32px text-center text-sm text-gray-400">暂无连接事件记录</div>
      <div v-else class="max-h-320px space-y-6px overflow-y-auto">
        <div v-for="e in nodeEvents" :key="e.id" class="flex items-center gap-8px rounded-8px bg-gray-50 px-12px py-8px text-sm dark:bg-gray-900">
          <NTag size="tiny" :type="eventTagType(e.eventType)" :bordered="false">{{ eventLabel(e.eventType) }}</NTag>
          <span class="flex-1 truncate text-gray-400">{{ e.detail || '-' }}</span>
          <span class="shrink-0 text-xs text-gray-400 tabular-nums">{{ fmtTime(e.createDt) }}</span>
        </div>
      </div>
    </NModal>
  </div>
</template>

<style scoped></style>
