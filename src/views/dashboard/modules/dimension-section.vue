<!-- 维度分析折叠区（一比一 axonhub：请求量/Token 两卡=柱图 + 序号图例·两卡各持独立时段并各自排序·去钱化无成本列） -->
<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useThemeVars } from 'naive-ui';
import CollapsibleSection from '../components/collapsible-section.vue';
import TimePeriodSelector from '../components/time-period-selector.vue';
import DimensionRequestsChart from '../components/dimension-requests-chart.vue';
import DimensionTokensChart from '../components/dimension-tokens-chart.vue';
import ChartLegend from '../components/chart-legend.vue';
import DashCard from '../components/dash-card.vue';
import { DIM_COLORS } from '../composables/chart-base';
import { totalTokensOf, useDimensionStats } from '../composables/use-dimension-stats';
import { formatCompact } from '../composables/use-dashboard';

defineOptions({ name: 'DimensionSection' });

interface Props {
  dim: 'channel' | 'model' | 'key';
  title: string;
  icon: string;
  storageKey: string;
  /** key → 展示名映射（channel/key 查列表·model 取自身） */
  resolveName: (k?: string) => string;
  defaultOpen?: boolean;
}
const props = withDefaults(defineProps<Props>(), { defaultOpen: false });

const theme = useThemeVars();
const { reqWindow, tokWindow, reqRows, tokRows, load } = useDimensionStats(props.dim, props.resolveName);
onMounted(load);

const dimLabel = computed(() => (props.dim === 'channel' ? '渠道' : props.dim === 'model' ? '模型' : '上游凭证'));

/** 请求量图例（序号 + 圆点 + name + 请求量/占比%）。 */
const reqLegend = computed(() => {
  const sum = reqRows.value.reduce((s, r) => s + Number(r.total ?? 0), 0) || 1;
  return reqRows.value.map((r, i) => {
    const v = Number(r.total ?? 0);
    return {
      name: r.name,
      index: i + 1,
      color: DIM_COLORS[i % DIM_COLORS.length],
      primaryValue: formatCompact(v),
      secondaryValue: `${((v / sum) * 100).toFixed(1)}%`
    };
  });
});

/** Token 图例（序号 + 圆点 + name + Token 合计/占比%）。 */
const tokLegend = computed(() => {
  const vals = tokRows.value.map(totalTokensOf);
  const sum = vals.reduce((s, t) => s + t, 0) || 1;
  return tokRows.value.map((r, i) => ({
    name: r.name,
    index: i + 1,
    color: DIM_COLORS[i % DIM_COLORS.length],
    primaryValue: formatCompact(vals[i]),
    secondaryValue: `${((vals[i] / sum) * 100).toFixed(1)}%`
  }));
});
</script>

<template>
  <CollapsibleSection :title="title" :icon="icon" :storage-key="storageKey" :default-open="defaultOpen">
    <NGrid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <!-- 请求量分布 -->
      <NGridItem span="2 m:1">
        <DashCard class="h-full">
          <div class="flex flex-row items-start justify-between gap-2 px-24px">
            <div class="flex flex-col gap-4px">
              <span class="text-16px font-semibold leading-none">按{{ dimLabel }}请求量</span>
              <span class="text-14px" :style="{ color: theme.textColor3 }">各{{ dimLabel }}的请求数分布。</span>
            </div>
            <TimePeriodSelector v-model:value="reqWindow" />
          </div>
          <div class="px-24px">
            <DimensionRequestsChart :rows="reqRows" />
            <!-- axonhub space-y-6：图与图例间 24px -->
            <ChartLegend :items="reqLegend" class="mt-24px" />
          </div>
        </DashCard>
      </NGridItem>

      <!-- Token 分布 -->
      <NGridItem span="2 m:1">
        <DashCard class="h-full">
          <div class="flex flex-row items-start justify-between gap-2 px-24px">
            <div class="flex flex-col gap-4px">
              <span class="text-16px font-semibold leading-none">按{{ dimLabel }}统计Token</span>
              <span class="text-14px" :style="{ color: theme.textColor3 }">各{{ dimLabel }}的Token使用分布。</span>
            </div>
            <TimePeriodSelector v-model:value="tokWindow" />
          </div>
          <div class="px-24px">
            <DimensionTokensChart :rows="tokRows" />
            <ChartLegend :items="tokLegend" class="mt-24px" />
          </div>
        </DashCard>
      </NGridItem>
    </NGrid>
  </CollapsibleSection>
</template>
