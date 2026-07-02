<script setup lang="tsx">
import { computed, ref, watch } from 'vue';
import { refDebounced } from '@vueuse/core';
import { NButton, NDropdown, useThemeVars } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { useTable } from '@/hooks/common/table';
import { fetchProjects, fetchRotateProjectToken, fetchUpdateProject } from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';
import ProjectSearch from './modules/project-search.vue';
import ProjectOperateDrawer from './modules/project-operate-drawer.vue';
import AccessPolicyModal from './modules/access-policy-modal.vue';
import DeleteProjectModal from './modules/delete-project-modal.vue';
import QuotaModal from './modules/quota-modal.vue';

defineOptions({ name: 'Projects' });

const appStore = useAppStore();
const theme = useThemeVars();

// 令牌一次性明文（仅创建/轮转当次可见并复制；令牌哈希化后不再支持按需揭示·列表默认打码）
const revealedTokens = ref<Record<number, string>>({});

async function copyText(text: string) {
  await navigator.clipboard?.writeText(text);
  window.$message?.success('已复制到剪贴板');
}

const fmtDt = (s?: unknown) => (typeof s === 'string' && s ? s.replace('T', ' ').slice(0, 16) : '-');

/** 美元额度展示（后端回美元数值/字符串·保留 2 位小数）。 */
function formatUsd(v?: number | string | null) {
  if (v === null || v === undefined || v === '') {
    return '-';
  }
  const n = Number(v);
  return Number.isFinite(n) ? `$${n.toFixed(2)}` : '-';
}

/** 是否配置了访问策略（任一维度受限·PM：权限状态须列表可见）。 */
function isRestricted(r: NexusAI.Project) {
  const has = (s?: string) => {
    if (!s) {
      return false;
    }
    try {
      const v = JSON.parse(s);
      return Array.isArray(v) && v.length > 0;
    } catch {
      return false;
    }
  };
  return has(r.allowedModels) || has(r.allowedModalities) || has(r.allowedChannelIds) || has(r.allowedChannelTags);
}

