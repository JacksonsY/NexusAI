<script setup lang="ts">
import { watch } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';

defineOptions({ name: 'DistributionPieChart' });

const props = defineProps<{ data: { name: string; value: number }[]; title?: string }>();

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { type: 'scroll', bottom: 0 },
  series: [
    {
      name: '分布',
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 1 },
      label: { formatter: '{b}\n{d}%' },
      data: [] as { name: string; value: number }[]
    }
  ]
}));

watch(
  () => props.data,
  d => {
    updateOptions(opts => {
      opts.series[0].data = (d ?? []).filter(x => x.value > 0);
      return opts;
    });
  },
  { deep: true, immediate: true }
);
</script>

<template>
  <div ref="domRef" class="h-260px w-full" />
</template>
