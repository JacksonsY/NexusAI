<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';

/**
 * JSON 树查看器（JsonContent 清洁室实现）：
 * token 着色 + 行号 + 对象/数组逐节点折叠（chevron·折叠显示 "N keys / N items" 统计）
 * + expandDepth 受控初始展开深度；超大 JSON（节点过多）退化为纯文本防卡顿。
 */
defineOptions({ name: 'JsonContent' });

const props = defineProps<{
  data: unknown;
  /** 初始展开深度（0=全折叠根节点·变化时重置手动折叠态） */
  expandDepth: number;
  maxHeight?: number;
}>();

const MAX_NODES = 4000;

interface FlatLine {
  /** 节点路径（折叠态键） */
  path: string;
  depth: number;
  /** 行首 key（对象成员）或 undefined（数组元素/根） */
  key?: string;
  /** 标量值的渲染文本（容器行无） */
  valueText?: string;
  valueType?: 'string' | 'number' | 'boolean' | 'null';
  /** 容器开括号行：'{' / '[' */
  open?: '{' | '[';
  /** 容器收括号行 */
  close?: '}' | ']';
  /** 容器子元素统计（开括号行用·折叠时展示） */
  childCount?: number;
  childLabel?: string;
  /** 行尾逗号 */
  comma?: boolean;
}

const parsed = computed<{ ok: boolean; value: unknown; raw: string }>(() => {
  const d = props.data;
  if (typeof d === 'string') {
    try {
      return { ok: true, value: JSON.parse(d), raw: d };
    } catch {
      return { ok: false, value: null, raw: d };
    }
  }
  return { ok: true, value: d, raw: '' };
});

/** 手动折叠态（路径集合·expandDepth 重置） */
const collapsed = ref(new Set<string>());

function defaultCollapsed(value: unknown): Set<string> {
  const set = new Set<string>();
  let count = 0;
  const walk = (v: unknown, path: string, depth: number) => {
    if (count > MAX_NODES) return;
    if (v !== null && typeof v === 'object') {
      count += 1;
      if (depth >= props.expandDepth) set.add(path);
      const entries = Array.isArray(v) ? v.map((x, i) => [String(i), x] as const) : Object.entries(v);
      for (const [k, child] of entries) walk(child, `${path}.${k}`, depth + 1);
    } else {
      count += 1;
    }
  };
  walk(value, '$', 0);
  return set;
}

watch(
  [() => props.expandDepth, () => parsed.value.value],
  () => {
    collapsed.value = defaultCollapsed(parsed.value.value);
  },
  { immediate: true }
);

function toggle(path: string) {
  const next = new Set(collapsed.value);
  if (next.has(path)) next.delete(path);
  else next.add(path);
  collapsed.value = next;
}

/** 节点总数（超限退化纯文本） */
const nodeCount = computed(() => {
  let count = 0;
  const walk = (v: unknown) => {
    count += 1;
    if (count > MAX_NODES) return;
    if (v !== null && typeof v === 'object') {
      for (const child of Array.isArray(v) ? v : Object.values(v)) walk(child);
    }
  };
  walk(parsed.value.value);
  return count;
});

const oversized = computed(() => nodeCount.value > MAX_NODES);

/** 拍平为可见行（折叠节点只出开括号行） */
const lines = computed<FlatLine[]>(() => {
  if (!parsed.value.ok || oversized.value) return [];
  const out: FlatLine[] = [];
  const emit = (v: unknown, path: string, depth: number, key: string | undefined, comma: boolean) => {
    if (v !== null && typeof v === 'object') {
      const isArr = Array.isArray(v);
      const entries = isArr ? v.map((x, i) => [String(i), x] as const) : Object.entries(v);
      const fold = collapsed.value.has(path);
      out.push({
        path,
        depth,
        key,
        open: isArr ? '[' : '{',
        childCount: entries.length,
        childLabel: isArr ? `${entries.length} items` : `${entries.length} keys`,
        comma: fold ? comma : false
      });
      if (!fold) {
        entries.forEach(([k, child], i) => {
          emit(child, `${path}.${k}`, depth + 1, isArr ? undefined : k, i < entries.length - 1);
        });
        out.push({ path: `${path}#close`, depth, close: isArr ? ']' : '}', comma });
      }
      return;
    }
    let valueText: string;
    let valueType: FlatLine['valueType'];
    if (typeof v === 'string') {
      valueText = JSON.stringify(v);
      valueType = 'string';
    } else if (typeof v === 'number') {
      valueText = String(v);
      valueType = 'number';
    } else if (typeof v === 'boolean') {
      valueText = String(v);
      valueType = 'boolean';
    } else {
      valueText = 'null';
      valueType = 'null';
    }
    out.push({ path, depth, key, valueText, valueType, comma });
  };
  emit(parsed.value.value, '$', 0, undefined, false);
  return out;
});
</script>

