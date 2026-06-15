<script setup lang="ts">
import { computed } from 'vue';
import { NTag, NTooltip } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fmtMs, num } from '../composables/format';

/**
 * 请求链路追踪（一比一 Aether HorizontalRequestTimeline·按 nexusai failover 链映射）：
 * 横向节点流——接入 → 尝试1..N（渠道/错误码·失败红/转移黄/成功绿）→ 首字 → 完成；
 * 节点下标耗时，hover 看错误原因。
 */
defineOptions({ name: 'RequestTimeline' });

const props = defineProps<{
  request: NexusAI.RequestLog;
  chain: NexusAI.FailoverEvent[];
  channelLabel: (id?: number | string | null) => string;
}>();

interface Node {
  key: string;
  label: string;
  sub?: string;
  state: 'ok' | 'fail' | 'transfer' | 'info';
  tooltip?: string;
}

const nodes = computed<Node[]>(() => {
  const out: Node[] = [{ key: 'start', label: '接入', sub: props.request.apiFormat || undefined, state: 'info' }];
  for (const e of props.chain) {
    const failed = e.outcome !== 'success';
    out.push({
      key: `attempt-${e.attempt}`,
      label: `尝试 ${e.attempt} · ${props.channelLabel(e.fromChannelId as number)}`,
      sub: e.errorCode || undefined,
      state: failed ? 'fail' : 'ok',
      tooltip: e.reason || undefined
    });
  }
  // 最终落点渠道（链尾为失败转移时补最终渠道节点）
  if (props.chain.length && props.request.channelId != null) {
    const last = props.chain[props.chain.length - 1];
    if (String(last.fromChannelId) !== String(props.request.channelId)) {
      out.push({
        key: 'final-channel',
        label: `转移 · ${props.channelLabel(props.request.channelId as number)}`,
        state: 'transfer'
      });
    }
  }
  if (!props.chain.length) {
    out.push({
      key: 'channel',
      label: props.channelLabel(props.request.channelId as number),
      sub: '直连命中',
      state: 'ok'
    });
  }
  if (num(props.request.ttftMs) > 0) {
    out.push({ key: 'ttft', label: '首字', sub: fmtMs(props.request.ttftMs), state: 'info' });
  }
  const success = num(props.request.httpStatus) > 0 && num(props.request.httpStatus) < 400;
  out.push({
    key: 'done',
    label: success ? '完成' : `失败 HTTP ${props.request.httpStatus ?? '-'}`,
    sub: fmtMs(props.request.durationMs),
    state: success ? 'ok' : 'fail'
  });
  return out;
});

const STATE_STYLE: Record<Node['state'], { dot: string; text: string; icon: string }> = {
  ok: { dot: 'bg-green-500', text: 'text-green-600 dark:text-green-400', icon: 'carbon:checkmark-filled' },
  fail: { dot: 'bg-red-500', text: 'text-red-500', icon: 'carbon:close-filled' },
  transfer: { dot: 'bg-amber-500', text: 'text-amber-500', icon: 'carbon:arrow-shift-down' },
  info: { dot: 'bg-blue-500', text: 'text-gray-600 dark:text-gray-300', icon: 'carbon:circle-filled' }
};
</script>

<template>
  <div class="overflow-x-auto py-4px">
    <div class="flex items-start gap-0 min-w-max">
      <template v-for="(n, idx) in nodes" :key="n.key">
        <!-- 连接线 -->
        <div v-if="idx > 0" class="mt-10px h-2px w-28px flex-shrink-0 bg-gray-200 dark:bg-gray-700" />
        <!-- 节点 -->
        <NTooltip :disabled="!n.tooltip">
          <template #trigger>
            <div class="flex flex-col items-center px-4px" :class="n.tooltip ? 'cursor-help' : ''">
              <SvgIcon :icon="STATE_STYLE[n.state].icon" class="text-18px" :class="STATE_STYLE[n.state].text" />
              <span class="mt-4px whitespace-nowrap text-12px font-medium" :class="STATE_STYLE[n.state].text">
                {{ n.label }}
              </span>
              <NTag v-if="n.sub" size="tiny" :bordered="false" class="mt-2px font-mono">{{ n.sub }}</NTag>
            </div>
          </template>
          {{ n.tooltip }}
        </NTooltip>
      </template>
    </div>
  </div>
</template>

<style scoped></style>
