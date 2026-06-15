<script setup lang="ts">
import { computed, ref } from 'vue';

defineOptions({ name: 'LiveStatusCard' });

/** 实时运行状态（一比一 Aether 性能分析实时区·tunnel 区映射为 nexusai 集群节点/并发槽）。 */
const props = defineProps<{
  data: NexusAI.PerfLive | null;
  loading: boolean;
}>();

const RECENT_ERRORS_COLLAPSED_LIMIT = 3;
const errorsExpanded = ref(false);

function num(value?: number | string | null): number {
  const n = Number(value);
  return Number.isNaN(n) ? 0 : n;
}

function fmt(value?: number | string | null): string {
  if (value === null || value === undefined || value === '') return '-';
  return new Intl.NumberFormat('zh-CN').format(num(value));
}

const healthTagType = computed(() => {
  switch (props.data?.status) {
    case 'healthy':
      return 'success';
    case 'degraded':
      return 'warning';
    case 'critical':
      return 'error';
    default:
      return 'default';
  }
});

const healthText = computed(() => {
  if (!props.data) return '健康状态未知';
  const map: Record<string, string> = { healthy: '系统健康', degraded: '系统降级', critical: '系统告警' };
  return `${map[props.data.status] ?? props.data.status} · ${props.data.healthScore}/100`;
});

/** 6 实时汇总卡（一比一 Aether liveSummaryCards·tunnel 卡映射集群节点/并发槽）。 */
const summaryCards = computed(() => {
  const d = props.data;
  return [
    {
      title: '系统健康',
      value: d ? `${d.healthScore}/100` : '-',
      hint: `${healthText.value} · 熔断 ${d?.circuits.length ?? 0}`,
      icon: 'lucide:shield-check',
      iconClass: 'text-emerald-500'
    },
    {
      title: '最近 1 小时错误',
      value: fmt(d?.errorsLastHour),
      hint: `24h 总错误 ${fmt(d?.errorsLast24h)}`,
      icon: 'lucide:triangle-alert',
      iconClass: 'text-yellow-500'
    },
    {
      title: '集群节点',
      value: d ? `${fmt(d.cluster.onlineNodes)}/${fmt(d.cluster.totalNodes)}` : '-',
      hint: '在线 / 总节点',
      icon: 'lucide:cable',
      iconClass: 'text-sky-500'
    },
    {
      title: '当前节点处理中',
      value: fmt(d?.local.inFlight),
      hint: `峰值并发 ${fmt(d?.local.peak)}`,
      icon: 'lucide:activity',
      iconClass: 'text-blue-500'
    },
    {
      title: '并发槽占用',
      value: fmt(d?.slots.occupied),
      hint: `可调度凭证 ${fmt(d?.slots.schedulableKeys)}/${fmt(d?.slots.totalKeys)}`,
      icon: 'lucide:workflow',
      iconClass: 'text-violet-500'
    },
    {
      title: '降级切换',
      value: fmt(d?.fallbackTotal),
      hint: '近 24 小时故障转移',
      icon: 'lucide:git-branch',
      iconClass: 'text-rose-500'
    }
  ];
});

/** 降级切换进度条行（一比一 Aether fallbackRows：按量降序 + 占比条）。 */
const fallbackRows = computed(() => {
  const items = props.data?.fallbacks ?? [];
  const max = Math.max(...items.map(item => num(item.total)), 0);
  return items
    .filter(item => num(item.total) > 0)
    .sort((a, b) => num(b.total) - num(a.total))
    .map(item => ({ ...item, ratio: max > 0 ? (num(item.total) / max) * 100 : 0 }));
});

const recentErrors = computed(() => props.data?.recentErrors ?? []);
const hasMoreErrors = computed(() => recentErrors.value.length > RECENT_ERRORS_COLLAPSED_LIMIT);
const visibleErrors = computed(() =>
  errorsExpanded.value ? recentErrors.value : recentErrors.value.slice(0, RECENT_ERRORS_COLLAPSED_LIMIT)
);

