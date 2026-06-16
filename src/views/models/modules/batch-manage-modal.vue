<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchModels } from '@/service/api';
import { num, priceLine } from '../composables/use-model-format';

defineOptions({ name: 'BatchManageModal' });

const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ confirm: [ids: Array<number | string>] }>();

const loading = ref(false);
const models = ref<NexusAI.ModelRow[]>([]);
const search = ref('');
const selected = ref<Set<number | string>>(new Set());
const submitting = ref(false);

async function loadAll() {
  loading.value = true;
  // 批量管理需要全量（分页上限 200·模型目录为小表）
  const { data } = await fetchModels({ pageNum: 1, pageSize: 200 });
  models.value = data?.records ?? [];
  loading.value = false;
}

watch(show, open => {
  if (open) {
    search.value = '';
    selected.value = new Set();
    loadAll();
  }
});

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase();
  if (!q) return models.value;
  return models.value.filter(r => `${r.model.modelKey} ${r.model.modelAlias ?? ''}`.toLowerCase().includes(q));
});

/** 快捷选中（batchManageShortcuts 五件套）。 */
const shortcuts = computed(() => {
  const defs: { label: string; description: string; filter: (r: NexusAI.ModelRow) => boolean }[] = [
    { label: '无提供商', description: '没有关联任何提供商的模型', filter: r => (num(r.channelCount) ?? 0) === 0 },
    {
      label: '无活跃提供商',
      description: '有提供商但没有活跃提供商的模型',
      filter: r => (num(r.activeChannelCount) ?? 0) === 0 && (num(r.channelCount) ?? 0) > 0
    },
    { label: '停用', description: '被停用的模型', filter: r => r.model.status !== 1 },
    { label: '未调用', description: '近 30 天没有调用记录的模型', filter: r => (num(r.usageCount) ?? 0) === 0 },
    { label: '无价格', description: '没有配置任何价格的模型', filter: r => priceLine(r.model).length === 0 }
  ];
  return defs.map(d => ({ ...d, count: models.value.filter(d.filter).length }));
});

function applyShortcut(filter: (r: NexusAI.ModelRow) => boolean) {
  selected.value = new Set(models.value.filter(filter).map(r => r.model.id!));
}

const allSelected = computed(
  () => filtered.value.length > 0 && filtered.value.every(r => selected.value.has(r.model.id!))
);

function toggleAll() {
  const set = new Set(selected.value);
  if (allSelected.value) {
    filtered.value.forEach(r => set.delete(r.model.id!));
  } else {
    filtered.value.forEach(r => set.add(r.model.id!));
  }
  selected.value = set;
}

function toggle(id: number | string) {
  const set = new Set(selected.value);
  if (set.has(id)) set.delete(id);
  else set.add(id);
  selected.value = set;
}

async function confirmDelete() {
  if (selected.value.size === 0) return;
  submitting.value = true;
  emit('confirm', [...selected.value]);
  submitting.value = false;
  show.value = false;
}
</script>

<template>
  <NModal v-model:show="show" preset="card" title="批量管理模型" class="w-680px max-w-[94vw]">
    <template #header-extra>
      <span class="text-12px text-gray-400">选择要删除的模型</span>
    </template>

    <div class="space-y-12px">
      <NInput v-model:value="search" size="small" placeholder="搜索模型..." clearable>
        <template #prefix>
          <SvgIcon icon="lucide:search" class="text-gray-400" />
        </template>
      </NInput>

      <!-- 快捷选中 -->
      <div class="flex flex-wrap items-center gap-6px">
        <span class="text-12px text-gray-400">快捷选中:</span>
        <NTooltip v-for="s in shortcuts" :key="s.label">
          <template #trigger>
            <NButton size="tiny" tertiary @click="applyShortcut(s.filter)">{{ s.label }} ({{ s.count }})</NButton>
          </template>
          {{ s.description }}
        </NTooltip>
      </div>

      <!-- 列表 -->
      <div class="overflow-hidden border border-gray-200 rounded-8px dark:border-gray-700">
        <div class="max-h-360px overflow-y-auto">
          <div v-if="loading" class="flex-center py-48px">
            <NSpin size="small" />
          </div>
          <template v-else-if="filtered.length > 0">
            <div class="sticky top-0 z-10 flex items-center justify-between bg-gray-50 px-12px py-6px dark:bg-gray-800">
              <span class="text-12px font-medium">模型 ({{ filtered.length }})</span>
              <NButton text size="tiny" type="primary" @click="toggleAll">
                {{ allSelected ? '取消全选' : '全选' }}
              </NButton>
            </div>
            <div class="p-6px space-y-2px">
              <div
                v-for="row in filtered"
                :key="String(row.model.id)"
                class="flex cursor-pointer items-center gap-8px rounded-6px px-8px py-5px hover:bg-gray-50 dark:hover:bg-gray-800"
                @click="toggle(row.model.id!)"
              >
                <NCheckbox :checked="selected.has(row.model.id!)" @click.stop="toggle(row.model.id!)" />
                <div class="min-w-0 flex-1">
                  <div class="truncate text-13px font-medium">{{ row.model.modelAlias || row.model.modelKey }}</div>
                  <div class="truncate text-11px text-gray-400 font-mono">{{ row.model.modelKey }}</div>
                </div>
                <NTag size="tiny" round>{{ num(row.activeChannelCount) ?? 0 }}/{{ num(row.channelCount) ?? 0 }}</NTag>
                <NTag size="tiny" :type="row.model.status === 1 ? 'success' : 'default'" :bordered="row.model.status === 1">
                  {{ row.model.status === 1 ? '活跃' : '停用' }}
                </NTag>
              </div>
            </div>
          </template>
          <NEmpty v-else :description="search ? '无匹配结果' : '暂无模型'" class="py-32px" />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <span class="text-12px text-gray-400">
          {{ selected.size > 0 ? `已选择 ${selected.size} 个模型` : '' }}
        </span>
        <NSpace :size="8">
          <NPopconfirm @positive-click="confirmDelete">
            <template #trigger>
              <NButton size="small" type="error" :disabled="selected.size === 0" :loading="submitting">
                删除选中
              </NButton>
            </template>
            确定删除选中的 {{ selected.size }} 个模型吗？此操作不可撤销。
          </NPopconfirm>
          <NButton size="small" @click="show = false">关闭</NButton>
        </NSpace>
      </div>
    </template>
  </NModal>
</template>
