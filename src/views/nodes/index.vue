<script setup lang="tsx">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NPopconfirm, NProgress, NTag, NText } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchNodes, removeNode } from '@/service/api';

/**
 * 集群节点（server node 集群视图对位）：汇总卡（节点数/在线/当前 leader/在途请求/熔断态真理源）
 * → 节点表格（心跳三档状态/leader 徽章/运行时快照 CPU·堆·在途请求/手动剔除）→ 行展开运行时详情。
 * 10s 自动刷新（心跳周期 15s）。
 */
defineOptions({ name: 'Nodes' });

const loading = ref(false);
const nodes = ref<NexusAI.Node[]>([]);
const summary = ref<NexusAI.ClusterSummary | null>(null);
let timer: ReturnType<typeof setInterval> | null = null;

async function load() {
  loading.value = true;
  const { data, error } = await fetchNodes();
  if (!error && data) {
    nodes.value = data.items ?? [];
    summary.value = data.summary ?? null;
  }
  loading.value = false;
}

async function handleRemove(node: NexusAI.Node) {
  const { error } = await removeNode(node.nodeId);
  if (error) return;
  window.$message?.success(`节点 ${node.nodeId} 已剔除${node.status !== 'offline' ? '（在线节点下次心跳会重新注册）' : ''}`);
  await load();
}

const fmtNum = (n?: number | string | null) => (n === null || n === undefined ? '0' : Number(n).toLocaleString());

function fmtTime(iso?: string | null) {
  if (!iso) return '-';
  const d = new Date(iso);
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff >= 0 && diff < 60) return '刚刚';
  if (diff >= 0 && diff < 3600) return `${Math.floor(diff / 60)}分钟前`;
  if (diff >= 0 && diff < 86400) return `${Math.floor(diff / 3600)}小时前`;
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function fmtUptime(startedAt?: number | string | null) {
  if (!startedAt) return '-';
  const ms = Date.now() - Number(startedAt);
  if (ms <= 0) return '-';
  const totalMinutes = Math.floor(ms / 60000);
  if (totalMinutes < 60) return `${totalMinutes} 分钟`;
  const hours = Math.floor(totalMinutes / 60);
  if (hours < 24) return `${hours} 小时 ${totalMinutes % 60} 分钟`;
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
}

function statusLabel(s: string) {
  if (s === 'online') return '在线';
  if (s === 'stale') return '迟滞';
  return '离线';
}

function statusType(s: string): 'success' | 'warning' | 'error' {
  if (s === 'online') return 'success';
  if (s === 'stale') return 'warning';
  return 'error';
}

function heapPercent(n: NexusAI.Node) {
  if (!n.heapUsedMb || !n.heapMaxMb) return null;
  return Math.round((n.heapUsedMb / n.heapMaxMb) * 100);
}

const summaryCards = computed(() => [
  { label: '节点总数', value: fmtNum(summary.value?.total), icon: 'carbon:edge-cluster', tone: '' },
  {
    label: '在线节点',
    value: fmtNum(summary.value?.online),
    icon: 'carbon:checkmark-outline',
    tone: (summary.value?.online ?? 0) > 0 ? 'text-success' : 'text-error'
  },
  { label: '当前 Leader', value: summary.value?.leader ?? '-', icon: 'carbon:user-certification', tone: 'text-primary', mono: true },
  { label: '在途请求', value: fmtNum(summary.value?.totalActiveRequests), icon: 'carbon:activity', tone: '' },
  {
    label: '熔断态真理源',
    value: 'Redis 集群共享',
    icon: 'carbon:data-share',
    tone: 'text-success'
  }
]);

