<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NButton, NInput, NPopconfirm, NTag, NTooltip } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  fetchAbilities,
  fetchBatchChannelStatus,
  fetchBatchDeleteChannels,
  fetchChannelBalance,
  fetchChannelSummary,
  fetchCopyChannel,
  fetchDeleteChannel,
  fetchUpdateChannel
} from '@/service/api';
import ChannelDetailDrawer from './modules/channel-detail-drawer.vue';
import ChannelFormDrawer from './modules/channel-form-drawer.vue';
import ChannelSearch from './modules/channel-search.vue';
import PriorityDialog from './modules/priority-dialog.vue';

defineOptions({ name: 'Channels' });

/** 提供商管理（ProviderManagement：工具栏(搜索+筛选+调度+新增+刷新) → 表格(行内备注编辑) → 详情抽屉）。 */
const loading = ref(false);
const rows = ref<NexusAI.ChannelSummary[]>([]);

// ===== 工具栏筛选（搜索 + 状态/协议/模型） =====
const searchQuery = ref('');
const filterStatus = ref<'all' | '1' | '0'>('all');
const filterProtocol = ref('all');
const filterModel = ref('all');

const protocolFilters = computed(() => {
  const set = new Set(rows.value.map(r => r.channel.protocol).filter(Boolean) as string[]);
  return [{ label: '全部协议', value: 'all' }, ...[...set].sort().map(v => ({ label: v, value: v }))];
});

// 模型筛选：模型名 → 渠道 id 集合（路由表一次拉取构建·行数有界）
const abilityIndex = ref<Map<string, Set<number>>>(new Map());

async function loadAbilityIndex() {
  const { data, error } = await fetchAbilities();
  if (error) return;
  const index = new Map<string, Set<number>>();
  (data ?? []).forEach(a => {
    if (!a.model || a.channelId == null) return;
    if (!index.has(a.model)) index.set(a.model, new Set());
    index.get(a.model)!.add(Number(a.channelId));
  });
  abilityIndex.value = index;
}

const modelFilters = computed(() => [
  { label: '全部模型', value: 'all' },
  ...[...abilityIndex.value.keys()].sort().map(m => ({ label: m, value: m }))
]);

const hasActiveFilters = computed(
  () => searchQuery.value.trim() !== '' || filterStatus.value !== 'all' || filterProtocol.value !== 'all' || filterModel.value !== 'all'
);

function resetFilters() {
  searchQuery.value = '';
  filterStatus.value = 'all';
  filterProtocol.value = 'all';
  filterModel.value = 'all';
}

const displayedRows = computed(() => {
  const kw = searchQuery.value.trim().toLowerCase();
  return rows.value.filter(r => {
    const ch = r.channel;
    if (kw && !`${ch.name ?? ''}${ch.description ?? ''}${ch.baseUrl ?? ''}`.toLowerCase().includes(kw)) return false;
    if (filterStatus.value !== 'all' && String(ch.status) !== filterStatus.value) return false;
    if (filterProtocol.value !== 'all' && ch.protocol !== filterProtocol.value) return false;
    if (filterModel.value !== 'all' && !abilityIndex.value.get(filterModel.value)?.has(Number(ch.id))) return false;
    return true;
  });
});

async function load() {
  loading.value = true;
  try {
    const { data, error } = await fetchChannelSummary();
    if (!error) {
      rows.value = data ?? [];
      loadBalances();
    }
  } finally {
    loading.value = false;
  }
}

function refreshAll() {
  load();
  loadAbilityIndex();
}

// ===== 余额监控（逐渠道并发查上游·后端缓存 5 分钟·失败红字） =====
const balances = ref<Record<number, NexusAI.ChannelBalance>>({});
const balanceLoading = ref<Record<number, boolean>>({});

async function loadBalances() {
  rows.value.forEach(async row => {
    const id = Number(row.channel.id);
    if (!id || !row.maskedCredential) return;
    balanceLoading.value = { ...balanceLoading.value, [id]: true };
    try {
      const { data, error } = await fetchChannelBalance(id);
      if (!error && data) {
        balances.value = { ...balances.value, [id]: data };
      }
    } finally {
      balanceLoading.value = { ...balanceLoading.value, [id]: false };
    }
  });
}

