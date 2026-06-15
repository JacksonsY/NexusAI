<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { NButton, NPopconfirm, NTag } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { fetchWorkspaceStatus, fetchWorkspaces, fetchProjects } from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';
import type { DataTableColumns } from 'naive-ui';
import WorkspaceOperateModal from './modules/workspace-operate-modal.vue';
import WorkspaceQuotaModal from './modules/workspace-quota-modal.vue';

/**
 * 项目内工作区管理（三层身份链·B2C 终端工作区钱包）：项目筛选 + 后端分页列表 +
 * 新建/充值（契约③ workspace 维度）/停用。B2B 项目（token 直挂项目池）无需建工作区。
 */
defineOptions({ name: 'Workspaces' });

const appStore = useAppStore();

/** 美元额度展示（后端回美元数值/字符串·保留 2 位小数）。 */
function formatUsd(v?: number | string | null) {
  if (v === null || v === undefined || v === '') {
    return '-';
  }
  const n = Number(v);
  return Number.isFinite(n) ? `$${n.toFixed(2)}` : '-';
}

// 筛选（项目下拉 + 关键字·后端分页）
const projectOptions = ref<{ label: string; value: string }[]>([]);
const filterProject = ref<string | null>(null);
const keyword = ref('');
const loading = ref(false);
const rows = ref<NexusAI.Workspace[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);

async function loadProjects() {
  const { data, error } = await fetchProjects();
  if (!error && data) {
    projectOptions.value = data.map(p => ({ label: `${p.name}（${p.projectCode}）`, value: p.projectCode! }));
  }
}

async function loadUsers() {
  loading.value = true;
  const { data, error } = await fetchWorkspaces({
    pageNum: page.value,
    pageSize: pageSize.value,
    projectCode: filterProject.value ?? undefined,
    keyword: keyword.value.trim() || undefined
  });
  if (!error && data) {
    rows.value = data.records ?? [];
    total.value = Number(data.total ?? 0);
  }
  loading.value = false;
}

function search() {
  page.value = 1;
  loadUsers();
}

async function toggleStatus(row: NexusAI.Workspace) {
  const next = row.status === 1 ? 0 : 1;
  const { error } = await fetchWorkspaceStatus(row.id!, next);
  if (!error) {
    window.$message?.success(next === 1 ? '工作区已启用' : '工作区已停用（其全部 Key 即时失效）');
    loadUsers();
  }
}

const columns: DataTableColumns<NexusAI.Workspace> = [
  {
    title: '工作区',
    key: 'externalId',
    minWidth: 180,
    render: r => (
      <div class="flex flex-col gap-2px py-2px leading-tight">
        <span class="text-14px font-medium">{r.displayName || r.externalId}</span>
        <span class="font-mono text-12px op-60">{r.externalId}</span>
      </div>
    )
  },
  {
    title: '项目',
    key: 'projectCode',
    width: 140,
    render: r => (
      <NTag size="small" bordered={false}>
        {r.projectCode}
      </NTag>
    )
  },
  {
    title: '钱包($)',
    key: 'quotaUsd',
    width: 150,
    render: r => (
      <NButton size="tiny" quaternary title="充值 / 调整钱包" onClick={() => openQuota(r)}>
        {r.unlimited ? (
          <span class="flex items-center gap-4px text-primary">
            <SvgIcon icon="lucide:infinity" class="text-14px" />
            不限额
          </span>
        ) : (
          <span class="flex items-center gap-4px">
            <SvgIcon icon="lucide:wallet" class="text-13px op-50" />
            <span class="text-13px font-medium tabular-nums">{formatUsd(r.quotaUsd)}</span>
          </span>
        )}
      </NButton>
    )
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: r => (
      <span class="flex items-center gap-6px">
        <span class="h-8px w-8px rounded-full" style={{ background: r.status === 1 ? '#18a058' : '#9ca3af' }} />
        <span class="text-13px">{r.status === 1 ? '正常' : '已停用'}</span>
      </span>
    )
  },
  {
    title: '创建时间',
    key: 'createDt',
    width: 150,
    render: r => (
      <span class="text-13px tabular-nums op-80">
        {typeof r.createDt === 'string' ? r.createDt.replace('T', ' ').slice(0, 16) : '-'}
      </span>
    )
  },
  {
    title: '操作',
    key: 'actions',
    width: 170,
    render: row => (
      <div class="flex gap-4px">
        <NButton size="tiny" quaternary title="充值 / 调整钱包" onClick={() => openQuota(row)}>
          充值
        </NButton>
        <NButton size="tiny" quaternary title="编辑工作区" onClick={() => openEdit(row)}>
          编辑
        </NButton>
        <NPopconfirm onPositiveClick={() => toggleStatus(row)}>
          {{
            trigger: () => (
              <NButton size="tiny" quaternary type={row.status === 1 ? 'error' : 'success'}>
                {row.status === 1 ? '停用' : '启用'}
              </NButton>
            ),
            default: () =>
              row.status === 1 ? '停用后该工作区全部 sk- Key 下一请求即被拒，确认？' : '恢复该工作区的调用能力？'
          }}
        </NPopconfirm>
      </div>
    )
  }
];

// 弹层
const operateVisible = ref(false);
const editing = ref<NexusAI.Workspace | null>(null);
const quotaVisible = ref(false);
const quotaUser = ref<NexusAI.Workspace | null>(null);

function openCreate() {
  editing.value = null;
  operateVisible.value = true;
}

function openEdit(row: NexusAI.Workspace) {
  editing.value = row;
  operateVisible.value = true;
}

function openQuota(row: NexusAI.Workspace) {
  quotaUser.value = row;
  quotaVisible.value = true;
}

onMounted(() => {
  loadProjects();
  loadUsers();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="flex flex-wrap items-center gap-12px">
        <span class="text-14px font-medium">筛选</span>
        <NSelect
          v-model:value="filterProject"
          :options="projectOptions"
          clearable
          placeholder="全部项目"
          class="w-260px"
          size="small"
          @update:value="search"
        />
        <NInput
          v-model:value="keyword"
          placeholder="按工作区标识/名称搜索"
          class="w-220px"
          size="small"
          clearable
          @keyup.enter="search"
        />
        <NButton size="small" @click="search">查询</NButton>
      </div>
    </NCard>

    <NCard title="项目内工作区（B2C 终端工作区钱包）" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <NButton size="small" type="primary" @click="openCreate">
          <template #icon>
            <SvgIcon icon="lucide:user-plus" />
          </template>
          新建工作区
        </NButton>
      </template>
      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="920"
        :row-key="r => r.id"
        remote
        :pagination="{
          page,
          pageSize,
          itemCount: total,
          onUpdatePage: (p: number) => {
            page = p;
            loadUsers();
          }
        }"
        class="sm:h-full"
      >
        <template #empty>
          <NEmpty description="该项目还没有终端工作区（B2B 项目 token 直挂项目池，无需建工作区）" class="py-48px" />
        </template>
      </NDataTable>
    </NCard>

    <WorkspaceOperateModal
      v-model:show="operateVisible"
      :editing="editing"
      :project-options="projectOptions"
      @saved="loadUsers"
    />
    <WorkspaceQuotaModal v-model:show="quotaVisible" :user="quotaUser" @saved="loadUsers" />
  </div>
</template>
