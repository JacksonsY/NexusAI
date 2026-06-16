<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchAuditEventTypes, fetchAuditLogs } from '@/service/api';

/**
 * 审计日志（内置工具·AuditLogs）：
 * 控制面写操作与登录成败的安全追踪——筛选（操作者/事件类型/时间范围）+ 表格 + 行详情 + CSV 导出。
 */
defineOptions({ name: 'AuditLogs' });

const loading = ref(false);
const logs = ref<NexusAI.AuditLog[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const selected = ref<NexusAI.AuditLog | null>(null);
const showDetail = ref(false);

const actorQuery = ref('');
const eventType = ref<string | null>(null);
const days = ref(7);
const eventTypeOptions = ref<{ label: string; value: string }[]>([]);

const daysOptions = [
  { label: '1 天', value: 1 },
  { label: '7 天', value: 7 },
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 }
];

/** 常见事件类型中文标签（未知类型回落原始值） */
const EVENT_LABELS: Record<string, string> = {
  admin_login_success: '登录成功',
  admin_login_failed: '登录失败',
  admin_logout: '登出',
  config_changed: '配置变更',
  config_imported: '配置导入',
  data_purged: '数据清空',
  module_config_changed: '模块配置变更',
  channel_created: '渠道创建',
  channel_updated: '渠道更新',
  channel_deleted: '渠道删除',
  token_created: '令牌创建',
  token_updated: '令牌更新',
  token_deleted: '令牌删除',
  project_created: '项目创建',
  project_updated: '项目更新',
  project_deleted: '项目删除',
  model_created: '模型创建',
  model_updated: '模型更新',
  model_deleted: '模型删除'
};

function eventLabel(type: string) {
  return EVENT_LABELS[type] ?? type;
}

function eventTagType(type: string): 'success' | 'error' | 'warning' | 'default' {
  if (type.includes('failed') || type.includes('deleted') || type.includes('purged')) return 'error';
  if (type.includes('success') || type.includes('created')) return 'success';
  if (type.includes('changed') || type.includes('imported') || type.includes('updated')) return 'warning';
  return 'default';
}

function statusTagType(code: number | null): 'success' | 'error' | 'warning' | 'default' {
  if (code == null) return 'default';
  if (code < 300) return 'success';
  if (code < 500) return 'warning';
  return 'error';
}

const hasFilters = computed(() => actorQuery.value !== '' || eventType.value !== null || days.value !== 7);

let debounceTimer: ReturnType<typeof setTimeout> | null = null;

async function load() {
  loading.value = true;
  const { data, error } = await fetchAuditLogs({
    actor: actorQuery.value || undefined,
    eventType: eventType.value || undefined,
    days: days.value,
    page: page.value,
    pageSize: pageSize.value
  });
  if (!error && data) {
    logs.value = data.items ?? [];
    total.value = Number(data.total ?? 0);
  }
  loading.value = false;
}

function resetAndLoad() {
  page.value = 1;
  load();
}

function handleSearchInput() {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(resetAndLoad, 500);
}

function handleResetFilters() {
  actorQuery.value = '';
  eventType.value = null;
  days.value = 7;
  resetAndLoad();
}

function openDetail(entry: NexusAI.AuditLog) {
  selected.value = entry;
  showDetail.value = true;
}

async function loadEventTypes() {
  const { data, error } = await fetchAuditEventTypes();
  if (!error && data) {
    eventTypeOptions.value = data.map(t => ({ label: eventLabel(t), value: t }));
  }
}

