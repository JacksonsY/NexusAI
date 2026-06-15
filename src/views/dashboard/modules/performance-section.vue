<!-- 性能分析折叠区（一比一 axonhub：每行=性能多曲线大图(4/7) + 最快榜(3/7)·模型/渠道两行·近30天逐日数据） -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useThemeVars } from 'naive-ui';
import CollapsibleSection from '../components/collapsible-section.vue';
import PerformanceChart from '../components/performance-chart.vue';
import FastestPerformersCard from '../components/fastest-performers-card.vue';
import DashCard from '../components/dash-card.vue';
import { formatCompact } from '../composables/use-dashboard';
import { fetchPerformanceDaily } from '@/service/api';

defineOptions({ name: 'PerformanceSection' });

interface Props {
  /** 渠道 key → 名称映射 */
  resolveChannelName: (k?: string) => string;
}
const props = defineProps<Props>();

const theme = useThemeVars();

interface ChartRow {
  date: string;
  id: string;
  name: string;
  throughput: number;
  ttftMs: number;
  requestCount: number;
}

const modelRows = ref<ChartRow[]>([]);
const channelRows = ref<ChartRow[]>([]);

function toRows(data: NexusAI.PerfDailyPoint[] | undefined, name: (k?: string) => string): ChartRow[] {
  return (data ?? []).map(p => ({
    date: p.date ?? '',
    id: p.key ?? '—',
    name: name(p.key),
    throughput: Number(p.throughput ?? 0),
    ttftMs: Number(p.ttftMs ?? 0),
    requestCount: Number(p.requestCount ?? 0)
  }));
}

onMounted(async () => {
  const [m, c] = await Promise.all([fetchPerformanceDaily('model', 30), fetchPerformanceDaily('channel', 30)]);
  modelRows.value = toRows(m.data, k => k ?? '—');
  channelRows.value = toRows(c.data, props.resolveChannelName);
});

/** 卡描述（一比一 axonhub performanceDescription）：共 N 次请求。 */
const modelDesc = computed(
  () => `显示过去30天的tokens/second与首个Token延迟，共${formatCompact(modelRows.value.reduce((s, r) => s + r.requestCount, 0))}次请求。`
);
const channelDesc = computed(
  () => `显示过去30天的tokens/second与首个Token延迟，共${formatCompact(channelRows.value.reduce((s, r) => s + r.requestCount, 0))}次请求。`
);
</script>

<template>
  <CollapsibleSection title="性能分析" icon="lucide:zap" storage-key="performance">
    <!-- 模型性能：多曲线大图 + 最快模型榜 -->
    <NGrid :cols="7" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <NGridItem span="7 m:4">
        <DashCard class="h-full">
          <div class="flex flex-col gap-4px px-24px">
            <span class="text-16px font-semibold leading-none">最佳模型性能</span>
            <span class="text-14px" :style="{ color: theme.textColor3 }">{{ modelDesc }}</span>
          </div>
          <div class="px-24px">
            <PerformanceChart :rows="modelRows" empty-text="暂无模型数据" />
          </div>
        </DashCard>
      </NGridItem>
      <NGridItem span="7 m:3">
        <FastestPerformersCard title="最快模型" type-label="模型" dim="model" />
      </NGridItem>
    </NGrid>

    <!-- 渠道性能：多曲线大图 + 最快渠道榜 -->
    <NGrid :cols="7" :x-gap="16" :y-gap="16" responsive="screen" item-responsive class="mt-16px">
      <NGridItem span="7 m:4">
        <DashCard class="h-full">
          <div class="flex flex-col gap-4px px-24px">
            <span class="text-16px font-semibold leading-none">最佳渠道性能</span>
            <span class="text-14px" :style="{ color: theme.textColor3 }">{{ channelDesc }}</span>
          </div>
          <div class="px-24px">
            <PerformanceChart :rows="channelRows" empty-text="暂无渠道数据" />
          </div>
        </DashCard>
      </NGridItem>
      <NGridItem span="7 m:3">
        <FastestPerformersCard title="最快渠道" type-label="渠道" dim="channel" :resolve-name="resolveChannelName" />
      </NGridItem>
    </NGrid>
  </CollapsibleSection>
</template>
