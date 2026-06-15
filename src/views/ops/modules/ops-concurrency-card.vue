<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchRuntimeChannels, fetchRuntimeKeys } from '@/service/api';

defineOptions({ name: 'OpsConcurrencyCard' });

/**
 * 并发/可用性卡（sub2api OpsConcurrencyCard 对位·概念映射：platform/group→渠道、account→渠道 Key）：
 * 渠道/Key 两维度切换，展示可调度占比、并发槽占用、健康分与熔断状态。
 */
const props = defineProps<{ refreshToken: number; channelId?: number }>();

const dimension = ref<'channel' | 'key'>('channel');
const loading = ref(false);
const channels = ref<NexusAI.RuntimeChannel[]>([]);
const keys = ref<NexusAI.RuntimeKey[]>([]);

const channelRows = computed(() =>
  channels.value.filter(c => props.channelId == null || Number(c.channelId) === props.channelId)
);
const keyRows = computed(() =>
  keys.value.filter(k => props.channelId == null || Number(k.channelId) === props.channelId)
);

const summary = computed(() => {
  if (dimension.value === 'channel') {
    const rows = channelRows.value;
    return {
      total: rows.length,
      schedulable: rows.filter(c => c.schedulable).length,
      occupied: keyRows.value.reduce((s, k) => s + Number(k.occupied ?? 0), 0)
    };
  }
  const rows = keyRows.value;
  return {
    total: rows.length,
    schedulable: rows.filter(k => k.schedulable).length,
    occupied: rows.reduce((s, k) => s + Number(k.occupied ?? 0), 0)
  };
});

function scoreClass(score?: number) {
  const s = Number(score ?? 100);
  if (s >= 80) return 'text-green-600';
  if (s >= 50) return 'text-amber-500';
  return 'text-red-500';
}

async function load() {
  loading.value = true;
  const [c, k] = await Promise.all([fetchRuntimeChannels(), fetchRuntimeKeys()]);
  if (!c.error && c.data) channels.value = c.data;
  if (!k.error && k.data) keys.value = k.data;
  loading.value = false;
}

watch(
  () => props.refreshToken,
  () => load()
);

onMounted(load);
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <div class="flex items-center justify-between">
      <div>
        <h3 class="m-0 text-14px font-semibold">并发与可用性</h3>
        <p class="m-0 mt-2px text-12px op-60">渠道 / Key 运行态</p>
      </div>
      <NButtonGroup size="tiny">
        <NButton :type="dimension === 'channel' ? 'primary' : 'default'" @click="dimension = 'channel'">渠道</NButton>
        <NButton :type="dimension === 'key' ? 'primary' : 'default'" @click="dimension = 'key'">Key</NButton>
      </NButtonGroup>
    </div>

    <div class="mt-10px grid grid-cols-3 gap-8px text-center">
      <div class="rounded-6px bg-gray-50 py-6px dark:bg-gray-800">
        <div class="text-15px font-semibold tabular-nums">{{ summary.schedulable }}/{{ summary.total }}</div>
        <div class="text-11px text-gray-400">可调度</div>
      </div>
      <div class="rounded-6px bg-gray-50 py-6px dark:bg-gray-800">
        <div class="text-15px font-semibold tabular-nums">{{ summary.occupied }}</div>
        <div class="text-11px text-gray-400">占用槽</div>
      </div>
      <div class="rounded-6px bg-gray-50 py-6px dark:bg-gray-800">
        <div class="text-15px font-semibold tabular-nums" :class="summary.total - summary.schedulable > 0 ? 'text-red-500' : ''">
          {{ summary.total - summary.schedulable }}
        </div>
        <div class="text-11px text-gray-400">熔断中</div>
      </div>
    </div>

    <NSpin :show="loading">
      <div class="mt-10px max-h-150px space-y-6px overflow-y-auto">
        <template v-if="dimension === 'channel'">
          <NEmpty v-if="channelRows.length === 0" description="暂无渠道" class="py-12px" />
          <div
            v-for="c in channelRows"
            :key="c.channelId"
            class="flex items-center justify-between rounded-6px border border-gray-100 px-8px py-5px text-12px dark:border-gray-800"
          >
            <div class="flex items-center gap-6px truncate">
              <span class="h-6px w-6px rounded-full" :class="c.schedulable ? 'bg-green-500' : 'bg-red-500'" />
              <span class="truncate">{{ c.name ?? `渠道 ${c.channelId}` }}</span>
            </div>
            <div class="flex items-center gap-8px tabular-nums">
              <span :class="scoreClass(c.healthScore)">{{ Math.round(Number(c.healthScore ?? 100)) }}</span>
              <span class="text-gray-400">{{ Math.round(Number(c.latencyEwmaMs ?? 0)) }}ms</span>
            </div>
          </div>
        </template>
        <template v-else>
          <NEmpty v-if="keyRows.length === 0" description="暂无 Key" class="py-12px" />
          <div
            v-for="k in keyRows"
            :key="k.keyId"
            class="flex items-center justify-between rounded-6px border border-gray-100 px-8px py-5px text-12px dark:border-gray-800"
          >
            <div class="flex items-center gap-6px">
              <span class="h-6px w-6px rounded-full" :class="k.schedulable ? 'bg-green-500' : 'bg-red-500'" />
              <span>Key #{{ k.keyId }}</span>
              <span class="text-gray-400">渠道 {{ k.channelId }}</span>
            </div>
            <div class="flex items-center gap-8px tabular-nums">
              <span :class="scoreClass(k.healthScore)">{{ Math.round(Number(k.healthScore ?? 100)) }}</span>
              <span class="text-gray-400" :title="'占用并发槽'">
                <SvgIcon icon="carbon:meter" class="inline text-12px" />
                {{ k.occupied ?? 0 }}
              </span>
            </div>
          </div>
        </template>
      </div>
    </NSpin>
  </NCard>
</template>

<style scoped></style>
