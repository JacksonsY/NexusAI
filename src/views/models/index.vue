<script setup lang="tsx">
import { onMounted, ref, watch } from 'vue';
import { NButton, NPopconfirm, NTag, NTooltip } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchBatchDeleteModels, fetchDeleteModel, fetchMissingModels, fetchModels, fetchUpdateModel } from '@/service/api';
import { fmtUsageCount, num, priceLine } from './composables/use-model-format';
import ModelFormModal from './modules/model-form-modal.vue';
import ModelDetailDrawer from './modules/model-detail-drawer.vue';
import BatchManageModal from './modules/batch-manage-modal.vue';
import AssignChannelsModal from './modules/assign-channels-modal.vue';
import SyncUpstreamModal from './modules/sync-upstream-modal.vue';

defineOptions({ name: 'Models' });

const loading = ref(false);
const rows = ref<NexusAI.ModelRow[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(20);
const searchQuery = ref('');
const missing = ref<string[]>([]);

// 创建/编辑
const formShow = ref(false);
const editing = ref<NexusAI.Model | null>(null);
// 详情抽屉
const drawerShow = ref(false);
const selected = ref<NexusAI.ModelRow | null>(null);
// 批量管理 / 关联渠道 / 同步上游
const batchShow = ref(false);
const syncShow = ref(false);
const assignShow = ref(false);
const assignModelKey = ref('');

async function load() {
  loading.value = true;
  const { data } = await fetchModels({
    pageNum: pageNum.value,
    pageSize: pageSize.value,
    search: searchQuery.value.trim() || undefined
  });
  rows.value = data?.records ?? [];
  total.value = num(data?.total) ?? 0;
  loading.value = false;
}

async function loadMissing() {
  const { data } = await fetchMissingModels();
  missing.value = data ?? [];
}

function refresh() {
  load();
  loadMissing();
}

let searchTimer: ReturnType<typeof setTimeout> | null = null;
watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    pageNum.value = 1;
    load();
  }, 300);
});

function openCreate(prefillKey?: string) {
  editing.value = prefillKey ? { modelKey: prefillKey, modality: 'CHAT', status: 1 } : null;
  formShow.value = true;
}

function openEdit(row: NexusAI.ModelRow) {
  editing.value = { ...row.model };
  formShow.value = true;
}

function openDetail(row: NexusAI.ModelRow) {
  selected.value = row;
  drawerShow.value = true;
}

async function toggleStatus(row: NexusAI.ModelRow) {
  const next = row.model.status === 1 ? 0 : 1;
  const { error } = await fetchUpdateModel(row.model.id!, { id: row.model.id, status: next });
  if (!error) {
    window.$message?.success(next === 1 ? '模型已启用' : '模型已停用');
    row.model.status = next;
  }
}

async function remove(row: NexusAI.ModelRow) {
  const { error } = await fetchDeleteModel(row.model.id!);
  if (!error) {
    window.$message?.success('模型删除成功');
    if (selected.value?.model.id === row.model.id) drawerShow.value = false;
    refresh();
  }
}

async function batchDelete(ids: Array<number | string>) {
  const { data, error } = await fetchBatchDeleteModels(ids);
  if (!error) {
    window.$message?.success(`已删除 ${data ?? ids.length} 个模型`);
    refresh();
  }
}

function openAssign(modelKey: string) {
  assignModelKey.value = modelKey;
  assignShow.value = true;
}

function copyKey(text: string) {
  navigator.clipboard?.writeText(text);
  window.$message?.success('已复制模型 ID');
}