function exportCsv() {
  const header = ['时间', '操作者', '事件类型', '描述', '路径', '方法', 'IP', '状态码', '错误'];
  const rows = logs.value.map(l => [
    l.createDt,
    `"${l.actor ?? ''}"`,
    l.eventType,
    `"${l.description ?? ''}"`,
    l.path ?? '',
    l.method ?? '',
    l.ip ?? '',
    l.statusCode ?? '',
    `"${l.errorMessage ?? ''}"`
  ]);
  const csv = [header.join(','), ...rows.map(r => r.join(','))].join('\n');
  const blob = new Blob([`﻿${csv}`], { type: 'text/csv;charset=utf-8;' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `audit-logs-${new Date().toISOString().split('T')[0]}.csv`;
  link.click();
}

onMounted(() => {
  load();
  loadEventTypes();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="审计日志" :bordered="false" size="small" class="card-wrapper flex-1">
      <template #header-extra>
        <div class="flex flex-wrap items-center gap-8px">
          <NInput
            v-model:value="actorQuery"
            size="small"
            placeholder="搜索操作者..."
            class="w-180px"
            clearable
            @input="handleSearchInput"
          >
            <template #prefix>
              <SvgIcon icon="carbon:search" class="text-gray-400" />
            </template>
          </NInput>
          <NSelect
            v-model:value="eventType"
            size="small"
            :options="eventTypeOptions"
            placeholder="全部类型"
            clearable
            class="w-160px"
            @update:value="resetAndLoad"
          />
          <NSelect v-model:value="days" size="small" :options="daysOptions" class="w-100px" @update:value="resetAndLoad" />
          <NButton v-if="hasFilters" size="small" quaternary title="重置筛选" @click="handleResetFilters">
            <template #icon>
              <SvgIcon icon="carbon:filter-remove" />
            </template>
          </NButton>
          <NButton size="small" quaternary title="导出 CSV" @click="exportCsv">
            <template #icon>
              <SvgIcon icon="carbon:download" />
            </template>
          </NButton>
          <NButton size="small" quaternary :loading="loading" @click="load">
            <template #icon>
              <SvgIcon icon="carbon:renew" />
            </template>
          </NButton>
        </div>
      </template>
      <p class="mb-12px text-sm text-gray-400">控制面全部写操作与登录成败的安全追踪（保留天数见系统设置·记录清理策略）</p>
      <NSpin :show="loading">
        <NEmpty v-if="logs.length === 0" description="暂无审计记录" class="py-32px" />
        <template v-else>
          <NTable size="small" :bordered="false" :single-line="false">
            <thead>
              <tr>
                <th class="w-160px">时间</th>
                <th class="w-140px">操作者</th>
                <th class="w-150px">事件类型</th>
                <th>描述</th>
                <th class="w-130px">IP 地址</th>
                <th class="w-80px">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in logs" :key="entry.id" class="cursor-pointer" @click="openDetail(entry)">
                <td class="text-xs tabular-nums">{{ entry.createDt?.replace('T', ' ') }}</td>
                <td>
                  <NTag size="tiny" :bordered="false" :type="entry.actor?.startsWith('project:') ? 'info' : 'default'">
                    {{ entry.actor || '系统' }}
                  </NTag>
                </td>
                <td>
                  <NTag size="small" :bordered="false" :type="eventTagType(entry.eventType)">
                    {{ eventLabel(entry.eventType) }}
                  </NTag>
                </td>
                <td class="max-w-360px truncate" :title="entry.description">{{ entry.description || '无描述' }}</td>
                <td class="font-mono text-xs">{{ entry.ip || '-' }}</td>
                <td>
                  <NTag v-if="entry.statusCode" size="tiny" :bordered="false" :type="statusTagType(entry.statusCode)">
                    {{ entry.statusCode }}
                  </NTag>
                  <span v-else>-</span>
                </td>
              </tr>
            </tbody>
          </NTable>
          <div class="mt-12px flex justify-end">
            <NPagination
              v-model:page="page"
              v-model:page-size="pageSize"
              :item-count="total"
              :page-sizes="[10, 20, 50, 100]"
              show-size-picker
              @update:page="load"
              @update:page-size="resetAndLoad"
            />
          </div>
        </template>
      </NSpin>
    </NCard>

    <!-- 详情对话框 -->
    <NModal v-model:show="showDetail" preset="card" title="审计日志详情" class="max-w-640px">
      <div v-if="selected" class="space-y-12px">
        <div class="grid grid-cols-2 gap-12px">
          <div>
            <div class="text-xs text-gray-400">事件类型</div>
            <div class="mt-2px text-sm">{{ eventLabel(selected.eventType) }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">时间</div>
            <div class="mt-2px text-sm tabular-nums">{{ selected.createDt?.replace('T', ' ') }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">操作者</div>
            <div class="mt-2px text-sm">{{ selected.actor || '系统' }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">IP 地址</div>
            <div class="mt-2px text-sm font-mono">{{ selected.ip || '-' }}</div>
          </div>
          <div v-if="selected.path" class="col-span-2">
            <div class="text-xs text-gray-400">请求</div>
            <div class="mt-2px text-sm font-mono">{{ selected.method }} {{ selected.path }}</div>
          </div>
          <div v-if="selected.statusCode">
            <div class="text-xs text-gray-400">状态码</div>
            <div class="mt-2px text-sm">{{ selected.statusCode }}</div>
          </div>
        </div>
        <div>
          <div class="text-xs text-gray-400">描述</div>
          <div class="mt-2px text-sm">{{ selected.description || '无描述' }}</div>
        </div>
        <div v-if="selected.errorMessage">
          <div class="text-xs text-gray-400">错误消息</div>
          <div class="mt-2px text-sm text-red-500">{{ selected.errorMessage }}</div>
        </div>
      </div>
    </NModal>
  </div>
</template>

<style scoped></style>