// ===== 行内备注编辑 =====
const editingDescId = ref<number | null>(null);
const descDraft = ref('');

function startEditDesc(ch: NexusAI.Channel) {
  editingDescId.value = ch.id!;
  descDraft.value = ch.description ?? '';
}

async function saveDesc(ch: NexusAI.Channel) {
  const { error } = await fetchUpdateChannel(ch.id!, { description: descDraft.value.trim() });
  if (!error) {
    window.$message?.success('备注已更新');
    editingDescId.value = null;
    load();
  }
}

// ===== 表单 / 详情抽屉 / 优先级对话框 =====
const formShow = ref(false);
const formRow = ref<NexusAI.Channel | null>(null);
const detailShow = ref(false);
const detailRow = ref<NexusAI.ChannelSummary | null>(null);
const priorityShow = ref(false);

function openCreate() {
  formRow.value = null;
  formShow.value = true;
}

function openEdit(ch: NexusAI.Channel) {
  formRow.value = ch;
  formShow.value = true;
}

function openDetail(row: NexusAI.ChannelSummary) {
  detailRow.value = row;
  detailShow.value = true;
}

async function toggleStatus(row: NexusAI.ChannelSummary) {
  const ch = row.channel;
  const next = ch.status === 1 ? 0 : 1;
  const { error } = await fetchUpdateChannel(ch.id!, { status: next });
  if (!error) {
    window.$message?.success(next === 1 ? '渠道已启用' : '渠道已停用');
    load();
  }
}

async function remove(row: NexusAI.ChannelSummary) {
  const { error } = await fetchDeleteChannel(row.channel.id!);
  if (!error) {
    window.$message?.success('已删除');
    load();
  }
}

function num(value?: number | string | null): number {
  const n = Number(value);
  return Number.isNaN(n) ? 0 : n;
}

// ===== 批量操作（渠道批量启停/删除·勾选出现操作条） =====
const checkedIds = ref<number[]>([]);

async function batchStatus(status: number) {
  const { error } = await fetchBatchChannelStatus(checkedIds.value, status);
  if (!error) {
    window.$message?.success(`已${status === 1 ? '启用' : '停用'} ${checkedIds.value.length} 个渠道`);
    checkedIds.value = [];
    load();
  }
}

async function batchDelete() {
  const { data, error } = await fetchBatchDeleteChannels(checkedIds.value);
  if (!error) {
    window.$message?.success(`已删除 ${data ?? checkedIds.value.length} 个渠道（含凭证与路由）`);
    checkedIds.value = [];
    refreshAll();
  }
}

/** 渠道复制（CopyChannel·复制配置+渠道密钥+路由·副本默认停用待确认）。 */
async function copyChannel(row: NexusAI.ChannelSummary) {
  const { error } = await fetchCopyChannel(row.channel.id!);
  if (!error) {
    window.$message?.success(`已复制为 "${row.channel.name}-副本"（默认停用）`);
    refreshAll();
  }
}

/** 月卡周期剩余天数（quotaStartAt + quotaResetDays 推下次重置）。 */
function quotaResetIn(ch: NexusAI.Channel): number | null {
  const start = num(ch.quotaStartAt);
  const days = num(ch.quotaResetDays);
  if (!start || !days) return null;
  const period = days * 86400_000;
  const elapsed = (Date.now() - start) % period;
  return Math.max(0, Math.ceil((period - elapsed) / 86400_000));
}

function quotaExpired(ch: NexusAI.Channel): boolean {
  const exp = num(ch.quotaExpiresAt);
  return exp > 0 && exp < Date.now();
}

/** 健康条色（≥80 绿 / ≥50 黄 / 其余红·端点健康条配色语义）。 */
function barColorClass(percent: number) {
  if (percent >= 80) return 'bg-green-500/85';
  if (percent >= 50) return 'bg-amber-400/85';
  return 'bg-red-500/85';
}

