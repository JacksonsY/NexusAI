<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useThemeVars } from 'naive-ui';
import { fetchHealthChannels } from '@/service/api';
import { formatCompact, formatMs, formatPercent, healthLabel, healthTagType, successRateClass } from '../composables/use-health-format';
import MetricBox from './metric-box.vue';
import ModelHealthTile from './model-health-tile.vue';
import MonitorShell from './monitor-shell.vue';
import PerfDetailModal from './perf-detail-modal.vue';

defineOptions({ name: 'ChannelHealthCard' });

const theme = useThemeVars();
const loading = ref(false);
const lookbackHours = ref(6);
const channels = ref<NexusAI.ChannelHealth[]>([]);
const expanded = ref<Record<string, boolean>>({});

const detailShow = ref(false);
const detailDim = ref<'model' | 'channel'>('channel');
const detailKey = ref('');
const detailName = ref('');

/** 渠道行「性能详情」入口（dim=channel·key=渠道 id）。 */
function openChannelDetail(channel: NexusAI.ChannelHealth) {
  detailDim.value = 'channel';
  detailKey.value = String(channel.channelId);
  detailName.value = channel.channelName;
  detailShow.value = true;
}

/** 展开区模型瓦片入口（dim=model）。 */
function openModelDetail(model: string) {
  detailDim.value = 'model';
  detailKey.value = model;
  detailName.value = model;
  detailShow.value = true;
}

async function loadData() {
  loading.value = true;
  try {
    const { data, error } = await fetchHealthChannels(lookbackHours.value);
    if (!error) {
      channels.value = data?.channels ?? [];
    }
  } finally {
    loading.value = false;
  }
}

function toggle(channelId: number | string) {
  const key = String(channelId);
  expanded.value = { ...expanded.value, [key]: !expanded.value[key] };
}

function isExpanded(channelId: number | string) {
  return Boolean(expanded.value[String(channelId)]);
}

watch(lookbackHours, loadData);
onMounted(loadData);
</script>

<template>
  <div>
    <MonitorShell
      v-model:lookback="lookbackHours"
      title="渠道健康监控"
      subtitle="故障渠道排前，展开后查看该渠道下的模型健康明细"
      :loading="loading"
      :empty="channels.length === 0"
      empty-icon="lucide:server"
      empty-text="暂无渠道健康数据"
      empty-sub-text="当前没有启用渠道或尚未产生请求记录"
      @refresh="loadData"
    >
    <div class="space-y-12px">
      <div v-for="channel in channels" :key="String(channel.channelId)" class="channel-row overflow-hidden border border-gray-500/20 rounded-12px">
        <div
          role="button"
          tabindex="0"
          class="w-full flex flex-col cursor-pointer gap-16px p-16px text-left transition-colors duration-150 lg:flex-row lg:items-center lg:justify-between hover:bg-gray-500/5"
          :aria-expanded="isExpanded(channel.channelId)"
          :aria-label="`展开 ${channel.channelName} 模型明细`"
          @click="toggle(channel.channelId)"
          @keydown.enter="toggle(channel.channelId)"
        >
          <div class="min-w-0 flex items-start gap-12px">
            <div class="h-44px w-44px flex shrink-0 items-center justify-center border border-gray-500/20 rounded-12px bg-gray-500/8">
              <SvgIcon icon="lucide:server" class="text-20px op-60" />
            </div>
            <div class="min-w-0">
              <div class="min-w-0 flex flex-wrap items-center gap-8px">
                <SvgIcon
                  icon="lucide:chevron-right"
                  class="shrink-0 text-16px op-60 transition-transform duration-150"
                  :class="{ 'rotate-90': isExpanded(channel.channelId) }"
                />
                <h4 class="m-0 truncate text-14px font-semibold">{{ channel.channelName }}</h4>
                <NTag size="small" class="font-mono">{{ channel.protocol }}</NTag>
                <NTag v-if="channel.status === 0" size="small">已停用</NTag>
                <NTag :type="healthTagType(channel)" size="small" round>{{ healthLabel(channel) }}</NTag>
              </div>
              <p class="m-0 mt-4px text-12px op-60">
                {{ channel.modelCount }} 个模型 / {{ formatCompact(channel.totalAttempts) }} 次请求
              </p>
            </div>
          </div>

          <div class="flex items-center gap-12px lg:w-640px">
            <div class="grid grid-cols-3 min-w-0 w-full gap-8px">
              <MetricBox label="延迟" :value="formatMs(channel.avgLatencyMs)" />
              <MetricBox label="Ping" :value="formatMs(channel.avgFirstByteMs)" />
              <MetricBox label="可用率" :value="formatPercent(channel.successRate)" :value-class="successRateClass(channel.successRate)" />
            </div>
            <NTooltip>
              <template #trigger>
                <NButton
                  size="small"
                  circle
                  quaternary
                  :aria-label="`查看 ${channel.channelName} 性能详情`"
                  @click.stop="openChannelDetail(channel)"
                >
                  <template #icon>
                    <SvgIcon icon="lucide:line-chart" />
                  </template>
                </NButton>
              </template>
              性能详情
            </NTooltip>
          </div>
        </div>

        <NCollapseTransition :show="isExpanded(channel.channelId)">
          <div class="border-t border-gray-500/15 p-16px">
            <div
              v-if="channel.models.length === 0"
              class="border border-gray-500/20 rounded-10px border-dashed py-32px text-center text-13px op-60"
            >
              该渠道在当前时间范围内暂无模型请求
            </div>
            <div v-else class="grid grid-cols-1 gap-16px md:grid-cols-2 xl:grid-cols-3">
              <ModelHealthTile
                v-for="model in channel.models"
                :key="`${channel.channelId}-${model.model}`"
                :item="model"
                @detail="openModelDetail"
              />
            </div>
          </div>
        </NCollapseTransition>
      </div>
    </div>
    </MonitorShell>

    <PerfDetailModal v-model:show="detailShow" :dim="detailDim" :series-key="detailKey" :name="detailName" />
  </div>
</template>

<style scoped>
.channel-row:hover {
  border-color: v-bind('`${theme.primaryColor}80`');
}
</style>
