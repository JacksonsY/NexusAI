import { computed, ref, watch } from 'vue';
import { useIntervalFn } from '@vueuse/core';

/**
 * 自动刷新组合式（封装 VueUse useIntervalFn）：单一 selected 间隔模型（0=手动/关闭），
 * 配合一个 NSelect 即可。运维台多维筛选+自动刷新（缺口分析 G5 6/6 共识）的前置件。
 *
 * @param fn        刷新回调（每次 tick 调用）
 * @param defaultMs 默认间隔毫秒（0=关闭）
 */
export function useAutoRefresh(fn: () => void | Promise<void>, defaultMs = 0) {
  /** 选中的刷新间隔（ms·0=手动） */
  const selected = ref<number>(defaultMs);
  const presets = [
    { label: '手动', value: 0 },
    { label: '15s', value: 15000 },
    { label: '30s', value: 30000 },
    { label: '60s', value: 60000 }
  ];
  const ms = computed(() => Math.max(1000, selected.value || 1000));
  const { pause, resume } = useIntervalFn(fn, ms, { immediate: false });

  watch(
    selected,
    v => {
      pause();
      if (v > 0) resume();
    },
    { immediate: true }
  );

  return { selected, presets };
}
