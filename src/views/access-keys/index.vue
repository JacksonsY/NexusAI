<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton, NPopconfirm, NTag, NTooltip } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  fetchCreateAccessKey,
  fetchDeleteAccessKey,
  fetchAccessKeys,
  fetchProjects,
  fetchRotateAccessKey,
  fetchUpdateAccessKey,
  fetchUpdateAccessKeyStatus
} from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';
import AccessKeyFormDialog from './components/access-key-form-dialog.vue';
import QuotaOpsDrawer from './components/quota-ops-drawer.vue';
import InstallCliDialog from './components/install-cli-dialog.vue';

defineOptions({ name: 'AccessKeys' });

// ===== 列表状态 =====
const loading = ref(false);
const rows = ref<NexusAI.AccessKey[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const searchQuery = ref('');
const filterStatus = ref<'all' | 'active' | 'inactive'>('all');
const projectFilter = ref<string | null>(null);
const projectOptions = ref<{ label: string; value: string }[]>([]);

const statusFilters = [
  { label: '全部状态', value: 'all' },
  { label: '活跃', value: 'active' },
  { label: '禁用', value: 'inactive' }
];

async function loadProjects() {
  const { data, error } = await fetchProjects();
  if (!error && data) {
    projectOptions.value = data
      .filter(p => p.projectCode)
      .map(p => ({ label: `${p.name ?? p.projectCode}（${p.projectCode}）`, value: p.projectCode! }));
  }
}

async function loadAccessKeys() {
  loading.value = true;
  const { data } = await fetchAccessKeys({
    pageNum: page.value,
    pageSize: size.value,
    projectCode: projectFilter.value ?? undefined
  });
  // 一比一 Aether：列表只展示独立 Key（standalone===1），其余前端过滤丢弃
  rows.value = (data?.records ?? []).filter(k => k.standalone === 1);
  total.value = data?.total ?? 0;
  loading.value = false;
}

function refreshAccessKeys() {
  loadAccessKeys();
}

// 一比一 Aether：搜索（空格分隔多关键词 AND·域=name+keyHash+project）+ 状态前端过滤
const displayRows = computed(() => {
  let list = rows.value;
  const kw = searchQuery.value.trim().toLowerCase();
  if (kw) {
    const terms = kw.split(/\s+/);
    list = list.filter(k => {
      const hay = `${k.name ?? ''} ${k.keyHash ?? ''} ${k.projectCode ?? ''}`.toLowerCase();
      return terms.every(t => hay.includes(t));
    });
  }
  if (filterStatus.value === 'active') list = list.filter(k => k.status === 1);
  else if (filterStatus.value === 'inactive') list = list.filter(k => k.status !== 1);
  return list;
});

const hasActiveFilters = computed(() => !!searchQuery.value.trim() || filterStatus.value !== 'all' || !!projectFilter.value);
function clearFilters() {
  searchQuery.value = '';
  filterStatus.value = 'all';
  projectFilter.value = null;
  loadAccessKeys();
}

// ===== 工具函数 =====
function num(v: unknown) {
  const n = Number(v ?? 0);
  return Number.isFinite(n) ? n : 0;
}
/** 美元额度展示（后端回美元数值/字符串·保留 2 位小数）。 */
function formatUsd(v: unknown) {
  const n = Number(v ?? 0);
  return Number.isFinite(n) ? `$${n.toFixed(2)}` : '$0.00';
}
function shortHash(v?: string) {
  if (!v) return '****';
  return v.length > 20 ? `${v.slice(0, 10)}…${v.slice(-6)}` : v;
}
function expiryMs(v: unknown) {
  return num(v);
}
function isExpired(v: unknown) {
  const ms = expiryMs(v);
  return ms > 0 && ms < Date.now();
}
function fmtDate(v: unknown) {
  const ms = num(v);
  if (!ms) return '-';
  const d = new Date(ms);
  return Number.isNaN(d.getTime()) ? '-' : d.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-');
}
function fmtCreate(v?: string) {
  if (!v) return '-';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-');
}
function relativeExpiry(v: unknown) {
  const ms = expiryMs(v);
  if (!ms) return '';
  const diff = ms - Date.now();
  if (diff <= 0) return '已过期';
  const day = Math.floor(diff / 86400000);
  if (day >= 1) return `${day} 天后过期`;
  const hour = Math.floor(diff / 3600000);
  if (hour >= 1) return `${hour} 小时后过期`;
  return '即将过期';
}
async function copyText(t?: string) {
  if (!t) return;
  await navigator.clipboard?.writeText(t);
  window.$message?.success('已复制到剪贴板');
}

// ===== 表单弹窗 =====
const formVisible = ref(false);
const editingAccessKey = ref<NexusAI.AccessKey | null>(null);
const submitting = ref(false);
function openCreateDialog() {
  editingAccessKey.value = null;
  formVisible.value = true;
}
function editAccessKey(row: NexusAI.AccessKey) {
  editingAccessKey.value = row;
  formVisible.value = true;
}
async function handleAccessKeyFormSubmit(payload: NexusAI.AccessKeyCreateRequest & { status?: number }) {
  submitting.value = true;
  const editing = editingAccessKey.value;
  const res = editing ? await fetchUpdateAccessKey(editing.id!, payload) : await fetchCreateAccessKey(payload);
  submitting.value = false;
  if (res.error) return;
  formVisible.value = false;
  await loadAccessKeys();
  if (editing) {
    window.$message?.success('独立密钥已更新');
  } else {
    const created = res.data as NexusAI.AccessKeyCreateResponse | undefined;
    if (created?.key) showNewKey(created.key, '创建成功');
  }
}

// ===== 新 Key 明文一次性弹窗 =====
const newKeyVisible = ref(false);
const newKeyValue = ref('');
const newKeyTitle = ref('');
function showNewKey(key: string, title: string) {
  newKeyValue.value = key;
  newKeyTitle.value = title;
  newKeyVisible.value = true;
}

// ===== 额度抽屉（DollarSign·一比一 Aether WalletOpsDrawer 语义映射为子限额+项目池）=====
const quotaDrawerVisible = ref(false);
const quotaTarget = ref<NexusAI.AccessKey | null>(null);
function openAddBalanceDialog(row: NexusAI.AccessKey) {
  quotaTarget.value = row;
  quotaDrawerVisible.value = true;
}

// ===== 一键安装 CLI 弹窗（Terminal）=====
const installVisible = ref(false);
const installTarget = ref<NexusAI.AccessKey | null>(null);
function openInstallDialog(row: NexusAI.AccessKey) {
  installTarget.value = row;
  installVisible.value = true;
}

// ===== 行操作 =====
function confirmRotate(row: NexusAI.AccessKey) {
  window.$dialog?.warning({
    title: '轮转独立密钥',
    content: `为 "${row.name ?? row.id}" 生成新 sk- Key？旧 Key 将立即失效。`,
    positiveText: '轮转',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { data, error } = await fetchRotateAccessKey(row.id!);
      if (!error && data?.key) {
        await loadAccessKeys();
        showNewKey(data.key, '轮转成功');
      }
    }
  });
}
async function toggleAccessKey(row: NexusAI.AccessKey) {
  const next = row.status === 1 ? 0 : 1;
  const { error } = await fetchUpdateAccessKeyStatus(row.id!, next);
  if (!error) {
    window.$message?.success(next === 1 ? '已启用' : '已禁用');
    loadAccessKeys();
  }
}
async function deleteAccessKey(row: NexusAI.AccessKey) {
  const { error } = await fetchDeleteAccessKey(row.id!);
  if (!error) {
    window.$message?.success('独立密钥已删除');
    loadAccessKeys();
  }
}