function renderExpand(row: NexusAI.Node) {
  const items = [
    { label: '节点 ID', value: row.nodeId },
    { label: '主机', value: `${row.host ?? '-'}:${row.port ?? '-'}` },
    { label: '角色', value: row.role === 'leader' ? 'leader（后台任务/清理/探测由其执行）' : 'worker' },
    { label: 'JVM 版本', value: row.version ?? '-' },
    { label: '启动时长', value: fmtUptime(row.startedAt) },
    { label: '在途请求', value: fmtNum(row.activeRequests) },
    { label: '进程 CPU', value: row.cpuPercent != null ? `${row.cpuPercent}%` : '-' },
    { label: 'JVM 堆', value: row.heapUsedMb != null ? `${fmtNum(row.heapUsedMb)} / ${fmtNum(row.heapMaxMb)} MB` : '-' },
    { label: '部署标签', value: row.labels || '-' },
    { label: '首次注册', value: fmtTime(row.registeredAt) },
    { label: '最后心跳', value: fmtTime(row.lastHeartbeat) }
  ];
  return (
    <div class="bg-gray-50 px-16px py-12px dark:bg-gray-900">
      <div class="grid grid-cols-2 gap-x-24px gap-y-8px sm:grid-cols-3 xl:grid-cols-4">
        {items.map(item => (
          <div class="min-w-0" key={item.label}>
            <div class="text-10px text-gray-400">{item.label}</div>
            <div class="mt-2px truncate text-xs font-medium tabular-nums" title={String(item.value)}>
              {item.value}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

const columns = computed<DataTableColumns<NexusAI.Node>>(() => [
  { type: 'expand', width: 36, renderExpand: row => renderExpand(row) },
  {
    key: 'nodeId',
    title: '节点 ID',
    minWidth: 190,
    render: row => (
      <div class="flex items-center gap-6px">
        <NText code class="text-xs">{row.nodeId}</NText>
        {row.role === 'leader' ? (
          <NTag size="tiny" type="primary" bordered={false}>
            Leader
          </NTag>
        ) : null}
      </div>
    )
  },
  {
    key: 'status',
    title: '状态',
    width: 90,
    align: 'center',
    render: row => (
      <NTag size="small" type={statusType(row.status)} bordered={false}>
        {statusLabel(row.status)}
      </NTag>
    )
  },
  {
    key: 'activeRequests',
    title: '在途请求',
    width: 100,
    align: 'center',
    render: row => <span class="tabular-nums">{fmtNum(row.activeRequests)}</span>
  },
  {
    key: 'cpuPercent',
    title: 'CPU',
    width: 90,
    align: 'center',
    render: row => (
      <span class={`tabular-nums ${row.cpuPercent != null && row.cpuPercent >= 80 ? 'text-error font-medium' : ''}`}>
        {row.cpuPercent != null ? `${row.cpuPercent}%` : '-'}
      </span>
    )
  },
  {
    key: 'heap',
    title: 'JVM 堆',
    width: 170,
    render: row => {
      const pct = heapPercent(row);
      if (pct === null) return <NText depth={3}>-</NText>;
      return (
        <div class="flex items-center gap-8px">
          <NProgress
            type="line"
            percentage={pct}
            height={6}
            showIndicator={false}
            status={pct >= 90 ? 'error' : pct >= 75 ? 'warning' : 'success'}
            class="w-72px"
          />
          <span class="text-xs tabular-nums">{`${row.heapUsedMb}/${row.heapMaxMb}MB`}</span>
        </div>
      );
    }
  },
  {
    key: 'version',
    title: 'JVM',
    width: 110,
    align: 'center',
    render: row => <span class="text-xs tabular-nums">{row.version ?? '-'}</span>
  },
  {
    key: 'uptime',
    title: '启动时长',
    width: 130,
    render: row => <span class="text-xs text-gray-400">{fmtUptime(row.startedAt)}</span>
  },
  {
    key: 'lastHeartbeat',
    title: '最后心跳',
    width: 110,
    render: row => <span class="text-xs text-gray-400">{fmtTime(row.lastHeartbeat)}</span>
  },
  {
    key: 'actions',
    title: '操作',
    width: 80,
    align: 'center',
    render: row => (
      <NPopconfirm onPositiveClick={() => handleRemove(row)}>
        {{
          default: () =>
            row.status === 'offline'
              ? `确定剔除离线节点 ${row.nodeId} 吗？`
              : `节点 ${row.nodeId} 仍在线，剔除后下次心跳会重新注册。确定剔除？`,
          trigger: () => (
            <NButton size="tiny" quaternary type="error" title="剔除节点">
              {{ icon: () => <SvgIcon icon="carbon:trash-can" /> }}
            </NButton>
          )
        }}
      </NPopconfirm>
    )
  }
]);

onMounted(() => {
  load();
  timer = setInterval(load, 10_000);
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px">
    <!-- 汇总卡 -->
    <div class="grid grid-cols-2 gap-12px md:grid-cols-3 xl:grid-cols-5">
      <NCard v-for="card in summaryCards" :key="card.label" :bordered="false" size="small" class="card-wrapper">
        <div class="flex items-center gap-12px">
          <div class="h-36px w-36px shrink-0 flex items-center justify-center rounded-8px bg-primary/10 text-primary">
            <SvgIcon :icon="card.icon" class="text-16px" />
          </div>
          <div class="min-w-0">
            <div class="text-xs text-gray-400">{{ card.label }}</div>
            <div
              class="mt-2px truncate text-sm font-semibold"
              :class="[card.tone, card.mono ? 'font-mono text-xs' : '']"
              :title="String(card.value)"
            >
              {{ card.value }}
            </div>
          </div>
        </div>
      </NCard>
    </div>

    <!-- 集群熔断态：始终 RTopic 广播跨实例共享（H6·新实例 warm-start 继承），无需开关 -->
    <NAlert v-if="summary && summary.online <= 1" type="info" :bordered="false">
      当前为单节点部署。横向扩容时新实例只需指向同一 MySQL/Redis 即自动注册进集群；熔断/过载态经 RTopic 广播跨实例即时共享，
      新实例启动 warm-start 继承在用熔断态；后台任务（清理/探测/任务轮询）由 leader 标记持有者执行，leader 失联 45s 后自动易主。
    </NAlert>

    <!-- 节点表格 -->
    <NCard :bordered="false" size="small" class="card-wrapper flex-1">
      <div class="mb-12px flex items-center justify-between">
        <div>
          <span class="text-base font-semibold">集群节点</span>
          <span class="ml-8px text-xs text-gray-400">心跳 15s · 超 45s 判离线 · 每 10s 自动刷新</span>
        </div>
        <NButton size="small" quaternary :loading="loading" @click="load">
          <template #icon>
            <SvgIcon icon="carbon:renew" />
          </template>
        </NButton>
      </div>
      <NDataTable
        :columns="columns"
        :data="nodes"
        :loading="loading && nodes.length === 0"
        :row-key="(row: NexusAI.Node) => row.nodeId"
        size="small"
      >
        <template #empty>
          <div class="py-48px text-center text-sm text-gray-400">暂无节点（网关进程启动后 15s 内自动注册）</div>
        </template>
      </NDataTable>
    </NCard>
  </div>
</template>

<style scoped></style>