const columns: DataTableColumns<NexusAI.ModelRow> = [
  {
    title: '模型名称',
    key: 'name',
    minWidth: 220,
    render: row => (
      <div>
        <div class="font-medium">{row.model.modelAlias || row.model.modelKey}</div>
        <div class="flex items-center gap-4px text-12px text-gray-400">
          <span class="font-mono">{row.model.modelKey}</span>
          <NButton text size="tiny" title="复制模型 ID" onClick={(e: MouseEvent) => { e.stopPropagation(); copyKey(row.model.modelKey!); }}>
            <SvgIcon icon="lucide:copy" class="text-12px" />
          </NButton>
        </div>
      </div>
    )
  },
  {
    title: '价格 ($/M)',
    key: 'price',
    width: 190,
    align: 'center',
    render: row => {
      const lines = priceLine(row.model);
      return lines.length === 0 ? (
        <span class="text-gray-400">-</span>
      ) : (
        <div class="text-12px leading-18px">
          {lines.map(l => (
            <div>{l}</div>
          ))}
        </div>
      );
    }
  },
  {
    title: '提供商',
    key: 'channels',
    width: 90,
    align: 'center',
    render: row => (
      <NTag size="small" round type={num(row.activeChannelCount) ? 'success' : 'default'}>
        {num(row.activeChannelCount) ?? 0}/{num(row.channelCount) ?? 0}
      </NTag>
    )
  },
  {
    title: () => (
      <NTooltip>
        {{ trigger: () => <span>调用次数</span>, default: () => '近 30 天（读时聚合请求日志）' }}
      </NTooltip>
    ),
    key: 'usage',
    width: 100,
    align: 'center',
    render: row => <span class="font-mono text-13px">{fmtUsageCount(row.usageCount)}</span>
  },
  {
    title: '状态',
    key: 'status',
    width: 80,
    render: row =>
      row.model.status === 1 ? (
        <NTag size="small" type="success">活跃</NTag>
      ) : (
        <NTag size="small">停用</NTag>
      )
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    align: 'center',
    render: row => (
      <div class="flex-center gap-2px" onClick={(e: MouseEvent) => e.stopPropagation()}>
        <NButton quaternary circle size="small" title="查看详情" onClick={() => openDetail(row)}>
          <SvgIcon icon="lucide:eye" />
        </NButton>
        <NButton quaternary circle size="small" title="编辑模型" onClick={() => openEdit(row)}>
          <SvgIcon icon="lucide:pencil" />
        </NButton>
        <NButton
          quaternary
          circle
          size="small"
          title={row.model.status === 1 ? '停用模型' : '启用模型'}
          onClick={() => toggleStatus(row)}
        >
          <SvgIcon icon="lucide:power" />
        </NButton>
        <NPopconfirm onPositiveClick={() => remove(row)}>
          {{
            trigger: () => (
              <NButton quaternary circle size="small" title="删除模型">
                <SvgIcon icon="lucide:trash-2" />
              </NButton>
            ),
            default: () => `确定删除模型 "${row.model.modelKey}" 吗？此操作不可撤销。`
          }}
        </NPopconfirm>
      </div>
    )
  }
];

function rowProps(row: NexusAI.ModelRow) {
  return {
    style: 'cursor: pointer',
    onClick: () => openDetail(row)
  };
}

onMounted(refresh);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-12px overflow-hidden lt-sm:overflow-auto">
    <NAlert v-if="missing.length > 0" type="warning" closable :show-icon="true" title="缺失模型">
      <div class="flex flex-wrap items-center gap-6px">
        <span class="text-13px">以下模型已在路由表登记但未建目录（无费率口径，存在计费缺口）：</span>
        <NTag
          v-for="name in missing"
          :key="name"
          size="small"
          type="warning"
          class="cursor-pointer"
          @click="openCreate(name)"
        >
          {{ name }}
        </NTag>
      </div>
    </NAlert>

    <NCard title="模型管理" :bordered="false" size="small" class="card-wrapper sm:flex-1-hidden">
      <template #header-extra>
        <NSpace :size="8" align="center">
          <NInput v-model:value="searchQuery" size="small" placeholder="搜索模型名称..." clearable class="w-180px">
            <template #prefix>
              <SvgIcon icon="lucide:search" class="text-gray-400" />
            </template>
          </NInput>
          <NButton size="small" @click="syncShow = true">
            <template #icon>
              <SvgIcon icon="lucide:cloud-download" />
            </template>
            同步上游
          </NButton>
          <NButton size="small" @click="batchShow = true">
            <template #icon>
              <SvgIcon icon="lucide:list-checks" />
            </template>
            批量管理
          </NButton>
          <NButton size="small" type="primary" ghost @click="openCreate()">
            <template #icon>
              <SvgIcon icon="ic:round-plus" />
            </template>
            创建模型
          </NButton>
          <NButton size="small" quaternary circle :loading="loading" title="刷新" @click="refresh">
            <template #icon>
              <SvgIcon icon="ic:round-refresh" />
            </template>
          </NButton>
        </NSpace>
      </template>

      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :bordered="false"
        size="small"
        :row-key="(r: NexusAI.ModelRow) => r.model.id"
        :row-props="rowProps"
        :scroll-x="860"
        remote
        :pagination="{
          page: pageNum,
          pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50, 100],
          onUpdatePage: (p: number) => {
            pageNum = p;
            load();
          },
          onUpdatePageSize: (s: number) => {
            pageSize = s;
            pageNum = 1;
            load();
          }
        }"
        class="sm:h-full"
      />
    </NCard>

    <ModelFormModal v-model:show="formShow" :model="editing" @success="refresh" />

    <ModelDetailDrawer
      v-model:show="drawerShow"
      :row="selected"
      @edit="openEdit"
      @toggle-status="toggleStatus"
      @assign-channels="openAssign"
      @refresh="refresh"
    />

    <BatchManageModal v-model:show="batchShow" @confirm="batchDelete" />

    <AssignChannelsModal v-model:show="assignShow" :model-key="assignModelKey" @success="refresh" />

    <SyncUpstreamModal v-model:show="syncShow" @success="refresh" />
  </div>
</template>