// 访问限制 chips（一比一 Aether：模型/渠道/协议/IP/PII）
function accessChips(r: NexusAI.AccessKey) {
  const chips: string[] = [];
  if (r.modelLimits) chips.push('模型');
  if (r.allowProviders) chips.push('渠道');
  if (r.allowApiFormats) chips.push('协议');
  if (r.allowIps) chips.push('IP');
  try {
    if (r.featureSettings && JSON.parse(r.featureSettings)?.chat_pii_redaction?.enabled) chips.push('PII');
  } catch {
    /* ignore */
  }
  return chips;
}

// ===== 桌面 8 列（一比一 Aether AccessKeys）=====
const columns: DataTableColumns<NexusAI.AccessKey> = [
  {
    title: '密钥信息',
    key: 'name',
    width: 220,
    render: r => (
      <div class="flex flex-col gap-2px py-2px leading-tight">
        <span class="text-14px font-medium">{r.name || '未命名 Key'}</span>
        <div class="flex items-center gap-4px">
          <span class="font-mono text-12px op-55">{shortHash(r.keyHash)}</span>
          <NButton size="tiny" quaternary circle onClick={() => copyText(r.keyHash)}>
            {{ icon: () => <SvgIcon icon="lucide:copy" class="text-11px" /> }}
          </NButton>
        </div>
      </div>
    )
  },
  {
    title: '额度($)',
    key: 'quota',
    width: 180,
    render: r => (
      <div class="flex flex-col gap-2px text-12px leading-tight">
        {num(r.quotaUsd) > 0 ? (
          <span>
            子限额 <span class="font-medium tabular-nums">{formatUsd(r.quotaUsd)}</span>
          </span>
        ) : (
          <NTag size="tiny" bordered={false} type="success">
            不设子限额
          </NTag>
        )}
        <span class="op-55">{r.workspaceId ? `工作区 ${r.workspaceId}` : '项目池计费'}</span>
      </div>
    )
  },
  {
    title: '统计/限制',
    key: 'limits',
    width: 190,
    render: r => {
      const chips = accessChips(r);
      return (
        <div class="flex flex-col gap-3px text-12px leading-tight">
          <span class="op-70">
            RPM {num(r.rpmLimit) || '∞'} · TPM {num(r.tpmLimit) || '∞'} · 并发 {num(r.concurrentLimit) || '∞'}
          </span>
          {chips.length ? (
            <div class="flex flex-wrap gap-3px">
              {chips.map(c => (
                <NTag size="tiny" bordered={false} type="warning">
                  {c}
                </NTag>
              ))}
            </div>
          ) : (
            <span class="op-45">无访问限制</span>
          )}
        </div>
      );
    }
  },
  { title: '创建时间', key: 'createDt', width: 150, render: r => <span class="text-12px">{fmtCreate(r.createDt)}</span> },
  {
    title: '有效期',
    key: 'expiredTime',
    width: 130,
    render: r => {
      if (!expiryMs(r.expiredTime)) return <span class="op-50 text-12px">永不过期</span>;
      return (
        <div class="flex flex-col gap-2px text-12px leading-tight">
          <span>{fmtDate(r.expiredTime)}</span>
          <span class={isExpired(r.expiredTime) ? 'text-error' : 'op-55'}>
            {relativeExpiry(r.expiredTime)}
            {r.autoDeleteOnExpiry === 1 ? ' · 到期删除' : ''}
          </span>
        </div>
      );
    }
  },
  { title: '最近使用', key: 'lastUsed', width: 130, render: () => <span class="op-45 text-12px">暂无记录</span> },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: r => {
      if (isExpired(r.expiredTime)) return <NTag size="small" type="warning">已过期</NTag>;
      return (
        <NTag size="small" type={r.status === 1 ? 'success' : 'error'}>
          {r.status === 1 ? '活跃' : '禁用'}
        </NTag>
      );
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    align: 'center',
    render: row => (
      <div class="flex items-center justify-center gap-2px">
        <NTooltip>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary circle onClick={() => openInstallDialog(row)}>
                {{ icon: () => <SvgIcon icon="lucide:terminal" /> }}
              </NButton>
            ),
            default: () => '一键安装并配置 CLI'
          }}
        </NTooltip>
        <NTooltip>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary circle onClick={() => editAccessKey(row)}>
                {{ icon: () => <SvgIcon icon="lucide:square-pen" /> }}
              </NButton>
            ),
            default: () => '编辑'
          }}
        </NTooltip>
        <NTooltip>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary circle onClick={() => openAddBalanceDialog(row)}>
                {{ icon: () => <SvgIcon icon="lucide:gauge" /> }}
              </NButton>
            ),
            default: () => '额度管理'
          }}
        </NTooltip>
        <NPopconfirm onPositiveClick={() => toggleAccessKey(row)}>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary circle type={row.status === 1 ? 'error' : 'success'}>
                {{ icon: () => <SvgIcon icon="lucide:power" /> }}
              </NButton>
            ),
            default: () => (row.status === 1 ? '停用后该 Key 下一请求即失效，确认？' : '恢复该 Key 的调用能力？')
          }}
        </NPopconfirm>
        <NPopconfirm onPositiveClick={() => deleteAccessKey(row)}>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary circle type="error">
                {{ icon: () => <SvgIcon icon="lucide:trash-2" /> }}
              </NButton>
            ),
            default: () => '删除后该 sk- 永久失效，确认？'
          }}
        </NPopconfirm>
      </div>
    )
  }
];

