<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, NInputNumber } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import JsonContent from './json-content.vue';

/**
 * JSON 内容面板（JsonContentPanel）：json-panel-toolbar 标题/统计 +
 * json-panel-actions（展开深度步进 + 全部展开/收起 + 复制）+ JsonContent 树查看器。
 */
defineOptions({ name: 'JsonPanel' });

const props = defineProps<{
  data: unknown;
  title?: string;
  maxHeight?: number;
}>();

/** 初始展开深度（默认全展开·此处 99=全展开） */
const expandDepth = ref(99);
const copied = ref(false);

const text = computed(() => {
  if (props.data == null) return '';
  if (typeof props.data === 'string') {
    try {
      return JSON.stringify(JSON.parse(props.data), null, 2);
    } catch {
      return props.data;
    }
  }
  return JSON.stringify(props.data, null, 2);
});

const lineCount = computed(() => (text.value ? text.value.split('\n').length : 0));
const byteLabel = computed(() => {
  const bytes = new Blob([text.value]).size;
  return bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`;
});

function expandAll() {
  expandDepth.value = 99;
}

function collapseAll() {
  expandDepth.value = 1;
}

async function copy() {
  if (!text.value) return;
  await navigator.clipboard.writeText(text.value);
  window.$message?.success('已复制');
  copied.value = true;
  setTimeout(() => {
    copied.value = false;
  }, 1500);
}
</script>

<template>
  <div class="overflow-hidden border border-gray-100 rounded-8px dark:border-gray-800">
    <!-- json-panel-toolbar（对位） -->
    <div class="flex items-center justify-between gap-8px bg-gray-50 px-10px py-4px dark:bg-gray-800/60">
      <span class="truncate text-11px text-gray-400">{{ title || 'JSON' }} · {{ lineCount }} 行 · {{ byteLabel }}</span>
      <!-- json-panel-actions -->
      <div class="flex flex-shrink-0 items-center gap-2px">
        <NInputNumber v-model:value="expandDepth" size="tiny" :min="0" :max="99" class="w-76px" title="展开深度（0=全部折叠）" />
        <NButton size="tiny" quaternary title="全部展开" @click="expandAll">
          <template #icon>
            <SvgIcon icon="carbon:expand-all" />
          </template>
        </NButton>
        <NButton size="tiny" quaternary title="全部收起（保留一层）" @click="collapseAll">
          <template #icon>
            <SvgIcon icon="carbon:collapse-all" />
          </template>
        </NButton>
        <NButton size="tiny" quaternary title="复制全部" :type="copied ? 'success' : 'default'" @click="copy">
          <template #icon>
            <SvgIcon :icon="copied ? 'carbon:checkmark' : 'carbon:copy'" />
          </template>
        </NButton>
      </div>
    </div>
    <JsonContent :data="data" :expand-depth="expandDepth" :max-height="maxHeight ?? 460" />
  </div>
</template>

<style scoped></style>
