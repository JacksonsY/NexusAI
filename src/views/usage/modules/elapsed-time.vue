<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';

/**
 * 进行中请求活计时（一比一 Aether ElapsedTimeText）：pending/streaming 状态下
 * requestAnimationFrame 持续走秒；终态显示最终耗时；卸载/状态变化时清理。
 */
defineOptions({ name: 'ElapsedTime' });

const props = withDefaults(
  defineProps<{
    /** 开始时间（epoch ms） */
    startedAt?: number | null;
    /** pending | streaming = 活跃走秒 */
    status?: string | null;
    /** 终态总耗时 ms（非活跃时显示） */
    durationMs?: number | null;
    precision?: number;
  }>(),
  { startedAt: null, status: null, durationMs: null, precision: 2 }
);

const now = ref(Date.now());
let rafId: number | null = null;

const isLive = computed(() => props.status === 'pending' || props.status === 'streaming');

function tick() {
  now.value = Date.now();
  rafId = requestAnimationFrame(tick);
}

function stop() {
  if (rafId != null) {
    cancelAnimationFrame(rafId);
    rafId = null;
  }
}

watch(
  isLive,
  live => {
    stop();
    if (live) tick();
  },
  { immediate: true }
);

onBeforeUnmount(stop);

const text = computed(() => {
  if (isLive.value && props.startedAt) {
    return `${((now.value - props.startedAt) / 1000).toFixed(props.precision)}s`;
  }
  if (props.durationMs != null && props.durationMs > 0) {
    return `${(props.durationMs / 1000).toFixed(props.precision)}s`;
  }
  return '-';
});
</script>

<template>
  <span class="tabular-nums">{{ text }}</span>
</template>

<style scoped></style>
