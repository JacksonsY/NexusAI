<script setup lang="ts">
import { ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchRequestDetail, fetchRequestLogs } from '@/service/api';

defineOptions({ name: 'SlowRequestsModal' });

/**
 * 慢请求排查弹窗（sub2api OpsRequestDetailsModal duration_desc 预设对位）：
 * 按耗时倒序的请求分页列表 → 点击行钻取完整诊断。
 */
const props = defineProps<{ hours: number; channelId?: number }>();

const show = defineModel<boolean>('show', { default: false });

const loading = ref(false);
const rows = ref<NexusAI.RequestLog[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
/** 仅看成功 / 全部（慢且失败的请求在错误详情弹窗也能看到） */
const successOnly = ref(false);

const detail = ref<Record<string, unknown> | null>(null);
const detailLoading = ref(false);

async function load() {
  loading.value = true;
  const { data, error } = await fetchRequestLogs({
    hours: props.hours,
    channelId: props.channelId,
    success: successOnly.value ? true : undefined,
    pageNum: page.value,
    pageSize: pageSize.value,
    orderBy: 'durationMs',
    orderDesc: true
  } as never);
  if (!error && data) {
    rows.value = data.records ?? [];
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

function fmtMs(v?: number) {
  const n = Number(v ?? 0);
  return n >= 1000 ? `${(n / 1000).toFixed(1)}s` : `${n}ms`;
}

watch(show, v => {
  if (v) {
    page.value = 1;
    detail.value = null;
    load();
  }
});
</script>

<template>
  <NModal v-model:show="show" preset="card" title="慢请求排查（按耗时倒序）" class="max-w-980px">
    <template v-if="!detail">
      <div class="mb-10px flex items-center gap-8px">
        <NSwitch v-model:value="successOnly" size="small" @update:value="page = 1; load()">
          <template #checked>仅成功</template>
          <template #unchecked>全部</template>
        </NSwitch>
        <span class="text-xs text-gray-400">最近 {{ hours }} 小时{{ channelId ? '·当前渠道' : '' }}</span>
        <NButton size="small" quaternary :loading="loading" class="ml-auto" @click="load">
          <template #icon>
            <SvgIcon icon="carbon:renew" />
          </template>
        </NButton>
      </div>
      <NSpin :show="loading">
        <NEmpty v-if="rows.length === 0" description="窗口内无请求" class="py-24px" />
        <template v-else>
          <NTable size="small" :bordered="false" :single-line="false">
            <thead>
              <tr>
                <th class="w-150px">时间</th>
                <th class="w-100px">耗时</th>
                <th>模型</th>
                <th class="w-70px">状态码</th>
                <th class="w-70px">流式</th>
                <th class="w-90px">输出 tokens</th>
                <th class="w-200px">requestId</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="String(r.id)" class="cursor-pointer" @click="openDetail(r)">
                <td class="text-xs tabular-nums">{{ String((r as any).createDt ?? '').replace('T', ' ') }}</td>
                <td class="font-semibold tabular-nums" :class="Number(r.durationMs) >= 10000 ? 'text-red-500' : Number(r.durationMs) >= 3000 ? 'text-amber-500' : ''">
                  {{ fmtMs(Number(r.durationMs)) }}
                </td>
                <td class="font-mono text-xs">{{ r.model || '-' }}</td>
                <td>
                  <NTag size="tiny" :bordered="false" :type="Number(r.httpStatus) < 400 ? 'success' : 'error'">
                    {{ r.httpStatus }}
                  </NTag>
                </td>
                <td class="text-xs">{{ (r as any).streamMode ? '是' : '否' }}</td>
                <td class="text-xs tabular-nums">{{ (r as any).completionTokens ?? '-' }}</td>
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
