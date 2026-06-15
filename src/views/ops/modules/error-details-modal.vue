<script setup lang="ts">
import { ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchRequestDetail, fetchRequestLogs } from '@/service/api';

defineOptions({ name: 'ErrorDetailsModal' });

/**
 * 错误详情弹窗（sub2api OpsErrorDetailsModal + OpsErrorDetailModal 对位）：
 * 错误请求分页列表（kind 预筛：全部/上游/限流）→ 点击行钻取单条完整诊断（含同请求 usage 记录）。
 */
const props = defineProps<{
  hours: number;
  channelId?: number;
  /** 预筛：all=全部错误 / upstream=上游(≥500) / limited=限流(429) */
  kind?: 'all' | 'upstream' | 'limited';
}>();

const show = defineModel<boolean>('show', { default: false });

const loading = ref(false);
const rows = ref<NexusAI.RequestLog[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const kindVal = ref<'all' | 'upstream' | 'limited'>('all');
/** 状态码精确筛选（sub2api status_code 下拉对位·与 kind 预筛叠加） */
const statusCode = ref<number | null>(null);

const STATUS_CODE_OPTIONS = [400, 401, 403, 404, 408, 422, 429, 500, 502, 503, 504, 529].map(c => ({
  label: String(c),
  value: c
}));

const detail = ref<Record<string, unknown> | null>(null);
const detailLoading = ref(false);

async function load() {
  loading.value = true;
  const params: Record<string, unknown> = {
    success: false,
    hours: props.hours,
    channelId: props.channelId,
    pageNum: page.value,
    pageSize: pageSize.value,
    orderDesc: true
  };
  if (kindVal.value === 'limited') params.status = 429;
  if (statusCode.value != null) params.status = statusCode.value;
  const { data, error } = await fetchRequestLogs(params as never);
  if (!error && data) {
    let list = data.records ?? [];
    // 上游错误（≥500）后端无独立筛选——按当前页客户端过滤展示（与趋势线口径一致）
    if (kindVal.value === 'upstream') list = list.filter(r => Number(r.httpStatus) >= 500);
    rows.value = list;
    total.value = Number(data.total ?? 0);
  }
  loading.value = false;
}

async function openDetail(r: NexusAI.RequestLog) {
  if (!r.requestId) return;
  detailLoading.value = true;
  const { data, error } = await fetchRequestDetail(r.requestId);
  detailLoading.value = false;
  if (!error && data) detail.value = data as Record<string, unknown>;
}

function statusType(code?: number): 'error' | 'warning' | 'default' {
  if (code == null) return 'default';
  return code >= 500 ? 'error' : 'warning';
}

watch(show, v => {
  if (v) {
    kindVal.value = props.kind ?? 'all';
    page.value = 1;
    detail.value = null;
    load();
  }
});
</script>

<template>
  <NModal v-model:show="show" preset="card" title="错误详情" class="max-w-980px">
    <!-- 列表 -->
    <template v-if="!detail">
      <div class="mb-10px flex items-center gap-8px">
        <NButtonGroup size="small">
          <NButton :type="kindVal === 'all' ? 'primary' : 'default'" @click="kindVal = 'all'; page = 1; load()">
            全部错误
          </NButton>
          <NButton :type="kindVal === 'upstream' ? 'primary' : 'default'" @click="kindVal = 'upstream'; page = 1; load()">
            上游（≥500）
          </NButton>
          <NButton :type="kindVal === 'limited' ? 'primary' : 'default'" @click="kindVal = 'limited'; page = 1; load()">
            限流（429）
          </NButton>
        </NButtonGroup>
        <NSelect
          v-model:value="statusCode"
          size="small"
          :options="STATUS_CODE_OPTIONS"
          placeholder="状态码"
          clearable
          class="w-100px"
          @update:value="page = 1; load()"
        />
        <span class="text-xs text-gray-400">最近 {{ hours }} 小时{{ channelId ? '·当前渠道' : '' }}</span>
        <NButton size="small" quaternary :loading="loading" class="ml-auto" @click="load">
          <template #icon>
            <SvgIcon icon="carbon:renew" />
          </template>
        </NButton>
      </div>
      <NSpin :show="loading">
        <NEmpty v-if="rows.length === 0" description="无错误记录" class="py-24px" />
        <template v-else>
          <NTable size="small" :bordered="false" :single-line="false">
            <thead>
              <tr>
                <th class="w-150px">时间</th>
                <th class="w-70px">状态码</th>
                <th class="w-150px">错误码</th>
                <th>模型</th>
                <th class="w-90px">耗时</th>
                <th class="w-70px">重试</th>
                <th class="w-200px">requestId</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="String(r.id)" class="cursor-pointer" @click="openDetail(r)">
                <td class="text-xs tabular-nums">{{ String((r as any).createDt ?? '').replace('T', ' ') }}</td>
                <td>
                  <NTag size="tiny" :bordered="false" :type="statusType(Number(r.httpStatus))">{{ r.httpStatus }}</NTag>
                </td>
                <td class="font-mono text-xs">{{ r.errorCode || '-' }}</td>
                <td class="font-mono text-xs">{{ r.model || '-' }}</td>
                <td class="text-xs tabular-nums">{{ r.durationMs }}ms</td>
                <td class="text-xs tabular-nums">{{ r.retryCount }}/{{ r.fallbackCount }}</td>
                <td class="truncate font-mono text-xs op-60">{{ r.requestId }}</td>
              </tr>
            </tbody>
          </NTable>
          <div class="mt-12px flex justify-end">
            <NPagination
              v-model:page="page"
              v-model:page-size="pageSize"
              :item-count="total"
              :page-sizes="[20, 50, 100]"
              show-size-picker
              @update:page="load"
              @update:page-size="page = 1; load()"
            />
          </div>
        </template>
      </NSpin>
    </template>

    <!-- 单条钻取 -->
    <template v-else>
      <div class="mb-10px flex items-center justify-between">
        <NButton size="small" quaternary @click="detail = null">
          <template #icon>
            <SvgIcon icon="carbon:arrow-left" />
          </template>
          返回列表
        </NButton>
      </div>
      <NSpin :show="detailLoading">
        <NCode :code="JSON.stringify(detail, null, 2)" language="json" class="max-h-480px overflow-auto text-12px" />
      </NSpin>
    </template>
  </NModal>
</template>

<style scoped></style>
