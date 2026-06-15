<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { fetchHealthModels } from '@/service/api';
import ModelHealthTile from './model-health-tile.vue';
import MonitorShell from './monitor-shell.vue';
import PerfDetailModal from './perf-detail-modal.vue';

defineOptions({ name: 'ModelHealthCard' });

const loading = ref(false);
const lookbackHours = ref(6);
const models = ref<NexusAI.ModelHealth[]>([]);

const detailShow = ref(false);
const detailKey = ref('');

function openDetail(model: string) {
  detailKey.value = model;
  detailShow.value = true;
}

async function loadData() {
  loading.value = true;
  try {
    const { data, error } = await fetchHealthModels(lookbackHours.value);
    if (!error) {
      models.value = data?.models ?? [];
    }
  } finally {
    loading.value = false;
  }
}

watch(lookbackHours, loadData);
onMounted(loadData);
</script>

<template>
  <div>
    <MonitorShell
      v-model:lookback="lookbackHours"
      title="模型健康监控"
      subtitle="基于真实请求统计模型可用率、响应延迟与首包延迟"
      :loading="loading"
      :empty="models.length === 0"
      empty-icon="lucide:bot"
      empty-text="暂无模型健康监控数据"
      empty-sub-text="模型尚未产生请求记录"
      @refresh="loadData"
    >
      <div class="grid grid-cols-1 gap-16px md:grid-cols-2 xl:grid-cols-3">
        <ModelHealthTile v-for="monitor in models" :key="monitor.model" :item="monitor" show-channel-info @detail="openDetail" />
      </div>
    </MonitorShell>

    <PerfDetailModal v-model:show="detailShow" dim="model" :series-key="detailKey" :name="detailKey" />
  </div>
</template>