// soybean 表格三件套（全量端点·本地分页）
const { loading, data, columns, columnChecks, pagination, mobilePagination, getData } = useTable<NexusAI.Project>({
  apiFn: fetchProjects,
  apiParams: {},
  columns: () => [
    {
      title: '项目',
      key: 'name',
      width: 180,
      render: (r: NexusAI.Project) => (
        <div class="flex flex-col gap-2px py-2px leading-tight">
          <span class="text-14px font-medium">{r.name}</span>
          <span class="font-mono text-12px op-60">{r.projectCode}</span>
        </div>
      )
    },
    { title: '描述', key: 'description', minWidth: 140, ellipsis: { tooltip: true }, render: (r: NexusAI.Project) => r.description || '—' },
    {
      title: '令牌',
      key: 'token',
      width: 230,
      render: (r: NexusAI.Project) => {
        // plain 仅在本次创建/轮转后当场可见（哈希化令牌不可再查明文）；否则展示后端打码标识
        const plain = revealedTokens.value[r.id!];
        return (
          <div class="flex items-center gap-4px">
            <span class={`break-all font-mono text-12px tabular-nums ${plain ? '' : 'op-60'}`}>{plain ?? r.token}</span>
            {plain && (
              <NButton size="tiny" quaternary aria-label="复制令牌" onClick={() => copyText(plain)}>
                <SvgIcon icon="lucide:copy" class="text-14px" />
              </NButton>
            )}
          </div>
        );
      }
    },
    {
      title: '访问策略',
      key: 'policy',
      width: 110,
      render: (r: NexusAI.Project) => (
        <NButton size="tiny" quaternary aria-label="配置访问策略" onClick={() => onAction('policy', r)}>
          {isRestricted(r) ? (
            <span class="flex items-center gap-4px" style={{ color: theme.value.primaryColor }}>
              <SvgIcon icon="lucide:shield-check" class="text-14px" />
              已限制
            </span>
          ) : (
            <span class="flex items-center gap-4px op-60">
              <SvgIcon icon="lucide:shield" class="text-14px" />
              不限制
            </span>
          )}
        </NButton>
      )
    },
    {
      title: '额度池($)',
      key: 'quotaUsd',
      width: 130,
      render: (r: NexusAI.Project) => (
        <NButton size="tiny" quaternary title="充值 / 调整额度" onClick={() => onAction('quota', r)}>
          {r.unlimited ? (
            <span class="flex items-center gap-4px" style={{ color: theme.value.primaryColor }}>
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
      render: (r: NexusAI.Project) => (
        <span class="flex items-center gap-6px">
          <span
            class="h-8px w-8px rounded-full"
            style={{ background: r.status === 1 ? '#18a058' : '#9ca3af' }}
          />
          <span class="text-13px">{r.status === 1 ? '活跃' : '已归档'}</span>
        </span>
      )
    },
    {
      title: '创建时间',
      key: 'createDt',
      width: 150,
      render: (r: NexusAI.Project) => <span class="text-13px tabular-nums op-80">{fmtDt(r.createDt)}</span>
    },
    {
      title: '操作',
      key: 'actions',
      width: 70,
      align: 'center',
      render: (row: NexusAI.Project) => (
        <NDropdown trigger="click" options={rowActions(row)} onSelect={(k: string) => onAction(k, row)}>
          <NButton size="tiny" quaternary aria-label="更多操作">
            <SvgIcon icon="lucide:ellipsis" class="text-16px" />
          </NButton>
        </NDropdown>
      )
    }
  ]
});

/** 归档行整行弱化（状态扫读零成本）。 */
function rowProps(row: NexusAI.Project) {
  return row.status === 1 ? {} : { style: 'opacity: 0.55' };
}

// 关键字本地过滤（全量端点·300ms 防抖·按名称/标识）
const keyword = ref('');
const debouncedKeyword = refDebounced(keyword, 300);
// 搜索词变化时分页归 1（受控分页下防止停留在超界页显示空表）
watch(debouncedKeyword, () => {
  pagination.page = 1;
});
const filteredData = computed(() => {
  const kw = debouncedKeyword.value.trim().toLowerCase();
  if (!kw) {
    return data.value;
  }
  return data.value.filter(
    p => (p.name ?? '').toLowerCase().includes(kw) || (p.projectCode ?? '').toLowerCase().includes(kw)
  );
});

// 弹层状态（创建/编辑抽屉 · 访问策略 · 输名删除）
const drawerVisible = ref(false);
const editing = ref<NexusAI.Project | null>(null);
const deleteModal = ref(false);
const deletingProject = ref<NexusAI.Project | null>(null);
const policyModal = ref(false);
const policyProject = ref<NexusAI.Project | null>(null);
const quotaModal = ref(false);
const quotaProject = ref<NexusAI.Project | null>(null);

function openCreate() {
  editing.value = null;
  drawerVisible.value = true;
}

async function onCreated(project: NexusAI.Project) {
  window.$message?.success('项目已创建');
  await getData();
  // 新建行直接揭示明文令牌（即看即拷）
  if (project.id && project.token) {
    revealedTokens.value = { ...revealedTokens.value, [project.id]: project.token };
  }
}

/** 归档/激活（独立确认操作·非表单字段）：PUT 仅传 status。 */
function confirmStatus(row: NexusAI.Project, active: boolean) {
  window.$dialog?.[active ? 'info' : 'warning']({
    title: active ? '激活项目' : '归档项目',
    content: active
      ? `确定要激活项目 "${row.name}" 吗？激活后接入凭证恢复生效。`
      : `确定要归档项目 "${row.name}" 吗？归档后其 M2M 接入凭证将暂停生效。`,
    positiveText: active ? '激活' : '归档',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await fetchUpdateProject(row.id!, { status: active ? 1 : 0 } as NexusAI.Project);
      if (!error) {
        window.$message?.success(active ? '项目已激活' : '项目已归档');
        getData();
      }
    }
  });
}

/** 轮转令牌：确认后旧令牌立即失效，新明文直接在行内揭示。 */
function confirmRotate(row: NexusAI.Project) {
  window.$dialog?.warning({
    title: '轮转令牌',
    content: `为项目 "${row.name}" 生成新令牌？旧令牌将立即失效，使用旧令牌的接入方需要同步更新。`,
    positiveText: '轮转',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { data: token, error } = await fetchRotateProjectToken(row.id!);
      if (!error && token) {
        window.$message?.success('令牌已轮转，旧令牌即时失效');
        await getData();
        revealedTokens.value = { ...revealedTokens.value, [row.id!]: token };
      }
    }
  });
}

/** 行操作 ⋯ 下拉（操作流抄 ：归档|激活互斥·删除红色·带图标可扫读）。 */
function rowActions(row: NexusAI.Project) {
  const danger = { style: { color: '#d03050' } };
  const ic = (name: string) => () => <SvgIcon icon={name} class="text-14px" />;
  return [
    { label: '访问策略', key: 'policy', icon: ic('lucide:shield-check') },
    { label: '充值 / 调整额度', key: 'quota', icon: ic('lucide:wallet') },
    { label: '编辑', key: 'edit', icon: ic('lucide:pencil') },
    { label: '轮转令牌', key: 'rotate', icon: ic('lucide:rotate-ccw') },
    { type: 'divider' as const, key: 'd1' },
    row.status === 1
      ? { label: '归档', key: 'archive', icon: ic('lucide:archive'), props: danger }
      : { label: '激活', key: 'activate', icon: ic('lucide:archive-restore') },
    { label: '删除', key: 'delete', icon: ic('lucide:trash-2'), props: danger }
  ];
}

function onAction(key: string, row: NexusAI.Project) {
  switch (key) {
    case 'policy':
      policyProject.value = row;
      policyModal.value = true;
      break;
    case 'quota':
      quotaProject.value = row;
      quotaModal.value = true;
      break;
    case 'edit':
      editing.value = row;
      drawerVisible.value = true;
      break;
    case 'rotate':
      confirmRotate(row);
      break;
    case 'archive':
      confirmStatus(row, false);
      break;
    case 'activate':
      confirmStatus(row, true);
      break;
    case 'delete':
      deletingProject.value = row;
      deleteModal.value = true;
      break;
    default:
  }
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <ProjectSearch v-model:keyword="keyword" @reset="getData" />

    <NCard title="项目列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :loading="loading"
          :show-batch-delete="false"
          @add="openCreate"
          @refresh="getData"
        />
      </template>
      <NDataTable
        :columns="columns"
        :data="filteredData"
        :loading="loading"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="980"
        :row-key="r => r.id"
        :row-props="rowProps"
        :pagination="mobilePagination"
        class="sm:h-full"
      >
        <template #empty>
          <NEmpty :description="keyword ? '没有匹配的项目' : '还没有项目'" class="py-48px">
            <template #extra>
              <NButton v-if="!keyword" type="primary" size="small" @click="openCreate">
                <template #icon>
                  <SvgIcon icon="lucide:plus" />
                </template>
                新建第一个项目
              </NButton>
            </template>
          </NEmpty>
        </template>
      </NDataTable>
    </NCard>

    <ProjectOperateDrawer v-model:visible="drawerVisible" :editing="editing" @created="onCreated" @saved="getData" />
    <AccessPolicyModal v-model:show="policyModal" :project="policyProject" @saved="getData" />
    <QuotaModal v-model:show="quotaModal" :project="quotaProject" @saved="getData" />
    <DeleteProjectModal v-model:show="deleteModal" :project="deletingProject" @deleted="getData" />
  </div>
</template>
