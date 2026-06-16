<script setup lang="ts">
import { ref, watch } from 'vue';
import { fetchModelRouting } from '@/service/api';
import { num } from '../composables/use-model-format';

defineOptions({ name: 'ModelRoutingTab' });

interface Props {
  modelKey: string;
  /** Tab 激活时才拉数据 */
  active?: boolean;
}

const props = defineProps<Props>();
const emit = defineEmits<{ assignChannels: [] }>();

const loading = ref(false);
const data = ref<NexusAI.ModelRouting | null>(null);
const expandedProtocols = ref<Set<string>>(new Set());
const expandedChannels = ref<Set<string>>(new Set());

const SCHEDULING_LABELS: Record<string, string> = {
  cache_affinity: '缓存亲和',
  load_balance: '负载均衡',
  fixed_order: '固定顺序'
};
const PRIORITY_LABELS: Record<string, string> = {
  provider: '提供商优先',
  key: 'Key 优先'
};

async function load() {
  if (!props.modelKey) return;
  loading.value = true;
  const { data: resp } = await fetchModelRouting(props.modelKey);
  data.value = resp ?? null;
  // 默认展开全部协议组（初始展开第一组·组少全开更直观）
  expandedProtocols.value = new Set((resp?.groups ?? []).map(g => g.protocol));
  loading.value = false;
}

watch(
  () => [props.active, props.modelKey],
  ([active]) => {
    if (active) load();
  },
  { immediate: true }
);

function toggleProtocol(protocol: string) {
  const set = new Set(expandedProtocols.value);
  if (set.has(protocol)) set.delete(protocol);
  else set.add(protocol);
  expandedProtocols.value = set;
}

function channelKey(group: string, channelId: number | string) {
  return `${group}:${channelId}`;
}

function toggleChannel(group: string, channelId: number | string) {
  const key = channelKey(group, channelId);
  const set = new Set(expandedChannels.value);
  if (set.has(key)) set.delete(key);
  else set.add(key);
  expandedChannels.value = set;
}

function healthBarColor(score?: number) {
  const s = score ?? 100;
  if (s >= 80) return 'bg-success';
  if (s >= 50) return 'bg-warning';
  return 'bg-error';
}

function healthTextColor(score?: number) {
  const s = score ?? 100;
  if (s >= 80) return 'text-success';
  if (s >= 50) return 'text-warning';
  return 'text-error';
}

/** 熔断倒计时（blockedUntil > now）。 */
function blockedSeconds(key: NexusAI.ModelRoutingKey): number {
  const until = num(key.blockedUntil) ?? 0;
  const nowMs = num(data.value?.now) ?? Date.now();
  return until > nowMs ? Math.ceil((until - nowMs) / 1000) : 0;
}

function statusDot(enabled: boolean | undefined, schedulable: boolean | undefined) {
  if (!enabled) return 'bg-gray-300 dark:bg-gray-600';
  return schedulable ? 'bg-success' : 'bg-error';
}
</script>

