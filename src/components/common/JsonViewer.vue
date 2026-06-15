<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { EditorView, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language';
import { oneDark } from '@codemirror/theme-one-dark';
import { json } from '@codemirror/lang-json';
import { useThemeStore } from '@/store/modules/theme';

defineOptions({ name: 'JsonViewer' });

const props = defineProps<{
  data: any;
  maxHeight?: number;
  collapsed?: boolean;
}>();

const themeStore = useThemeStore();
const editorRef = ref<HTMLDivElement>();
const view = shallowRef<EditorView>();

/** 格式化 JSON 显示 */
const formattedJson = computed(() => {
  try {
    if (props.data === null || props.data === undefined) return '';
    if (typeof props.data === 'string') {
      // 尝试解析为 JSON 再格式化
      try {
        const parsed = JSON.parse(props.data);
        return JSON.stringify(parsed, null, 2);
      } catch {
        return props.data;
      }
    }
    return JSON.stringify(props.data, null, 2);
  } catch {
    return String(props.data);
  }
});

/** 计算实际高度 */
const editorHeight = computed(() => {
  if (props.collapsed) return '40px';
  return props.maxHeight ? `${props.maxHeight}px` : '200px';
});

function createState(doc: string) {
  const isDark = themeStore.darkMode;

  return EditorState.create({
    doc,
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      json(),
      EditorView.lineWrapping,
      EditorState.readOnly.of(true),
      ...(isDark ? [oneDark] : []),
      EditorView.theme({
        '&': {
          height: editorHeight.value,
          minHeight: 0,
          fontSize: '12px',
          backgroundColor: 'transparent'
        },
        '.cm-scroller': {
          overflow: 'auto',
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace'
        },
        '.cm-gutters': {
          minWidth: '30px',
          backgroundColor: 'transparent',
          borderRight: 'none'
        },
        '.cm-content': {
          padding: '8px 0'
        }
      })
    ]
  });
}

onMounted(() => {
  if (!editorRef.value) return;

  view.value = new EditorView({
    state: createState(formattedJson.value),
    parent: editorRef.value
  });
});

watch(
  () => formattedJson.value,
  newVal => {
    if (!view.value) return;
    const current = view.value.state.doc.toString();
    if (current !== newVal) {
      view.value.dispatch({
        changes: { from: 0, to: current.length, insert: newVal }
      });
    }
  }
);

watch(
  () => themeStore.darkMode,
  () => {
    if (!view.value) return;
    const doc = view.value.state.doc.toString();
    view.value.setState(createState(doc));
  }
);

watch(
  () => editorHeight.value,
  () => {
    if (!view.value) return;
    const doc = view.value.state.doc.toString();
    view.value.setState(createState(doc));
  }
);

onBeforeUnmount(() => {
  view.value?.destroy();
});
</script>

<template>
  <div
    ref="editorRef"
    class="json-viewer rounded-4px border border-[var(--n-border-color)] bg-[var(--n-code-color)]"
    :style="{ height: editorHeight, maxHeight: editorHeight }"
  />
</template>

<style scoped>
.json-viewer {
  overflow: hidden;
}

.json-viewer :deep(.cm-editor) {
  height: 100%;
}
.json-viewer :deep(.cm-editor.cm-focused) {
  outline: none;
}
</style>
