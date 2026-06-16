<script setup lang="ts">
import { computed } from 'vue';
import { NTag } from 'naive-ui';

/**
 * 对话视图（ConversationView 清洁室实现）：把 LLM 请求/响应体渲染成
 * 角色气泡——system/user/assistant/tool 各色；content 兼容 OpenAI 字符串与
 * Anthropic/OpenAI 分块数组（text/image/tool_use/tool_result 块），工具与图片显示占位标签。
 */
defineOptions({ name: 'ConversationView' });

const props = defineProps<{
  /** 原始正文（JSON 字符串） */
  body: string;
  kind: 'request' | 'response';
  maxHeight?: number;
}>();

interface Bubble {
  role: string;
  /** 文本段落（多块合并） */
  text: string;
  /** 非文本块占位标签（[图片] / [工具调用 xxx] / [工具结果]） */
  chips: string[];
}

/** content 字段归一：字符串 | 分块数组 → 文本 + 占位标签 */
function normalizeContent(content: unknown): { text: string; chips: string[] } {
  if (content == null) return { text: '', chips: [] };
  if (typeof content === 'string') return { text: content, chips: [] };
  if (Array.isArray(content)) {
    const texts: string[] = [];
    const chips: string[] = [];
    for (const block of content) {
      if (block == null || typeof block !== 'object') continue;
      const b = block as Record<string, unknown>;
      const type = String(b.type ?? '');
      if (type === 'text' && typeof b.text === 'string') texts.push(b.text);
      else if (type === 'input_text' && typeof b.text === 'string') texts.push(b.text);
      else if (type === 'output_text' && typeof b.text === 'string') texts.push(b.text);
      else if (type === 'image_url' || type === 'image' || type === 'input_image') chips.push('[图片]');
      else if (type === 'tool_use') chips.push(`[工具调用 ${String(b.name ?? '')}]`.trim());
      else if (type === 'tool_result') chips.push('[工具结果]');
      else if (type === 'thinking') chips.push('[思考块]');
      else if (type) chips.push(`[${type}]`);
    }
    return { text: texts.join('\n'), chips };
  }
  return { text: '', chips: [] };
}

const bubbles = computed<Bubble[]>(() => {
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(props.body);
  } catch {
    return [];
  }
  const out: Bubble[] = [];
  if (props.kind === 'request') {
    // Anthropic 顶层 system
    if (typeof parsed.system === 'string' && parsed.system) {
      out.push({ role: 'system', text: parsed.system, chips: [] });
    } else if (Array.isArray(parsed.system)) {
      const n = normalizeContent(parsed.system);
      if (n.text || n.chips.length) out.push({ role: 'system', ...n });
    }
    const messages = Array.isArray(parsed.messages) ? parsed.messages : [];
    for (const m of messages) {
      if (m == null || typeof m !== 'object') continue;
      const msg = m as Record<string, unknown>;
      const n = normalizeContent(msg.content);
      // OpenAI assistant tool_calls
      if (Array.isArray(msg.tool_calls)) {
        for (const tc of msg.tool_calls as Record<string, unknown>[]) {
          const fn = (tc?.function ?? {}) as Record<string, unknown>;
          n.chips.push(`[工具调用 ${String(fn.name ?? '')}]`.trim());
        }
      }
      out.push({ role: String(msg.role ?? 'user'), ...n });
    }
    if (Array.isArray(parsed.tools) && parsed.tools.length) {
      out.push({ role: 'meta', text: '', chips: [`[声明 ${parsed.tools.length} 个工具]`] });
    }
    return out;
  }
  // 响应：OpenAI choices / Anthropic content
  if (Array.isArray(parsed.choices)) {
    for (const c of parsed.choices as Record<string, unknown>[]) {
      const msg = (c?.message ?? {}) as Record<string, unknown>;
      const n = normalizeContent(msg.content);
      if (Array.isArray(msg.tool_calls)) {
        for (const tc of msg.tool_calls as Record<string, unknown>[]) {
          const fn = (tc?.function ?? {}) as Record<string, unknown>;
          n.chips.push(`[工具调用 ${String(fn.name ?? '')}]`.trim());
        }
      }
      out.push({ role: String(msg.role ?? 'assistant'), ...n });
    }
    return out;
  }
  if (Array.isArray(parsed.content)) {
    const n = normalizeContent(parsed.content);
    out.push({ role: String(parsed.role ?? 'assistant'), ...n });
    return out;
  }
  return out;
});

const ROLE_STYLE: Record<string, { tag: 'default' | 'info' | 'success' | 'warning'; label: string; align: string }> = {
  system: { tag: 'default', label: 'system', align: '' },
  user: { tag: 'info', label: 'user', align: '' },
  assistant: { tag: 'success', label: 'assistant', align: '' },
  tool: { tag: 'warning', label: 'tool', align: '' },
  meta: { tag: 'default', label: 'meta', align: '' }
};

/** 是否能渲染为对话（index 工具栏切换可用性判定共用） */
defineExpose({});
</script>

<template>
  <div v-if="bubbles.length" class="space-y-10px overflow-auto py-8px pr-4px" :style="{ maxHeight: `${maxHeight ?? 460}px` }">
    <div v-for="(b, i) in bubbles" :key="i" class="flex flex-col gap-4px">
      <NTag size="tiny" :bordered="false" :type="(ROLE_STYLE[b.role] ?? ROLE_STYLE.user).tag" class="self-start">
        {{ (ROLE_STYLE[b.role] ?? { label: b.role }).label }}
      </NTag>
      <div
        class="whitespace-pre-wrap break-words border border-gray-100 rounded-8px px-12px py-8px text-13px leading-20px dark:border-gray-800"
        :class="b.role === 'assistant' ? 'bg-green-50/60 dark:bg-green-900/10' : b.role === 'user' ? 'bg-blue-50/60 dark:bg-blue-900/10' : 'bg-gray-50 dark:bg-gray-800/50'"
      >
        <template v-if="b.text">{{ b.text }}</template>
        <span v-else-if="!b.chips.length" class="text-gray-400 italic">（空内容）</span>
        <div v-if="b.chips.length" class="mt-4px flex flex-wrap gap-4px">
          <NTag v-for="(c, j) in b.chips" :key="j" size="tiny" :bordered="false">{{ c }}</NTag>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="py-32px text-center text-13px text-gray-400">该内容无法解析为对话格式</div>
</template>

<style scoped></style>