function formatTime(value?: string) {
  if (!value) return '-';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
</script>

<template>
  <NCard :bordered="true" class="card-wrapper" content-class="!p-0">
    <div class="border-b border-gray-500/15 bg-gray-500/4 px-20px py-12px">
      <div class="flex flex-col gap-8px sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 class="m-0 text-14px font-semibold">实时运行状态</h2>
          <p class="m-0 mt-2px text-12px op-60">聚合系统健康、并发保护、集群节点与降级切换</p>
        </div>
        <div class="flex flex-wrap items-center gap-8px">
          <NTag :type="healthTagType" size="small" round>{{ healthText }}</NTag>
          <NTag size="small" round>{{ data ? '网关指标在线' : '网关指标暂不可达' }}</NTag>
        </div>
      </div>
    </div>

    <div class="p-20px">
      <div v-if="loading && !data" class="flex items-center justify-center py-32px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-20px" />
        <span class="ml-8px">加载实时性能数据中...</span>
      </div>

      <div v-else-if="!data" class="border border-gray-500/30 rounded-10px border-dashed px-16px py-24px text-13px op-60">
        实时性能数据暂不可用，请稍后重试。
      </div>

      <div v-else class="flex-col gap-16px">
        <!-- 6 实时汇总卡 -->
        <div class="grid grid-cols-1 gap-12px sm:grid-cols-2 2xl:grid-cols-6 xl:grid-cols-3">
          <div v-for="card in summaryCards" :key="card.title" class="border border-gray-500/20 rounded-12px px-16px py-12px">
            <div class="flex items-center justify-between gap-12px">
              <span class="text-12px op-60">{{ card.title }}</span>
              <SvgIcon :icon="card.icon" class="text-16px" :class="card.iconClass" />
            </div>
            <div class="mt-10px text-24px font-semibold tracking-tight tabular-nums">{{ card.value }}</div>
            <div class="mt-6px truncate text-12px op-60">{{ card.hint }}</div>
          </div>
        </div>

        <div class="grid grid-cols-1 gap-16px xl:grid-cols-3">
          <div class="grid grid-cols-1 gap-16px lg:grid-cols-2 xl:col-span-2">
            <!-- 并发保护（当前节点 + 并发槽·映射 Aether 本机/全局） -->
            <section class="border border-gray-500/20 rounded-12px p-16px lg:col-span-2">
              <div class="flex items-center justify-between gap-12px">
                <h3 class="m-0 text-13px font-semibold">并发保护</h3>
                <NTag size="small">渠道密钥并发槽</NTag>
              </div>
              <div class="mt-14px grid grid-cols-1 gap-12px lg:grid-cols-2">
                <div class="border border-gray-500/15 rounded-10px bg-gray-500/4 px-12px py-12px">
                  <div class="flex items-center justify-between gap-12px">
                    <div class="text-13px font-medium">当前节点</div>
                    <NTag size="small">本机</NTag>
                  </div>
                  <div class="mt-14px grid grid-cols-2 gap-12px text-13px">
                    <div>
                      <div class="text-12px op-60">处理中</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.local.inFlight) }}</div>
                    </div>
                    <div>
                      <div class="text-12px op-60">累计请求</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.local.total) }}</div>
                    </div>
                    <div>
                      <div class="text-12px op-60">峰值并发</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.local.peak) }}</div>
                    </div>
                    <div>
                      <div class="text-12px op-60">被限流</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.local.rejected) }}</div>
                    </div>
                  </div>
                </div>
                <div class="border border-gray-500/15 rounded-10px bg-gray-500/4 px-12px py-12px">
                  <div class="flex items-center justify-between gap-12px">
                    <div class="text-13px font-medium">渠道密钥并发槽</div>
                    <NTag size="small" :type="num(data.slots.schedulableKeys) > 0 ? 'default' : 'warning'">
                      {{ num(data.slots.schedulableKeys) > 0 ? '在线' : '无可调度' }}
                    </NTag>
                  </div>
                  <div class="mt-14px grid grid-cols-2 gap-12px text-13px">
                    <div>
                      <div class="text-12px op-60">占用槽位</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.slots.occupied) }}</div>
                    </div>
                    <div>
                      <div class="text-12px op-60">可调度凭证</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.slots.schedulableKeys) }}</div>
                    </div>
                    <div>
                      <div class="text-12px op-60">凭证总数</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.slots.totalKeys) }}</div>
                    </div>
                    <div>
                      <div class="text-12px op-60">熔断中</div>
                      <div class="mt-4px text-17px font-semibold tabular-nums">{{ data.circuits.length }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <!-- 集群节点（映射 Aether 代理通道区） -->
            <section class="border border-gray-500/20 rounded-12px p-16px lg:col-span-2">
              <div class="flex items-center justify-between gap-12px">
                <h3 class="m-0 text-13px font-semibold">集群节点</h3>
                <NTag size="small">心跳注册</NTag>
              </div>
              <div class="mt-14px grid grid-cols-2 gap-12px text-13px lg:grid-cols-4">
                <div>
                  <div class="text-12px op-60">在线节点</div>
                  <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.cluster.onlineNodes) }}</div>
                </div>
                <div>
                  <div class="text-12px op-60">总节点</div>
                  <div class="mt-4px text-17px font-semibold tabular-nums">{{ fmt(data.cluster.totalNodes) }}</div>
                </div>
                <div>
                  <div class="text-12px op-60">运维告警</div>
                  <div class="mt-4px text-17px font-semibold tabular-nums">{{ data.alerts.length }}</div>
                </div>
                <div>
                  <div class="text-12px op-60">熔断对象</div>
                  <div class="mt-4px text-17px font-semibold tabular-nums">{{ data.circuits.length }}</div>
                </div>
              </div>
            </section>
          </div>

          <!-- 降级切换统计 -->
          <section class="border border-gray-500/20 rounded-12px p-16px">
            <div class="flex items-center justify-between gap-12px">
              <h3 class="m-0 text-13px font-semibold">降级切换统计</h3>
              <span class="text-12px op-60">总计 {{ fmt(data.fallbackTotal) }}</span>
            </div>
            <div v-if="!fallbackRows.length" class="mt-14px border border-gray-500/30 rounded-10px border-dashed px-12px py-16px text-13px op-60">
              当前没有记录到降级切换。
            </div>
            <div v-else class="mt-14px flex-col gap-12px">
              <div v-for="item in fallbackRows" :key="item.reason" class="border border-gray-500/15 rounded-10px bg-gray-500/4 px-12px py-10px">
                <div class="flex items-center justify-between gap-12px">
                  <span class="truncate text-13px font-medium">{{ item.reason || '未知原因' }}</span>
                  <span class="text-13px font-semibold tabular-nums">{{ fmt(item.total) }}</span>
                </div>
                <div class="mt-8px h-6px overflow-hidden rounded-full bg-gray-500/15">
                  <NProgress
                    type="line"
                    :percentage="Math.max(item.ratio, 8)"
                    :show-indicator="false"
                    :height="6"
                  />
                </div>
              </div>
            </div>
          </section>
        </div>

        <div class="grid grid-cols-1 items-stretch gap-16px lg:grid-cols-2">
          <!-- 最近错误 -->
          <section class="h-full flex flex-col border border-gray-500/20 rounded-12px p-16px">
            <div class="flex items-center justify-between gap-12px">
              <h3 class="m-0 text-13px font-semibold">最近错误</h3>
              <div class="flex items-center gap-8px">
                <NButton v-if="hasMoreErrors" size="tiny" quaternary @click="errorsExpanded = !errorsExpanded">
                  <template #icon>
                    <SvgIcon :icon="errorsExpanded ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
                  </template>
                  {{ errorsExpanded ? '收起' : `展开 ${recentErrors.length}` }}
                </NButton>
                <span class="text-12px op-60">{{ fmt(data.errorsLast24h) }} / 24h</span>
              </div>
            </div>
            <div v-if="!recentErrors.length" class="mt-14px flex-1 border border-gray-500/30 rounded-10px border-dashed px-12px py-16px text-13px op-60">
              当前没有最近错误。
            </div>
            <div v-else class="mt-14px min-h-0 flex-1 flex-col gap-12px" :class="errorsExpanded ? 'max-h-360px overflow-y-auto pr-4px' : ''">
              <article v-for="item in visibleErrors" :key="item.requestId" class="border border-gray-500/15 rounded-10px bg-gray-500/4 px-12px py-10px">
                <div class="flex items-start justify-between gap-12px">
                  <div class="min-w-0">
                    <div class="truncate text-13px font-medium">{{ item.errorCode || `HTTP_${item.httpStatus}` }}</div>
                    <div class="mt-2px truncate text-12px op-60">{{ item.model || '-' }}</div>
                  </div>
                  <span class="shrink-0 text-12px op-60">{{ formatTime(item.createDt) }}</span>
                </div>
                <div class="mt-8px flex flex-wrap gap-6px">
                  <NTag size="tiny">HTTP {{ item.httpStatus ?? '-' }}</NTag>
                  <NTag size="tiny">{{ item.channelName || '未知渠道' }}</NTag>
                </div>
              </article>
            </div>
          </section>

          <!-- 熔断现状与建议 -->
          <section class="h-full flex flex-col border border-gray-500/20 rounded-12px p-16px">
            <div class="flex items-center justify-between gap-12px">
              <h3 class="m-0 text-13px font-semibold">熔断现状与建议</h3>
              <span class="text-12px op-60">熔断中 {{ data.circuits.length }}</span>
            </div>
            <div v-if="!data.circuits.length" class="mt-14px border border-gray-500/30 rounded-10px border-dashed px-12px py-16px text-13px op-60">
              当前没有熔断对象。
            </div>
            <div v-else class="mt-14px flex-col gap-12px">
              <article v-for="(item, index) in data.circuits" :key="index" class="border border-gray-500/15 rounded-10px bg-gray-500/4 px-12px py-10px">
                <div class="flex items-center justify-between gap-12px">
                  <div class="flex min-w-0 items-center gap-8px">
                    <span class="truncate text-13px font-medium">{{ item.name }}</span>
                    <NTag size="tiny" :type="item.kind === 'channel' ? 'error' : 'warning'">
                      {{ item.kind === 'channel' ? '渠道熔断' : '凭证熔断' }}
                    </NTag>
                  </div>
                  <span class="shrink-0 text-12px op-60">健康分 {{ item.healthScore ?? '-' }}</span>
                </div>
                <div v-if="item.channelName" class="mt-4px text-12px op-60">所属渠道：{{ item.channelName }}</div>
              </article>
            </div>
            <div class="mt-14px border-t border-gray-500/15 pt-14px">
              <h4 class="m-0 text-11px font-semibold uppercase tracking-wide op-60">建议</h4>
              <ul v-if="data.alerts.length" class="m-0 mt-10px flex-col gap-8px p-0 text-13px">
                <li v-for="(alert, index) in data.alerts" :key="index" class="list-none border border-gray-500/15 rounded-10px bg-gray-500/4 px-12px py-8px">
                  <NTag size="tiny" :type="alert.level === 'critical' ? 'error' : 'warning'" class="mr-6px">
                    {{ alert.level === 'critical' ? '严重' : '警告' }}
                  </NTag>
                  {{ alert.message }}
                </li>
              </ul>
              <div v-else class="mt-10px text-13px op-60">当前没有额外运维建议。</div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </NCard>
</template>