<template>
  <div class="pt-8px">
    <!-- 标题栏：链路预览 · 调度模式 · 操作 -->
    <div class="mb-8px flex items-center justify-between gap-8px">
      <div class="flex items-baseline gap-6px min-w-0">
        <h4 class="text-13px font-semibold shrink-0">链路预览</h4>
        <template v-if="data">
          <span class="text-12px text-gray-400">·</span>
          <span class="text-12px text-gray-400">{{ SCHEDULING_LABELS[data.schedulingMode] || data.schedulingMode }}</span>
          <span class="text-12px text-gray-400">·</span>
          <span class="text-12px text-gray-400">{{ PRIORITY_LABELS[data.priorityMode] || data.priorityMode }}</span>
        </template>
      </div>
      <NSpace :size="4">
        <NButton quaternary circle size="small" title="关联提供商" @click="emit('assignChannels')">
          <SvgIcon icon="lucide:link" />
        </NButton>
        <NButton quaternary circle size="small" title="刷新" :loading="loading" @click="load">
          <SvgIcon icon="ic:round-refresh" />
        </NButton>
      </NSpace>
    </div>

    <div v-if="loading && !data" class="flex-center py-48px">
      <NSpin size="small" />
    </div>

    <template v-else-if="data">
      <!-- 空态 -->
      <NEmpty v-if="data.groups.length === 0" description="暂无关联提供商" class="py-32px">
        <template #extra>
          <NButton size="small" @click="emit('assignChannels')">为此模型关联提供商</NButton>
        </template>
      </NEmpty>

      <!-- 协议家族分组 -->
      <div v-else class="space-y-12px">
        <div
          v-for="group in data.groups"
          :key="group.protocol"
          class="overflow-hidden border border-gray-200 rounded-10px dark:border-gray-700"
        >
          <!-- 组头 -->
          <div
            class="flex cursor-pointer items-center justify-between bg-gray-50 px-12px py-8px transition-colors dark:bg-gray-800/60 hover:bg-gray-100 dark:hover:bg-gray-800"
            @click="toggleProtocol(group.protocol)"
          >
            <NTag size="small" type="info" :bordered="false" class="font-semibold uppercase">
              {{ group.protocol }}
            </NTag>
            <div class="flex items-center gap-8px text-12px text-gray-400">
              <span>
                {{ num(group.activeKeys) ?? 0 }}/{{ num(group.totalKeys) ?? 0 }} Keys
                <span class="mx-4px">·</span>
                {{ num(group.activeChannels) ?? 0 }}/{{ num(group.totalChannels) ?? 0 }} 提供商
              </span>
              <SvgIcon
                icon="lucide:chevron-down"
                class="transition-transform"
                :class="expandedProtocols.has(group.protocol) ? 'rotate-180' : ''"
              />
            </div>
          </div>

          <!-- 渠道垂直时间线 -->
          <div v-if="expandedProtocols.has(group.protocol)" class="py-6px pl-10px pr-10px">
            <div v-for="(ch, idx) in group.channels" :key="String(ch.channelId)">
              <div class="flex py-3px">
                <!-- 节点+连线 -->
                <div class="w-22px flex flex-col items-center self-stretch shrink-0">
                  <div class="w-2px h-12px" :class="idx === 0 ? 'bg-transparent' : 'bg-gray-200 dark:bg-gray-700'" />
                  <div
                    class="h-10px w-10px rounded-full border-2 shrink-0"
                    :class="
                      idx === 0 && ch.schedulable && ch.abilityEnabled
                        ? 'bg-primary border-primary'
                        : 'bg-white border-gray-300 dark:bg-gray-900 dark:border-gray-600'
                    "
                  />
                  <div
                    class="w-2px flex-1"
                    :class="idx === group.channels.length - 1 ? 'bg-transparent' : 'bg-gray-200 dark:bg-gray-700'"
                  />
                </div>

                <!-- 渠道卡片 -->
                <div class="flex-1 min-w-0" :class="!ch.abilityEnabled || ch.channelStatus !== 1 ? 'opacity-50' : ''">
                  <div
                    class="rounded-8px transition-all"
                    :class="idx === 0 ? 'bg-primary/5 ring-1 ring-primary/20' : 'hover:bg-gray-50 dark:hover:bg-gray-800/60'"
                  >
                    <div class="cursor-pointer p-8px" @click="toggleChannel(group.protocol, ch.channelId)">
                      <div class="flex items-center gap-8px">
                        <span
                          class="min-w-32px rounded-full px-6px py-1px text-center text-10px font-medium shrink-0"
                          :class="idx === 0 ? 'bg-primary text-white' : 'bg-gray-200 text-gray-500 dark:bg-gray-700 dark:text-gray-400'"
                        >
                          {{ idx === 0 ? '首选' : `P${ch.priority ?? 0}` }}
                        </span>
                        <span class="h-6px w-6px rounded-full shrink-0" :class="statusDot(ch.abilityEnabled && ch.channelStatus === 1, ch.schedulable)" />
                        <div class="min-w-0 flex-1">
                          <div class="flex items-center gap-4px">
                            <span class="truncate text-13px font-medium">{{ ch.name }}</span>
                            <span v-if="ch.targetModel && ch.targetModel !== props.modelKey" class="text-10px text-gray-400 shrink-0">
                              ({{ ch.targetModel }})
                            </span>
                          </div>
                          <div class="truncate text-10px text-gray-400">{{ ch.baseUrl }}</div>
                        </div>
                        <div class="flex items-center gap-6px shrink-0 text-10px text-gray-400">
                          <span>权重 {{ ch.weight ?? 0 }}</span>
                          <span>{{ ch.keys.filter(k => k.schedulable).length }}/{{ ch.keys.length }} Keys</span>
                          <div class="flex items-center gap-3px">
                            <div class="h-5px w-36px overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                              <div class="h-full transition-all" :class="healthBarColor(ch.healthScore)" :style="{ width: `${ch.healthScore ?? 100}%` }" />
                            </div>
                            <span class="w-32px whitespace-nowrap text-right font-medium tabular-nums" :class="healthTextColor(ch.healthScore)">
                              {{ ch.healthScore ?? 100 }}%
                            </span>
                          </div>
                          <SvgIcon
                            icon="lucide:chevron-down"
                            class="transition-transform"
                            :class="expandedChannels.has(channelKey(group.protocol, ch.channelId)) ? 'rotate-180' : ''"
                          />
                        </div>
                      </div>
                    </div>

                    <!-- 展开：Key 池 -->
                    <div
                      v-if="expandedChannels.has(channelKey(group.protocol, ch.channelId))"
                      class="border-t border-gray-100 p-8px dark:border-gray-800"
                    >
                      <NEmpty v-if="ch.keys.length === 0" description="该渠道暂无 Key" size="small" class="py-8px" />
                      <div v-else class="space-y-2px">
                        <div v-for="(key, ki) in ch.keys" :key="String(key.keyId)" class="flex items-center gap-8px">
                          <div class="min-w-32px flex items-center justify-center shrink-0">
                            <div
                              class="h-7px w-7px rounded-full border-2"
                              :class="ki === 0 ? 'bg-primary border-primary' : 'bg-white border-gray-300 dark:bg-gray-900 dark:border-gray-600'"
                            />
                          </div>
                          <span class="h-5px w-5px rounded-full shrink-0" :class="statusDot(key.status === 1, key.schedulable)" />
                          <div
                            class="my-1px flex flex-1 items-center gap-6px rounded-6px px-8px py-4px text-12px"
                            :class="[ki === 0 ? 'bg-primary/5' : 'bg-gray-50 dark:bg-gray-800/50', key.status !== 1 ? 'opacity-50' : '']"
                          >
                            <code class="text-gray-500">{{ key.masked }}</code>
                            <span class="text-10px text-gray-400">P{{ key.priority ?? 0 }} · 权重 {{ key.weight ?? 0 }}</span>
                            <span v-if="num(key.latencyEwmaMs)" class="text-10px text-gray-400">{{ num(key.latencyEwmaMs) }}ms</span>
                            <NTag v-if="blockedSeconds(key) > 0" size="tiny" type="error" class="tabular-nums">
                              熔断 {{ blockedSeconds(key) }}s
                            </NTag>
                            <div class="ml-auto flex items-center gap-3px shrink-0">
                              <div class="h-4px w-32px overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                                <div class="h-full" :class="healthBarColor(key.healthScore)" :style="{ width: `${key.healthScore ?? 100}%` }" />
                              </div>
                              <span class="w-26px text-right text-10px font-medium tabular-nums" :class="healthTextColor(key.healthScore)">
                                {{ key.healthScore ?? 100 }}%
                              </span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
