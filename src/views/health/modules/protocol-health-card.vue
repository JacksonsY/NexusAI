<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useThemeVars } from 'naive-ui';
import { fetchHealthProtocols } from '@/service/api';
import { healthTagType } from '../composables/use-health-format';
import HealthTimeline from './health-timeline.vue';
import MonitorShell from './monitor-shell.vue';

defineOptions({ name: 'ProtocolHealthCard' });

const theme = useThemeVars();
const loading = ref(false);
const lookbackHours = ref(6);
const formats = ref<NexusAI.ProtocolHealth[]>([]);

async function loadData() {
  loading.value = true;
  try {
    const { data, error } = await fetchHealthProtocols(lookbackHours.value);
    if (!error) {
      formats.value = data?.formats ?? [];
    }
  } finally {
    loading.value = false;
  }
}

/** 协议族展示名（OpenAI / Anthropic / Gemini…·api_format_display_name 心法）。 */
function protocolLabel(protocol: string) {
  const known: Record<string, string> = { openai: 'OpenAI', anthropic: 'Anthropic', gemini: 'Gemini' };
  return known[protocol] ?? protocol;
}

watch(lookbackHours, loadData);
onMounted(loadData);
</script>

<template>
  <MonitorShell
    v-model:lookback="lookbackHours"
    title="端点健康监控"
    subtitle="基于真实请求统计协议端点可用率、请求成功率与健康历史"
    :loading="loading"
    :empty="formats.length === 0"
    empty-icon="lucide:activity"
    empty-text="暂无健康监控数据"
    empty-sub-text="端点尚未产生请求记录"
    @refresh="loadData"
  >
    <div class="space-y-12px">
      <div
        v-for="monitor in formats"
        :key="monitor.protocol"
        class="protocol-row border border-gray-500/20 rounded-10px p-16px transition-colors duration-150"
      >
        <div class="flex flex-col sm:flex-row sm:items-center sm:gap-24px">
          <div class="mb-12px shrink-0 space-y-6px sm:mb-0 sm:w-208px">
            <div class="flex flex-wrap items-center gap-8px">
              <NTag size="small" class="font-mono">{{ protocolLabel(monitor.protocol) }}</NTag>
              <NTag v-if="Number(monitor.totalAttempts) > 0" :type="healthTagType(monitor)" size="small" round>
                {{ (monitor.successRate * 100).toFixed(0) }}%
              </NTag>
            </div>
            <div class="text-12px op-60">{{ monitor.channelCount }} 个渠道 / {{ monitor.keyCount }} 个凭证</div>
          </div>
          <div class="min-w-0 flex-1">
            <HealthTimeline
              :timeline="monitor.timeline"
              :time-range-start="monitor.timeRangeStart"
              :time-range-end="monitor.timeRangeEnd"
              :height="24"
            />
          </div>
        </div>
      </div>
    </div>
  </MonitorShell>
</template>

<style scoped>
.protocol-row:hover {
  border-color: v-bind('`${theme.primaryColor}80`');
}
</style>
