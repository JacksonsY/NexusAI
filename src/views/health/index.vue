<script setup lang="ts">
import { ref, watch } from 'vue';
import ChannelHealthCard from './modules/channel-health-card.vue';
import ModelHealthCard from './modules/model-health-card.vue';
import ProtocolHealthCard from './modules/protocol-health-card.vue';

defineOptions({ name: 'HealthPage' });

/** 健康监控（一级菜单单页·HealthMonitor）：端点/模型/渠道三 Tab，访问过的 Tab 懒挂载后保留。 */
const activeTab = ref('endpoint');
const visitedTabs = ref<Record<string, boolean>>({ endpoint: true });

watch(activeTab, value => {
  visitedTabs.value = { ...visitedTabs.value, [value]: true };
});
</script>

<template>
  <div class="flex-col gap-16px pb-16px">
    <div class="max-w-672px">
      <NTabs v-model:value="activeTab" type="segment" animated>
        <NTab name="endpoint">端点健康监控</NTab>
        <NTab name="model">模型健康监控</NTab>
        <NTab name="channel">渠道健康监控</NTab>
      </NTabs>
    </div>

    <ProtocolHealthCard v-if="visitedTabs.endpoint" v-show="activeTab === 'endpoint'" />
    <ModelHealthCard v-if="visitedTabs.model" v-show="activeTab === 'model'" />
    <ChannelHealthCard v-if="visitedTabs.channel" v-show="activeTab === 'channel'" />
  </div>
</template>