/** 协议缩写（端点健康格标签·formatApiFormatShort 心法）。 */
function protocolShort(protocol?: string) {
  const map: Record<string, string> = {
    openai: 'OAI',
    'openai-compatible': 'OAIC',
    anthropic: 'CLD',
    gemini: 'GMN',
    azure: 'AZ',
    bedrock: 'BDR',
    vertex: 'VTX'
  };
  return map[protocol ?? ''] ?? (protocol ?? '?').slice(0, 4).toUpperCase();
}

const columns = computed<DataTableColumns<NexusAI.ChannelSummary>>(() => [
  { type: 'selection' },
  {
    title: '提供商信息',
    key: 'info',
    minWidth: 190,
    render: row => {
      const ch = row.channel;
      return (
        <div class="min-w-0 py-4px">
          <div class="flex items-center gap-6px">
            <span class="truncate text-13px font-medium">{ch.name}</span>
            {ch.website ? (
              <a
                href={ch.website}
                target="_blank"
                rel="noopener noreferrer"
                title={ch.website}
                class="shrink-0 op-40 hover:op-80"
                onClick={(e: Event) => e.stopPropagation()}
              >
                <SvgIcon icon="lucide:external-link" class="text-11px" />
              </a>
            ) : null}
          </div>
          {editingDescId.value === ch.id ? (
            <div class="mt-4px max-w-220px flex items-center gap-4px" onClick={(e: Event) => e.stopPropagation()}>
              <NInput
                size="tiny"
                value={descDraft.value}
                placeholder="输入备注..."
                onUpdateValue={(v: string) => {
                  descDraft.value = v;
                }}
                onKeydown={(e: KeyboardEvent) => {
                  if (e.key === 'Enter') saveDesc(ch);
                  if (e.key === 'Escape') editingDescId.value = null;
                }}
              />
              <NButton size="tiny" quaternary type="primary" aria-label="保存备注" onClick={() => saveDesc(ch)}>
                <SvgIcon icon="lucide:check" />
              </NButton>
              <NButton size="tiny" quaternary aria-label="取消" onClick={() => (editingDescId.value = null)}>
                <SvgIcon icon="lucide:x" />
              </NButton>
            </div>
          ) : (
            <div
              class="desc-trigger mt-2px max-w-200px cursor-pointer truncate text-12px op-55 hover:op-85"
              title={ch.description || '添加备注'}
              onClick={(e: Event) => {
                e.stopPropagation();
                startEditDesc(ch);
              }}
            >
              {ch.description || '添加备注'}
              <SvgIcon icon="lucide:pencil" class="desc-pencil ml-4px inline-block text-10px op-0" />
            </div>
          )}
        </div>
      );
    }
  },
  {
    title: '余额监控',
    key: 'balance',
    width: 170,
    render: row => {
      const id = Number(row.channel.id);
      const balance = balances.value[id];
      // 月卡模式（monthly_quota）：展示周期额度 + 重置倒计时 + 过期标记，替代上游余额查询
      if (row.channel.billingType === 'monthly_quota') {
        const resetIn = quotaResetIn(row.channel);
        const expired = quotaExpired(row.channel);
        return (
          <div class="py-2px text-12px leading-tight tabular-nums">
            <div class="flex items-center gap-4px">
              <span class="text-13px font-semibold">${Number(row.channel.quotaUsd ?? 0).toFixed(2)}</span>
              <NTag size="tiny" round bordered={false}>
                月卡
              </NTag>
              {expired ? (
                <NTag size="tiny" round type="error" bordered={false}>
                  已过期
                </NTag>
              ) : null}
            </div>
            <div class="mt-2px text-10px op-55">{resetIn == null ? '未设置重置周期' : `${resetIn} 天后重置`}</div>
          </div>
        );
      }
      if (row.channel.billingType === 'free_tier') {
        return (
          <NTag size="tiny" round bordered={false}>
            免费套餐
          </NTag>
        );
      }
      if (!row.maskedCredential) {
        return <span class="text-12px op-50">-</span>;
      }
      if (balanceLoading.value[id]) {
        return (
          <div class="flex items-center gap-6px text-12px op-60">
            <SvgIcon icon="lucide:loader-2" class="animate-spin text-12px" />
            <span>加载中...</span>
          </div>
        );
      }
      if (balance?.ok) {
        return (
          <div class="py-2px text-12px leading-tight tabular-nums">
            <div class="text-13px font-semibold">${(balance.availableUsd ?? 0).toFixed(2)}</div>
            <div class="mt-2px text-10px op-55">
              已用 ${(balance.usedUsd ?? 0).toFixed(2)} / ${(balance.totalUsd ?? 0).toFixed(2)}
            </div>
          </div>
        );
      }
      if (balance) {
        return (
          <span class="text-12px text-red-600/80 dark:text-red-400/80" title={balance.message}>
            {balance.message ?? '查询失败'}
          </span>
        );
      }
      return <span class="text-12px op-50">-</span>;
    }
  },
  {
    title: '资源统计',
    key: 'resources',
    width: 110,
    align: 'center',
    render: row => (
      <div class="inline-grid grid-cols-[auto_auto_auto] gap-x-2px gap-y-2px text-left text-12px">
        <span class="op-55">端点:</span>
        <span class="text-right font-medium tabular-nums">{row.channel.status === 1 ? 1 : 0}</span>
        <span class="op-45 tabular-nums">/1</span>
        <span class="op-55">密钥:</span>
        <span class="text-right font-medium tabular-nums">{num(row.activeKeys)}</span>
        <span class="op-45 tabular-nums">/{num(row.totalKeys)}</span>
        <span class="op-55">模型:</span>
        <span class="text-right font-medium tabular-nums">{num(row.activeModels)}</span>
        <span class="op-45 tabular-nums">/{num(row.totalModels)}</span>
      </div>
    )
  },
  {
    title: '端点健康',
    key: 'health',
    width: 220,
    render: row => {
      const score = row.channelSchedulable ? (row.healthScore ?? 100) : 0;
      const rate = row.successRate24h == null ? null : Math.round(row.successRate24h * 100);
      const cells = [
        { label: protocolShort(row.channel.protocol), percent: score, available: row.channelSchedulable },
        { label: '24h', percent: rate, available: rate != null }
      ];
      return (
        <div class="grid grid-cols-2 max-w-200px gap-x-12px gap-y-8px py-2px">
          {cells.map(cell => (
            <div class="flex flex-col gap-5px">
              <div class="flex items-center justify-between text-10px leading-none">
                <span class="font-medium op-65">{cell.label}</span>
                <span class="font-medium op-65 tabular-nums">{cell.available ? `${cell.percent}%` : '-'}</span>
              </div>
              <div class="h-5px w-full overflow-hidden rounded-full bg-gray-500/20">
                <div
                  class={`h-full rounded-full transition-all duration-300 ${
                    cell.available ? barColorClass(cell.percent ?? 0) : 'bg-red-500/85'
                  }`}
                  style={{ width: cell.available ? `${Math.max(cell.percent ?? 0, 5)}%` : '100%' }}
                />
              </div>
            </div>
          ))}
        </div>
      );
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 68,
    align: 'center',
    render: row => (
      <NTag size="small" round type={row.channel.status === 1 ? 'success' : 'default'}>
        {row.channel.status === 1 ? '活跃' : '停用'}
      </NTag>
    )
  },
  {
    title: '操作',
    key: 'actions',
    width: 165,
    align: 'center',
    render: row => (
      <div class="flex items-center justify-center gap-2px" onClick={(e: Event) => e.stopPropagation()}>
        <NTooltip>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary aria-label="查看详情" onClick={() => openDetail(row)}>
                <SvgIcon icon="lucide:eye" />
              </NButton>
            ),
            default: () => '查看详情'
          }}
        </NTooltip>
        <NTooltip>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary aria-label="编辑渠道" onClick={() => openEdit(row.channel)}>
                <SvgIcon icon="lucide:pencil" />
              </NButton>
            ),
            default: () => '编辑'
          }}
        </NTooltip>
        <NTooltip>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary aria-label="启停渠道" onClick={() => toggleStatus(row)}>
                <SvgIcon icon="lucide:power" />
              </NButton>
            ),
            default: () => (row.channel.status === 1 ? '停用' : '启用')
          }}
        </NTooltip>
        <NPopconfirm onPositiveClick={() => copyChannel(row)}>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary aria-label="复制渠道">
                <SvgIcon icon="lucide:copy" />
              </NButton>
            ),
            default: () => '复制该渠道（含渠道密钥与模型路由·副本默认停用）？'
          }}
        </NPopconfirm>
        <NPopconfirm onPositiveClick={() => remove(row)}>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary type="error" aria-label="删除渠道">
                <SvgIcon icon="lucide:trash-2" />
              </NButton>
            ),
            default: () => '确认删除该渠道？其模型路由将一并失效。'
          }}
        </NPopconfirm>
      </div>
    )
  }
]);

