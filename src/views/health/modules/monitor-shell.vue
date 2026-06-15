<script setup lang="ts">
defineOptions({ name: 'MonitorShell' });

/** 健康监控卡外壳（一比一 Aether 三卡公共骨架）：标题/副标 + 回溯时间选择 + 刷新 + loading/空态。 */
defineProps<{
  title: string;
  subtitle: string;
  loading: boolean;
  empty: boolean;
  emptyIcon: string;
  emptyText: string;
  emptySubText: string;
}>();

const lookback = defineModel<number>('lookback', { required: true });

const emit = defineEmits<{ refresh: [] }>();

const LOOKBACK_OPTIONS = [
  { label: '1 小时', value: 1 },
  { label: '6 小时', value: 6 },
  { label: '12 小时', value: 12 },
  { label: '24 小时', value: 24 },
  { label: '48 小时', value: 48 }
];
</script>

<template>
  <NCard :bordered="true" class="card-wrapper" content-class="!p-0">
    <div class="border-b border-gray-500/15 px-24px py-14px">
      <div class="flex flex-col gap-12px sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h3 class="m-0 text-16px font-semibold">{{ title }}</h3>
          <p class="m-0 mt-4px text-12px op-60">{{ subtitle }}</p>
        </div>
        <div class="flex items-center gap-12px">
          <span class="shrink-0 text-12px op-60">回溯时间：</span>
          <NSelect v-model:value="lookback" :options="LOOKBACK_OPTIONS" size="small" class="w-112px" aria-label="回溯时间" />
          <NButton size="small" circle quaternary :loading="loading" aria-label="刷新" @click="emit('refresh')">
            <template #icon>
              <SvgIcon icon="lucide:refresh-cw" />
            </template>
          </NButton>
        </div>
      </div>
    </div>

    <div class="p-24px">
      <div v-if="loading && empty" class="flex items-center justify-center py-48px op-60">
        <SvgIcon icon="lucide:loader-2" class="animate-spin text-22px" />
        <span class="ml-8px">加载中...</span>
      </div>
      <div v-else-if="empty" class="flex flex-col items-center justify-center py-48px op-60">
        <SvgIcon :icon="emptyIcon" class="mb-12px text-48px op-30" />
        <p class="m-0">{{ emptyText }}</p>
        <p class="m-0 mt-4px text-12px">{{ emptySubText }}</p>
      </div>
      <slot v-else />
    </div>
  </NCard>
</template>