<template>
  <!-- 非 JSON / 超大：原文退化 -->
  <pre
    v-if="!parsed.ok || oversized"
    class="m-0 overflow-auto whitespace-pre-wrap break-all p-12px text-12px font-mono leading-20px"
    :style="{ maxHeight: `${maxHeight ?? 460}px` }"
  ><span v-if="oversized" class="mb-4px block text-11px text-amber-500">内容过大（&gt;{{ MAX_NODES }} 节点），已退化为纯文本展示</span>{{ parsed.ok ? JSON.stringify(parsed.value, null, 2) : parsed.raw }}</pre>

  <div
    v-else
    class="json-tree overflow-auto py-6px text-12px font-mono leading-20px"
    :style="{ maxHeight: `${maxHeight ?? 460}px` }"
  >
    <div v-for="line in lines" :key="line.path + (line.close ?? '')" class="json-line flex items-start hover:bg-gray-50 dark:hover:bg-gray-800/60">
      <!-- 行号（CSS counter·随可见行递增） -->
      <span class="json-lineno select-none pr-10px text-right text-gray-300 dark:text-gray-600" />
      <!-- 缩进 + 折叠柄 -->
      <span class="whitespace-pre">{{ '  '.repeat(line.depth) }}</span>
      <span
        v-if="line.open"
        class="w-14px flex cursor-pointer select-none items-center justify-center text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
        @click="toggle(line.path)"
      >
        <SvgIcon :icon="collapsed.has(line.path) ? 'carbon:chevron-right' : 'carbon:chevron-down'" class="text-12px" />
      </span>
      <span v-else class="w-14px" />
      <!-- 内容 -->
      <span class="min-w-0 flex-1 break-all">
        <template v-if="line.close">
          <span class="json-bracket">{{ line.close }}</span><span v-if="line.comma" class="json-bracket">,</span>
        </template>
        <template v-else>
          <span v-if="line.key !== undefined" class="json-key">"{{ line.key }}"</span>
          <span v-if="line.key !== undefined" class="json-bracket">: </span>
          <template v-if="line.open">
            <span class="json-bracket">{{ line.open }}</span>
            <template v-if="collapsed.has(line.path)">
              <span class="cursor-pointer select-none text-gray-400 italic" @click="toggle(line.path)">
                … {{ line.childLabel }} …
              </span>
              <span class="json-bracket">{{ line.open === '{' ? '}' : ']' }}</span><span v-if="line.comma" class="json-bracket">,</span>
            </template>
          </template>
          <template v-else>
            <span :class="`json-${line.valueType}`">{{ line.valueText }}</span><span v-if="line.comma" class="json-bracket">,</span>
          </template>
        </template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.json-tree {
  counter-reset: jsonline;
}

.json-line {
  padding: 0 8px 0 0;
}

.json-line .json-lineno::before {
  counter-increment: jsonline;
  content: counter(jsonline);
  display: inline-block;
  min-width: 34px;
}

.json-key {
  color: #0451a5;
}

.json-string {
  color: #a31515;
}

.json-number {
  color: #098658;
}

.json-boolean,
.json-null {
  color: #0000ff;
}

.json-bracket {
  color: inherit;
  opacity: 0.75;
}

:global(.dark) .json-key {
  color: #9cdcfe;
}

:global(.dark) .json-string {
  color: #ce9178;
}

:global(.dark) .json-number {
  color: #b5cea8;
}

:global(.dark) .json-boolean,
:global(.dark) .json-null {
  color: #569cd6;
}
</style>