onMounted(() => {
  loadProjects();
  loadAccessKeys();
});
</script>

<template>
  <div class="flex flex-col gap-16px pb-8">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <template #header>
        <span class="text-15px font-semibold">独立余额 Access Keys</span>
      </template>
      <template #header-extra>
        <div class="flex flex-wrap items-center gap-8px">
          <NInput v-model:value="searchQuery" clearable size="small" placeholder="搜索..." class="w-160px sm:w-220px">
            <template #prefix>
              <SvgIcon icon="lucide:search" class="op-50" />
            </template>
          </NInput>
          <div class="hidden h-16px w-1px bg-[var(--n-border-color)] sm:block" />
          <NSelect
            v-model:value="projectFilter"
            :options="projectOptions"
            clearable
            filterable
            size="small"
            placeholder="全部项目"
            class="w-180px"
            @update:value="loadAccessKeys"
          />
          <NSelect v-model:value="filterStatus" :options="statusFilters" size="small" class="w-120px" />
          <div class="hidden h-16px w-1px bg-[var(--n-border-color)] sm:block" />
          <NTooltip>
            <template #trigger>
              <NButton size="small" type="primary" ghost circle @click="openCreateDialog">
                <template #icon>
                  <SvgIcon icon="lucide:plus" />
                </template>
              </NButton>
            </template>
            创建独立 Key
          </NTooltip>
          <NButton size="small" quaternary circle :loading="loading" @click="refreshAccessKeys">
            <template #icon>
              <SvgIcon icon="lucide:refresh-cw" />
            </template>
          </NButton>
        </div>
      </template>

      <NText depth="3" class="mb-3 block text-12px">
        独立密钥（standalone sk-）由控制台自管，挂项目计费、扣项目池/工作区额度，明文只在创建或轮转后显示一次。
      </NText>

      <!-- 桌面：8 列表格（一比一 Aether） -->
      <div class="hidden xl:block">
        <NDataTable
          :columns="columns"
          :data="displayRows"
          :loading="loading"
          :bordered="false"
          size="small"
          :row-key="r => r.id"
          :scroll-x="1200"
        />
      </div>

      <!-- 移动/窄屏：卡片列表（一比一 Aether <xl 卡片） -->
      <div class="flex flex-col gap-10px xl:hidden">
        <div v-for="r in displayRows" :key="r.id" class="border border-[var(--n-border-color)] rounded-8px p-12px">
          <div class="flex items-center justify-between">
            <span class="font-mono text-13px">{{ shortHash(r.keyHash) }}</span>
            <NButton size="tiny" quaternary circle @click="copyText(r.keyHash)">
              <template #icon><SvgIcon icon="lucide:copy" /></template>
            </NButton>
          </div>
          <div class="mt-2px text-13px font-medium">{{ r.name || '未命名 Key' }}</div>
          <div class="mt-6px flex flex-wrap gap-4px">
            <NTag size="tiny" :type="r.status === 1 ? 'success' : 'error'" :bordered="false">
              {{ isExpired(r.expiredTime) ? '已过期' : r.status === 1 ? '活跃' : '禁用' }}
            </NTag>
            <NTag size="tiny" :bordered="false">{{ r.workspaceId ? `工作区 ${r.workspaceId}` : '项目池' }}</NTag>
            <NTag v-for="c in accessChips(r)" :key="c" size="tiny" type="warning" :bordered="false">{{ c }}</NTag>
          </div>
          <div class="mt-8px grid grid-cols-2 gap-6px text-12px op-70">
            <div>子限额：{{ num(r.quotaUsd) > 0 ? formatUsd(r.quotaUsd) : '不设' }}</div>
            <div>RPM/并发：{{ num(r.rpmLimit) || '∞' }} / {{ num(r.concurrentLimit) || '∞' }}</div>
            <div>有效期：{{ expiryMs(r.expiredTime) ? fmtDate(r.expiredTime) : '永不过期' }}</div>
            <div>创建：{{ fmtCreate(r.createDt) }}</div>
          </div>
          <div class="mt-8px flex flex-wrap gap-4px">
            <NButton size="tiny" quaternary @click="openInstallDialog(r)">安装</NButton>
            <NButton size="tiny" quaternary @click="editAccessKey(r)">编辑</NButton>
            <NButton size="tiny" quaternary @click="openAddBalanceDialog(r)">额度</NButton>
            <NButton size="tiny" quaternary :type="r.status === 1 ? 'error' : 'success'" @click="toggleAccessKey(r)">
              {{ r.status === 1 ? '禁用' : '启用' }}
            </NButton>
            <NPopconfirm @positive-click="() => deleteAccessKey(r)">
              <template #trigger>
                <NButton size="tiny" quaternary type="error">删除</NButton>
              </template>
              删除后该 sk- 永久失效，确认？
            </NPopconfirm>
          </div>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="!loading && !displayRows.length" class="py-40px text-center op-50">
        <SvgIcon icon="lucide:key-round" class="mx-auto mb-8px text-32px" />
        <p v-if="hasActiveFilters">未找到匹配的 Key</p>
        <p v-else>暂无独立余额 Key</p>
        <NButton v-if="hasActiveFilters" size="small" class="mt-8px" @click="clearFilters">清除筛选</NButton>
      </div>

      <div class="mt-12px flex justify-end">
        <NPagination v-model:page="page" :page-size="size" :item-count="total" @update:page="loadAccessKeys" />
      </div>
    </NCard>

    <AccessKeyFormDialog
      v-model:show="formVisible"
      :editing="editingAccessKey"
      :project-options="projectOptions"
      :submitting="submitting"
      @submit="handleAccessKeyFormSubmit"
    />

    <QuotaOpsDrawer v-model:show="quotaDrawerVisible" :target="quotaTarget" @changed="loadAccessKeys" />

    <InstallCliDialog v-model:show="installVisible" :target="installTarget" />

    <NModal
      v-model:show="newKeyVisible"
      preset="card"
      :title="newKeyTitle"
      class="w-560px max-w-[94vw]"
      :bordered="false"
      size="small"
    >
      <NAlert type="success" :bordered="false" class="mb-3">
        <template #icon><SvgIcon icon="lucide:circle-check" /></template>
        请妥善保管，切勿泄露给他人。明文 Key 只显示这一次，关闭后只能轮转生成新的。
      </NAlert>
      <NInput :value="newKeyValue" readonly type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" class="font-mono" />
      <div class="mt-4 flex justify-end gap-8px">
        <NButton @click="copyText(newKeyValue)">
          <template #icon><SvgIcon icon="lucide:copy" /></template>
          复制
        </NButton>
        <NButton type="primary" @click="newKeyVisible = false">确定</NButton>
      </div>
    </NModal>
  </div>
</template>
