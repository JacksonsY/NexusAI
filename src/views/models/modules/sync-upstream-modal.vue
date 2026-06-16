<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchModelSync, fetchModelSyncPreview } from '@/service/api';

defineOptions({ name: 'SyncUpstreamModal' });

const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ success: [] }>();

const loading = ref(false);
const submitting = ref(false);
const missing = ref<NexusAI.ModelSyncMeta[]>([]);
const conflicts = ref<NexusAI.ModelSyncConflict[]>([]);
const selectedCreate = ref<Set<string>>(new Set());
/** modelKey:field 维度勾选 */
const selectedFields = ref<Set<string>>(new Set());

const FIELD_LABELS: Record<string, string> = {
  modelAlias: '名称',
  contextLength: '上下文',
  maxOutputTokens: '最大输出',
  inputRate: '输入费率',
  outputRate: '输出费率',
  cachedInputRate: '缓存费率'
};

async function loadPreview() {
  loading.value = true;
  const { data } = await fetchModelSyncPreview();
  missing.value = data?.missing ?? [];
  conflicts.value = data?.conflicts ?? [];
  // 默认全选新建；覆盖默认不勾（同策略：覆盖需显式选择）
  selectedCreate.value = new Set(missing.value.map(m => m.modelKey));
  selectedFields.value = new Set();
  loading.value = false;
}

watch(show, open => {
  if (open) loadPreview();
});

function toggleCreate(key: string) {
  const set = new Set(selectedCreate.value);
  if (set.has(key)) set.delete(key);
  else set.add(key);
  selectedCreate.value = set;
}

function fieldKey(modelKey: string, field: string) {
  return `${modelKey}:${field}`;
}

function toggleField(modelKey: string, field: string) {
  const k = fieldKey(modelKey, field);
  const set = new Set(selectedFields.value);
  if (set.has(k)) set.delete(k);
  else set.add(k);
  selectedFields.value = set;
}

const hasChanges = computed(() => selectedCreate.value.size > 0 || selectedFields.value.size > 0);

function fmt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '-';
  return String(v);
}

async function submit() {
  const overwrite: Record<string, string[]> = {};
  selectedFields.value.forEach(k => {
    const i = k.indexOf(':');
    if (i <= 0) return;
    const modelKey = k.slice(0, i);
    const field = k.slice(i + 1);
    (overwrite[modelKey] ??= []).push(field);
  });
  submitting.value = true;
  const { data, error } = await fetchModelSync({ createKeys: [...selectedCreate.value], overwrite });
  submitting.value = false;
  if (!error) {
    window.$message?.success(`同步完成：新建 ${data?.created ?? 0} 个，更新 ${data?.updated ?? 0} 个`);
    emit('success');
    show.value = false;
  }
}
</script>

<template>
  <NModal v-model:show="show" preset="card" title="同步上游模型" class="w-760px max-w-[94vw]">
    <template #header-extra>
      <span class="text-12px text-gray-400">数据源 models.dev · 本地锁定（关闭官方同步）的模型不参与</span>
    </template>

    <div v-if="loading" class="flex-center py-64px">
      <NSpin size="small" />
    </div>

    <div v-else class="space-y-16px">
      <!-- 区1：缺失模型（默认全选新建） -->
      <div class="space-y-8px">
        <div class="flex items-center gap-6px">
          <h4 class="text-13px font-semibold">可创建的新模型</h4>
          <NTag size="tiny" round type="warning">{{ missing.length }}</NTag>
          <span class="text-12px text-gray-400">路由已登记但目录未建，上游有元数据可一键补齐</span>
        </div>
        <NEmpty v-if="missing.length === 0" description="没有可创建的缺失模型" size="small" class="py-12px" />
        <div v-else class="overflow-hidden border border-gray-200 rounded-8px dark:border-gray-700">
          <div class="max-h-220px overflow-y-auto p-6px space-y-2px">
            <div
              v-for="m in missing"
              :key="m.modelKey"
              class="flex cursor-pointer items-center gap-8px rounded-6px px-8px py-5px hover:bg-gray-50 dark:hover:bg-gray-800"
              @click="toggleCreate(m.modelKey)"
            >
              <NCheckbox :checked="selectedCreate.has(m.modelKey)" @click.stop="toggleCreate(m.modelKey)" />
              <div class="min-w-0 flex-1">
                <div class="truncate text-13px font-medium">{{ m.modelAlias || m.modelKey }}</div>
                <div class="truncate text-11px font-mono text-gray-400">{{ m.modelKey }}</div>
              </div>
              <span class="text-11px font-mono text-gray-400">
                In: {{ m.inputRate ?? '-' }} / Out: {{ m.outputRate ?? '-' }}
              </span>
              <NTag size="tiny" :bordered="false">{{ m.providerId }}</NTag>
            </div>
          </div>
        </div>
      </div>

      <!-- 区2：字段差异（默认不勾·显式选择覆盖） -->
      <div class="space-y-8px">
        <div class="flex items-center gap-6px">
          <h4 class="text-13px font-semibold">与上游的字段差异</h4>
          <NTag size="tiny" round>{{ conflicts.length }}</NTag>
          <span class="text-12px text-gray-400">勾选的字段将以上游值覆盖本地</span>
        </div>
        <NEmpty v-if="conflicts.length === 0" description="本地模型与上游一致" size="small" class="py-12px" />
        <div v-else class="overflow-hidden border border-gray-200 rounded-8px dark:border-gray-700">
          <div class="max-h-260px overflow-y-auto divide-y divide-gray-100 dark:divide-gray-800">
            <div v-for="c in conflicts" :key="c.modelKey" class="p-8px">
              <div class="mb-4px flex items-center gap-6px">
                <span class="text-13px font-medium font-mono">{{ c.modelKey }}</span>
                <NTag size="tiny" :bordered="false">{{ c.providerId }}</NTag>
              </div>
              <div class="space-y-2px">
                <div
                  v-for="f in c.fields"
                  :key="f.field"
                  class="flex cursor-pointer items-center gap-8px rounded-4px px-6px py-3px text-12px hover:bg-gray-50 dark:hover:bg-gray-800"
                  @click="toggleField(c.modelKey, f.field)"
                >
                  <NCheckbox
                    :checked="selectedFields.has(fieldKey(c.modelKey, f.field))"
                    @click.stop="toggleField(c.modelKey, f.field)"
                  />
                  <span class="w-64px text-gray-500">{{ FIELD_LABELS[f.field] || f.field }}</span>
                  <span class="font-mono text-gray-400 line-through">{{ fmt(f.local) }}</span>
                  <SvgIcon icon="lucide:arrow-right" class="text-10px text-gray-400" />
                  <span class="font-mono text-primary">{{ fmt(f.upstream) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <span class="text-12px text-gray-400">
          {{ hasChanges ? `将新建 ${selectedCreate.size} 个 · 覆盖 ${selectedFields.size} 个字段` : '' }}
        </span>
        <NSpace :size="8">
          <NButton size="small" type="primary" :disabled="!hasChanges" :loading="submitting" @click="submit">
            执行同步
          </NButton>
          <NButton size="small" @click="show = false">关闭</NButton>
        </NSpace>
      </div>
    </template>
  </NModal>
</template>
