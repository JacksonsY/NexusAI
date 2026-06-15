<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchChannelUpstreamModels, fetchRegisterUpstreamModels } from '@/service/api';

defineOptions({ name: 'UpstreamModelsModal' });

/** 从上游拉取模型弹窗（new-api FetchUpstreamModels）：GET {baseUrl}/models → 勾选未注册的批量建 ability。 */
const props = defineProps<{
  channelId: number | null;
  channelName?: string;
}>();

const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ success: [] }>();

const loading = ref(false);
const submitting = ref(false);
const errorMsg = ref('');
const models = ref<string[]>([]);
const registered = ref<Set<string>>(new Set());
const selected = ref<Set<string>>(new Set());
const keyword = ref('');

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return kw ? models.value.filter(m => m.toLowerCase().includes(kw)) : models.value;
});

const selectableFiltered = computed(() => filtered.value.filter(m => !registered.value.has(m)));

async function loadModels() {
  if (!props.channelId) return;
  loading.value = true;
  errorMsg.value = '';
  models.value = [];
  selected.value = new Set();
  keyword.value = '';
  const { data, error } = await fetchChannelUpstreamModels(props.channelId);
  loading.value = false;
  if (error || !data?.ok) {
    errorMsg.value = data?.message ?? '拉取失败';
    return;
  }
  models.value = data.models ?? [];
  registered.value = new Set(data.registered ?? []);
}

watch(show, open => {
  if (open) loadModels();
});

function toggle(m: string) {
  if (registered.value.has(m)) return;
  const set = new Set(selected.value);
  if (set.has(m)) set.delete(m);
  else set.add(m);
  selected.value = set;
}

function selectAllVisible() {
  selected.value = new Set([...selected.value, ...selectableFiltered.value]);
}

async function submit() {
  if (!props.channelId || selected.value.size === 0) return;
  submitting.value = true;
  const { data, error } = await fetchRegisterUpstreamModels(props.channelId, [...selected.value]);
  submitting.value = false;
  if (!error) {
    window.$message?.success(`已注册 ${data ?? selected.value.size} 个模型到该渠道`);
    emit('success');
    show.value = false;
  }
}
</script>

<template>
  <NModal v-model:show="show" preset="card" :title="`从上游拉取模型${channelName ? ` · ${channelName}` : ''}`" class="w-640px max-w-[94vw]">
    <template #header-extra>
      <span class="text-12px text-gray-400">GET {baseUrl}/models · 勾选未注册的批量建路由（default 组）</span>
    </template>

    <div v-if="loading" class="flex-center py-64px">
      <NSpin size="small" />
    </div>

    <NAlert v-else-if="errorMsg" type="error" :show-icon="true">{{ errorMsg }}</NAlert>

    <div v-else class="space-y-10px">
      <div class="flex items-center gap-8px">
        <NInput v-model:value="keyword" size="small" placeholder="过滤模型名..." clearable class="flex-1" />
        <NButton size="small" :disabled="selectableFiltered.length === 0" @click="selectAllVisible">
          全选未注册（{{ selectableFiltered.length }}）
        </NButton>
      </div>
      <NEmpty v-if="filtered.length === 0" description="上游未返回模型" size="small" class="py-16px" />
      <div v-else class="max-h-380px overflow-y-auto border border-gray-200 rounded-8px p-6px space-y-2px dark:border-gray-700">
        <div
          v-for="m in filtered"
          :key="m"
          class="flex items-center gap-8px rounded-6px px-8px py-4px"
          :class="registered.has(m) ? 'op-45' : 'cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800'"
          @click="toggle(m)"
        >
          <NCheckbox :checked="registered.has(m) || selected.has(m)" :disabled="registered.has(m)" @click.stop="toggle(m)" />
          <span class="min-w-0 flex-1 truncate text-12px font-mono">{{ m }}</span>
          <NTag v-if="registered.has(m)" size="tiny" :bordered="false">已注册</NTag>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <span class="text-12px text-gray-400">
          共 {{ models.length }} 个模型 · 已注册 {{ registered.size }} · 已勾选 {{ selected.size }}
        </span>
        <NSpace :size="8">
          <NButton size="small" type="primary" :disabled="selected.size === 0" :loading="submitting" @click="submit">
            注册所选
          </NButton>
          <NButton size="small" @click="show = false">关闭</NButton>
        </NSpace>
      </div>
    </template>
  </NModal>
</template>
