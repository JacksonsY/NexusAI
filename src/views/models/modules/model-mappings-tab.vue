<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchUpdateModel } from '@/service/api';
import { parseAliasPatterns } from '../composables/use-model-format';

defineOptions({ name: 'ModelMappingsTab' });

/** 每模型规则上限（对齐后端 ModelAliasResolver.MAX_PATTERNS_PER_MODEL） */
const MAX_PATTERNS = 10;

interface Props {
  model: NexusAI.Model;
}

const props = defineProps<Props>();
const emit = defineEmits<{ saved: [] }>();

const patterns = ref<string[]>([]);
const dirty = ref(false);
const saving = ref(false);

watch(
  () => props.model.id,
  () => {
    patterns.value = parseAliasPatterns(props.model.aliasPatterns);
    dirty.value = false;
  },
  { immediate: true }
);

interface Validation {
  valid: boolean;
  error?: string;
}

const validations = computed<Validation[]>(() =>
  patterns.value.map(p => {
    if (!p.trim()) return { valid: true };
    try {
      // eslint-disable-next-line no-new
      new RegExp(p.trim());
      return { valid: true };
    } catch (e) {
      return { valid: false, error: e instanceof Error ? e.message : '正则非法' };
    }
  })
);

const hasErrors = computed(() => validations.value.some(v => !v.valid));

/** 命中演示：规则对自身 modelKey 的典型别名是否可解释（本地试配·展示用） */
function sampleHint(pattern: string): string {
  if (!pattern.trim()) return '';
  try {
    const re = new RegExp(`^(?:${pattern.trim()})$`);
    const samples = [`${props.model.modelKey}-latest`, `${props.model.modelKey}-20241022`, pattern.trim()];
    const hit = samples.find(s => re.test(s));
    return hit ? `如可匹配 "${hit}"` : '';
  } catch {
    return '';
  }
}

function addPattern() {
  if (patterns.value.length >= MAX_PATTERNS) return;
  patterns.value.push('');
  dirty.value = true;
}

function removePattern(index: number) {
  patterns.value.splice(index, 1);
  dirty.value = true;
}

async function save() {
  if (hasErrors.value) return;
  const cleaned = patterns.value.map(p => p.trim()).filter(Boolean);
  saving.value = true;
  const { error } = await fetchUpdateModel(props.model.id!, {
    id: props.model.id,
    aliasPatterns: cleaned.length > 0 ? JSON.stringify(cleaned) : ''
  });
  saving.value = false;
  if (!error) {
    window.$message?.success('映射规则已保存（数据面 ≤30s 生效）');
    patterns.value = cleaned;
    dirty.value = false;
    emit('saved');
  }
}
</script>

<template>
  <div class="pt-8px">
    <div class="mb-8px flex items-center justify-between gap-8px">
      <div class="flex items-baseline gap-6px">
        <h4 class="text-13px font-semibold">映射规则</h4>
        <span class="text-12px text-gray-400">支持正则表达式（完整匹配·{{ patterns.length }}/{{ MAX_PATTERNS }}）</span>
      </div>
      <NSpace :size="4">
        <NButton
          v-if="dirty"
          quaternary
          circle
          size="small"
          title="保存"
          :loading="saving"
          :disabled="hasErrors"
          @click="save"
        >
          <SvgIcon icon="lucide:save" />
        </NButton>
        <NButton
          quaternary
          circle
          size="small"
          title="添加规则"
          :disabled="patterns.length >= MAX_PATTERNS"
          @click="addPattern"
        >
          <SvgIcon icon="ic:round-plus" />
        </NButton>
      </NSpace>
    </div>

    <NText depth="3" class="mb-8px block text-12px">
      请求模型名命中任一规则将归一到
      <code class="font-mono">{{ model.modelKey }}</code>
      （计费费率 / 路由 / 日志统计随之对齐）；典型场景如
      <code class="font-mono">claude-3-5-sonnet-.*</code>
      吸收带日期后缀的别名。
    </NText>

    <div v-if="patterns.length > 0" class="space-y-6px">
      <div v-for="(_, index) in patterns" :key="index" class="flex items-start gap-8px">
        <div class="flex-1 min-w-0">
          <NInput
            v-model:value="patterns[index]"
            size="small"
            placeholder="例如: claude-haiku-.*"
            class="font-mono"
            :status="patterns[index] && !validations[index].valid ? 'error' : undefined"
            @input="dirty = true"
          />
          <div v-if="patterns[index] && !validations[index].valid" class="mt-2px flex items-center gap-3px text-11px text-error">
            <SvgIcon icon="lucide:alert-circle" />
            <span>{{ validations[index].error }}</span>
          </div>
          <div v-else-if="sampleHint(patterns[index])" class="mt-2px text-11px text-gray-400">
            {{ sampleHint(patterns[index]) }}
          </div>
        </div>
        <NButton quaternary circle size="small" title="删除" :disabled="saving" @click="removePattern(index)">
          <SvgIcon icon="lucide:trash-2" />
        </NButton>
      </div>
    </div>

    <NEmpty v-else description="暂无映射规则" class="py-32px">
      <template #icon>
        <SvgIcon icon="lucide:git-merge" class="text-32px text-gray-300" />
      </template>
      <template #extra>
        <NButton size="small" @click="addPattern">添加第一条规则</NButton>
      </template>
    </NEmpty>
  </div>
</template>
