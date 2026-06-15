<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useThemeVars } from 'naive-ui';
import { useRouterPush } from '@/hooks/common/router';
import { fetchDailySeries } from '@/service/api';
import { formatCompact, useDashboard } from './composables/use-dashboard';
import DashCard from './components/dash-card.vue';
import KpiCard from './components/kpi-card.vue';
import SuccessRateCard from './components/success-rate-card.vue';
import TokenCard from './components/token-card.vue';
import TodayCard from './components/today-card.vue';
import ChannelRank from './components/channel-rank.vue';
import DailyAreaChart from './components/daily-area-chart.vue';
import DimensionSection from './modules/dimension-section.vue';
import PerformanceSection from './modules/performance-section.vue';

defineOptions({ name: 'Dashboard' });

const { routerPushByKey } = useRouterPush();
const theme = useThemeVars();

const { dash, load, requestGrowth, resolveChannelName, resolveKeyName } = useDashboard();

const dailyRows = ref<NexusAI.DailyPoint[]>([]);

/** 全程成功率（百分比·0-100）+ 全程失败数（一比一 axonhub SuccessRateCard 的 all-time 口径）。 */
const successRate = computed(() => (dash.value.successRateAllTime ?? 1) * 100);
const failedAllTime = computed(() => {
  const total = dash.value.totalRequests ?? 0;
  return Math.max(0, Math.round(total * (1 - (dash.value.successRateAllTime ?? 1))));
});

/** 模型维度展示名即模型串自身（无需查库映射）。 */
const modelName = (k?: string) => k ?? '—';

async function refresh() {
  await load();
  const { data } = await fetchDailySeries(14);
  dailyRows.value = data ?? [];
}

onMounted(refresh);
</script>

<template>
  <NSpace vertical :size="24">
    <!-- 概览 section（axonhub space-y-4：KPI 行与 Daily 行间 16·区块间 24） -->
    <div class="flex flex-col gap-16px">
      <!-- 4 KPI（总请求数 / 成功率 / 词元统计 / 今日请求）·axonhub grid gap-6 -->
      <NGrid :cols="4" :x-gap="24" :y-gap="24" responsive="screen" item-responsive>
        <NGridItem span="4 s:2 m:1">
          <KpiCard label="总请求数" :value="formatCompact(dash.totalRequests)" icon="lucide:database" :growth="requestGrowth" />
        </NGridItem>
        <NGridItem span="4 s:2 m:1">
          <SuccessRateCard label="成功率" :rate="successRate" :failed="failedAllTime" />
        </NGridItem>
        <NGridItem span="4 s:2 m:1">
          <TokenCard :tokens="dash.tokenStats ?? {}" />
        </NGridItem>
        <NGridItem span="4 s:2 m:1">
          <TodayCard
            :value="formatCompact(dash.requestStats?.requestsToday)"
            :week-value="formatCompact(dash.requestStats?.requestsThisWeek)"
            :month-value="formatCompact(dash.requestStats?.requestsThisMonth)"
          />
        </NGridItem>
      </NGrid>

      <!-- 每日概览面积图（4/7） + 渠道成功率（3/7）·axonhub grid gap-4 -->
      <NGrid :cols="7" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGridItem span="7 m:4">
          <DashCard class="h-full">
            <div class="flex flex-row items-center justify-between px-24px">
              <span class="text-16px font-semibold leading-none">每日概览</span>
            </div>
            <div class="px-24px">
              <DailyAreaChart :rows="dailyRows" />
            </div>
          </DashCard>
        </NGridItem>
        <NGridItem span="7 m:3">
          <DashCard class="h-full">
            <div class="flex flex-row items-start justify-between gap-8px px-24px">
              <div class="flex flex-col gap-4px">
                <span class="text-16px font-semibold leading-none">渠道成功率</span>
                <span class="text-14px" :style="{ color: theme.textColor3 }">不同渠道的请求成功率情况。</span>
              </div>
              <NButton text type="primary" size="small" @click="routerPushByKey('channels')">查看全部</NButton>
            </div>
            <div class="px-24px">
              <ChannelRank :rows="dash.channelSuccessRates ?? []" />
            </div>
          </DashCard>
        </NGridItem>
      </NGrid>
    </div>

    <!-- 渠道分析（折叠·请求量 + Token 双图 + 时段选择器·axonhub 四区默认全折叠） -->
    <DimensionSection dim="channel" title="渠道分析" icon="lucide:bar-chart-3" storage-key="channels" :resolve-name="resolveChannelName" />
    <!-- 模型分析（折叠） -->
    <DimensionSection dim="model" title="模型分析" icon="lucide:brain" storage-key="models" :resolve-name="modelName" />
    <!-- 上游凭证分析（折叠·nexusai 日志维度为渠道密钥 key_id，非 axonhub 的调用方 API 密钥） -->
    <DimensionSection dim="key" title="上游凭证分析" icon="lucide:key" storage-key="apiKeys" :resolve-name="resolveKeyName" />

    <!-- 性能分析（折叠·模型/渠道性能柱大图 + 最快榜） -->
    <PerformanceSection :resolve-channel-name="resolveChannelName" />
  </NSpace>
</template>
