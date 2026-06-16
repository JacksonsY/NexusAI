<!-- 渠道成功率列表（ChannelSuccessRate：Activity 方块图标 + 渠道名 + ✓✗ 计数 + 成功率） -->
<script setup lang="ts">
import { useThemeVars } from 'naive-ui';
import { formatCompact } from '../composables/use-dashboard';

defineOptions({ name: 'ChannelRank' });

interface Props {
  /** dashboard.channelSuccessRates（近7天·已按总量降序 Top N） */
  rows: NexusAI.ChannelSuccessRate[];
}

defineProps<Props>();

const theme = useThemeVars();
</script>

<template>
  <NEmpty v-if="!rows.length" description="暂无渠道数据" class="py-8" />
  <div v-else class="flex flex-col gap-8">
    <div v-for="c in rows" :key="c.channelId" class="flex items-center">
      <div
        class="h-9 w-9 flex shrink-0 items-center justify-center rounded-md"
        :style="{ background: `${theme.primaryColor}1a`, color: theme.primaryColor }"
      >
        <SvgIcon icon="lucide:activity" class="text-20px" />
      </div>
      <div class="ml-4 min-w-0 flex-1 space-y-1">
        <p class="truncate text-14px font-medium leading-none">{{ c.channelName || '-' }}</p>
        <div class="flex gap-3 text-14px" :style="{ color: theme.textColor3 }">
          <span class="flex items-center gap-1">
            <SvgIcon icon="lucide:check-circle-2" class="text-12px text-#22c55e" />
            {{ formatCompact(c.successCount) }}
          </span>
          <span class="flex items-center gap-1">
            <SvgIcon icon="lucide:x-circle" class="text-12px text-#ef4444" />
            {{ formatCompact(c.failedCount) }}
          </span>
        </div>
      </div>
      <span class="ml-auto pl-2 font-medium">{{ (c.successRate ?? 0).toFixed(1) }}%</span>
    </div>
  </div>
</template>
