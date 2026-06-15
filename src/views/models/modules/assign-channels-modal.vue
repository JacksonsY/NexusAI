<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchAssignModelChannels, fetchChannels, fetchModelChannels } from '@/service/api';

defineOptions({ name: 'AssignChannelsModal' });

interface Props {
  modelKey: string;
}

const props = defineProps<Props>();
const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ success: [] }>();

const loading = ref(false);
const submitting = ref(false);
const channels = ref<NexusAI.Channel[]>([]);
const search = ref('');
const selected = ref<Set<number | string>>(new Set());
const initial = ref<Set<number | string>>(new Set());

async function load() {
  loading.value = true;
  const [{ data: all }, { data: linked }] = await Promise.all([
    fetchChannels(),
    fetchModelChannels(props.modelKey)
  ]);
  channels.value = all ?? [];
  const ids = new Set((linked ?? []).map(l => String(l.channelId)));
  selected.value = new Set(ids);
  initial.value = new Set(ids);
  loading.value = false;
}

watch(show, open => {
  if (open && props.modelKey) {
    search.value = '';
    load();
  }
});

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase();
  if (!q) return channels.value;
  return channels.value.filter(c => `${c.name ?? ''} ${c.baseUrl ?? ''}`.toLowerCase().includes(q));
});

const pendingChanges = computed(() => {
  let count = 0;
  selected.value.forEach(id => {
    if (!initial.value.has(id)) count += 1;
  });
  initial.value.forEach(id => {
    if (!selected.value.has(id)) count += 1;
  });
  return count;
});

const allSelected = computed(
  () => filtered.value.length > 0 && filtered.value.every(c => selected.value.has(String(c.id)))
);

function toggleAll() {
  const set = new Set(selected.value);
  if (allSelected.value) {
    filtered.value.forEach(c => set.delete(String(c.id)));
  } else {
    filtered.value.forEach(c => set.add(String(c.id)));
  }
  selected.value = set;
}

function toggle(id: number | string) {
  const key = String(id);
  const set = new Set(selected.value);
  if (set.has(key)) set.delete(key);
  else set.add(key);
  selected.value = set;
}

async function save() {
  submitting.value = true;
  const { data, error } = await fetchAssignModelChannels(props.modelKey, [...selected.value]);
  submitting.value = false;
  if (!error) {
    window.$message?.success(`已保存：新增 ${data?.added ?? 0}，移除 ${data?.removed ?? 0}`);
    emit('success');
    show.value = false;
  }
}
</script>

<template>
  <NModal v-model:show="show" preset="card" :title="`批量管理提供商 - ${props.modelKey}`" class="w-640px max-w-[94vw]">
    <template #header-extra>
      <span class="text-12px text-gray-400">选中的提供商将被关联到模型，取消选中将移除关联</span>
    </template>

    <div class="space-y-12px">
      <NInput v-model:value="search" size="small" placeholder="搜索提供商..." clearable>
        <template #prefix>
          <SvgIcon icon="lucide:search" class="text-gray-400" />
        </template>
      </NInput>

      <div class="overflow-hidden border border-gray-200 rounded-8px dark:border-gray-700">
        <div class="max-h-360px overflow-y-auto">
          <div v-if="loading" class="flex-center py-48px">
            <NSpin size="small" />
          </div>
          <template v-else-if="filtered.length > 0">
            <div class="sticky top-0 z-10 flex items-center justify-between bg-gray-50 px-12px py-6px dark:bg-gray-800">
              <span class="text-12px font-medium">提供商 ({{ filtered.length }})</span>
              <NButton text size="tiny" type="primary" @click="toggleAll">
                {{ allSelected ? '取消全选' : '全选' }}
              </NButton>
            </div>
            <div class="p-6px space-y-2px">
              <div
                v-for="ch in filtered"
                :key="String(ch.id)"
                class="flex cursor-pointer items-center gap-8px rounded-6px px-8px py-6px hover:bg-gray-50 dark:hover:bg-gray-800"
                @click="toggle(ch.id!)"
              >
                <NCheckbox :checked="selected.has(String(ch.id))" @click.stop="toggle(ch.id!)" />
                <div class="min-w-0 flex-1">
                  <div class="truncate text-13px font-medium">{{ ch.name }}</div>
                  <div class="truncate text-11px text-gray-400">{{ ch.baseUrl }}</div>
                </div>
                <NTag v-if="ch.protocol" size="tiny" :bordered="false" class="uppercase">{{ ch.protocol }}</NTag>
                <NTag size="tiny" :type="ch.status === 1 ? 'success' : 'default'" :bordered="ch.status === 1">
                  {{ ch.status === 1 ? '活跃' : '停用' }}
                </NTag>
              </div>
            </div>
          </template>
          <NEmpty v-else :description="search ? '无匹配结果' : '暂无可用提供商'" class="py-32px" />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <span class="text-12px text-gray-400">{{ pendingChanges > 0 ? `${pendingChanges} 项更改待保存` : '' }}</span>
        <NSpace :size="8">
          <NButton size="small" type="primary" :disabled="pendingChanges === 0" :loading="submitting" @click="save">
            {{ submitting ? '保存中...' : '保存' }}
          </NButton>
          <NButton size="small" @click="show = false">关闭</NButton>
        </NSpace>
      </div>
    </template>
  </NModal>
</template>