function rowProps(row: NexusAI.ChannelSummary) {
  return {
    style: 'cursor: pointer;',
    onClick: (e: MouseEvent) => {
      // 勾选列点击不进详情（checkbox 冒泡会误开抽屉遮挡批量条）
      if ((e.target as HTMLElement).closest('.n-checkbox')) return;
      openDetail(row);
    }
  };
}

onMounted(refreshAll);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <!-- 搜索卡（soybean 官方范式·独立 NCard 内联表单） -->
    <ChannelSearch
      v-model:keyword="searchQuery"
      v-model:status="filterStatus"
      v-model:protocol="filterProtocol"
      v-model:model="filterModel"
      :protocol-options="protocolFilters"
      :model-options="modelFilters"
    />

    <!-- 表格卡（NCard 标题 + header-extra 操作组） -->
    <NCard title="提供商管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <NSpace :size="8">
          <NButton size="small" @click="priorityShow = true">
            <template #icon>
              <SvgIcon icon="lucide:list-ordered" />
            </template>
            调度策略
          </NButton>
          <NButton size="small" type="primary" ghost @click="openCreate">
            <template #icon>
              <SvgIcon icon="ic:round-plus" />
            </template>
            新增提供商
          </NButton>
          <NButton size="small" quaternary circle :loading="loading" aria-label="刷新" @click="refreshAll">
            <template #icon>
              <SvgIcon icon="ic:round-refresh" />
            </template>
          </NButton>
        </NSpace>
      </template>

      <!-- 空态 -->
      <NEmpty
        v-if="!loading && displayedRows.length === 0"
        class="py-48px"
        :description="hasActiveFilters ? '未找到匹配当前筛选条件的提供商' : '暂无提供商，点击右上角添加'"
      >
        <template #extra>
          <NButton v-if="hasActiveFilters" size="small" @click="resetFilters">清除筛选</NButton>
        </template>
      </NEmpty>

      <template v-else>
        <!-- 批量操作条（范式：勾选后浮现） -->
        <div
          v-if="checkedIds.length > 0"
          class="mb-8px flex items-center gap-8px rounded-8px bg-primary/8 px-12px py-6px text-12px"
        >
          <span class="font-medium">已选 {{ checkedIds.length }} 个渠道</span>
          <NButton size="tiny" @click="batchStatus(1)">批量启用</NButton>
          <NButton size="tiny" @click="batchStatus(0)">批量停用</NButton>
          <NPopconfirm @positive-click="batchDelete">
            <template #trigger>
              <NButton size="tiny" type="error" ghost>批量删除</NButton>
            </template>
            确认删除选中的 {{ checkedIds.length }} 个渠道？凭证与模型路由将一并删除。
          </NPopconfirm>
          <NButton size="tiny" quaternary @click="checkedIds = []">取消选择</NButton>
        </div>
        <NDataTable
          v-model:checked-row-keys="checkedIds"
          :columns="columns"
          :data="displayedRows"
          :loading="loading"
          size="small"
          :row-key="r => r.channel.id"
          :row-props="rowProps"
          :scroll-x="940"
          class="sm:h-full"
        />
      </template>
    </NCard>

    <ChannelFormDrawer v-model:show="formShow" :row="formRow" @saved="refreshAll" />
    <ChannelDetailDrawer v-model:show="detailShow" :summary="detailRow" @refresh="refreshAll" @edit="openEdit" />
    <PriorityDialog v-model:show="priorityShow" :channels="rows" @saved="refreshAll" />
  </div>
</template>

<style scoped>
.desc-trigger:hover .desc-pencil {
  opacity: 0.5;
}
</style>
