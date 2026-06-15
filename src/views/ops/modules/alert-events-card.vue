<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchAlertEvents, updateAlertEventStatus } from '@/service/api';

defineOptions({ name: 'AlertEventsCard' });

/** 告警事件卡（sub2api OpsAlertEventsCard 对位）：firing/resolved 列表 + 手动解除。 */
const props = defineProps<{ refreshToken: number }>();

const loading = ref(false);
const items = ref<NexusAI.AlertEvent[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(10);
const statusFilter = ref<string | null>(null);
const severityFilter = ref<string | null>(null);
const hoursFilter = ref<number | null>(null);
const selected = ref<NexusAI.AlertEvent | null>(null);

const STATUS_OPTIONS = [
  { label: '进行中', value: 'firing' },
  { label: '已恢复', value: 'resolved' },
  { label: '手动解除', value: 'manual_resolved' }
];

const SEVERITY_OPTIONS = ['P0', 'P1', 'P2', 'P3'].map(s => ({ label: s, value: s }));

const HOURS_OPTIONS = [
  { label: '最近 1 小时', value: 1 },
  { label: '最近 6 小时', value: 6 },
  { label: '最近 24 小时', value: 24 },
  { label: '最近 7 天', value: 168 },
  { label: '最近 30 天', value: 720 }
];

const STATUS_LABELS: Record<string, string> = {
  firing: '进行中',
  resolved: '已恢复',
  manual_resolved: '手动解除'
};

function severityType(s: string): 'error' | 'warning' | 'info' | 'default' {
  if (s === 'P0') return 'error';
  if (s === 'P1') return 'error';
  if (s === 'P2') return 'warning';
  return 'default';
}

async function load() {
  loading.value = true;
  const { data, error } = await fetchAlertEvents({
    status: statusFilter.value ?? undefined,
    severity: severityFilter.value ?? undefined,
    hours: hoursFilter.value ?? undefined,
    page: page.value,
    pageSize: pageSize.value
  });
  if (!error && data) {
    items.value = data.items ?? [];
    total.value = Number(data.total ?? 0);
  }
  loading.value = false;
}

async function resolveManually(id: number) {
  const { error } = await updateAlertEventStatus(id, 'manual_resolved');
  if (error) return;
  window.$message?.success('已手动解除');
  load();
}

watch(
  () => props.refreshToken,
  () => load()
);
watch([statusFilter, severityFilter, hoursFilter], () => {
  page.value = 1;
  load();
});

onMounted(load);
</script>

<template>
  <NCard title="告警事件" :bordered="false" size="small" class="card-wrapper">
    <template #header-extra>
      <div class="flex items-center gap-8px">
        <NSelect
          v-model:value="hoursFilter"
          size="small"
          :options="HOURS_OPTIONS"
          placeholder="全部时间"
          clearable
          class="w-130px"
        />
        <NSelect
          v-model:value="severityFilter"
          size="small"
          :options="SEVERITY_OPTIONS"
          placeholder="全部级别"
          clearable
          class="w-100px"
        />
        <NSelect
          v-model:value="statusFilter"
          size="small"
          :options="STATUS_OPTIONS"
          placeholder="全部状态"
          clearable
          class="w-130px"
        />
        <NButton size="small" quaternary :loading="loading" @click="load">
          <template #icon>
            <SvgIcon icon="carbon:renew" />
          </template>
        </NButton>
      </div>
    </template>
    <NSpin :show="loading">
      <NEmpty v-if="items.length === 0" description="暂无告警事件" class="py-24px" />
      <template v-else>
        <NTable size="small" :bordered="false" :single-line="false">
          <thead>
            <tr>
              <th class="w-150px">触发时间</th>
              <th class="w-60px">级别</th>
              <th class="w-90px">状态</th>
              <th class="w-160px">规则</th>
              <th>详情</th>
              <th class="w-110px">指标 / 阈值</th>
              <th class="w-80px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in items" :key="e.id" class="cursor-pointer" @click="selected = e">
              <td class="text-xs tabular-nums">{{ e.firedDt?.replace('T', ' ') }}</td>
              <td>
                <NTag size="tiny" :bordered="false" :type="severityType(e.severity)">{{ e.severity }}</NTag>
              </td>
              <td>
                <NTag size="tiny" :bordered="false" :type="e.status === 'firing' ? 'error' : 'success'">
                  {{ STATUS_LABELS[e.status] ?? e.status }}
                </NTag>
              </td>
              <td class="text-xs">{{ e.ruleName }}</td>
              <td class="max-w-360px truncate text-xs" :title="e.description ?? ''">{{ e.description }}</td>
              <td class="text-xs tabular-nums">{{ e.metricValue }} / {{ e.thresholdValue }}</td>
              <td @click.stop>
                <NPopconfirm v-if="e.status === 'firing'" @positive-click="resolveManually(e.id)">
                  <template #trigger>
                    <NButton size="tiny" quaternary type="warning">解除</NButton>
                  </template>
                  确认手动解除此告警？
                </NPopconfirm>
                <span v-else class="text-xs text-gray-400">{{ e.resolvedDt?.replace('T', ' ')?.slice(5, 16) }}</span>
              </td>
            </tr>
          </tbody>
        </NTable>
        <div class="mt-12px flex justify-end">
          <NPagination
            v-model:page="page"
            v-model:page-size="pageSize"
            :item-count="total"
            :page-sizes="[10, 20, 50]"
            show-size-picker
            @update:page="load"
            @update:page-size="load"
          />
        </div>
      </template>
    </NSpin>

    <!-- 事件详情弹窗（sub2api 告警事件详情对位·规则快照 + 当前值/阈值 + 时间线） -->
    <NModal :show="!!selected" preset="card" title="告警事件详情" class="max-w-520px" @update:show="selected = null">
      <template v-if="selected">
        <div class="space-y-10px text-13px">
          <div class="flex items-center gap-8px">
            <NTag size="small" :bordered="false" :type="severityType(selected.severity)">{{ selected.severity }}</NTag>
            <NTag size="small" :bordered="false" :type="selected.status === 'firing' ? 'error' : 'success'">
              {{ STATUS_LABELS[selected.status] ?? selected.status }}
            </NTag>
            <span class="font-semibold">{{ selected.ruleName }}</span>
          </div>
          <div class="rounded-8px bg-gray-50 p-12px text-12px dark:bg-gray-800">{{ selected.description }}</div>
          <div class="grid grid-cols-2 gap-8px text-12px">
            <div>
              <span class="text-gray-400">触发值</span>
              <b class="ml-6px tabular-nums">{{ selected.metricValue }}</b>
            </div>
            <div>
              <span class="text-gray-400">阈值</span>
              <b class="ml-6px tabular-nums">{{ selected.thresholdValue }}</b>
            </div>
            <div>
              <span class="text-gray-400">触发时间</span>
              <b class="ml-6px tabular-nums">{{ selected.firedDt?.replace('T', ' ') }}</b>
            </div>
            <div>
              <span class="text-gray-400">恢复时间</span>
              <b class="ml-6px tabular-nums">{{ selected.resolvedDt?.replace('T', ' ') ?? '-' }}</b>
            </div>
            <div>
              <span class="text-gray-400">通知</span>
              <b class="ml-6px">{{ selected.notifiedFlag === 1 ? '已发送' : '未发送' }}</b>
            </div>
            <div>
              <span class="text-gray-400">规则 ID</span>
              <b class="ml-6px tabular-nums">#{{ selected.ruleId }}</b>
            </div>
          </div>
        </div>
        <template v-if="selected.status === 'firing'">
          <div class="mt-12px flex justify-end">
            <NPopconfirm @positive-click="resolveManually(selected.id); selected = null">
              <template #trigger>
                <NButton size="small" type="warning">手动解除</NButton>
              </template>
              确认手动解除此告警？
            </NPopconfirm>
          </div>
        </template>
      </template>
    </NModal>
  </NCard>
</template>

<style scoped></style>
