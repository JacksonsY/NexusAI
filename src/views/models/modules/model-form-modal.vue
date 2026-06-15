<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchAddModel, fetchModelsDevCatalog, fetchUpdateModel } from '@/service/api';

defineOptions({ name: 'ModelFormModal' });

interface Props {
  model?: NexusAI.Model | null;
}

const props = defineProps<Props>();
const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ success: [] }>();

/** 编辑模式 = 传入 model 带 id；带 modelKey 不带 id = 缺失模型预填创建 */
const isEdit = computed(() => Boolean(props.model?.id));

const form = ref<NexusAI.Model>({});
const submitting = ref(false);

// ===== 左侧 models.dev 目录树（仅创建模式·Aether GlobalModelFormDialog 左栏） =====
interface DevItem {
  providerId: string;
  providerName: string;
  modelId: string;
  modelName: string;
  official?: boolean;
  deprecated?: boolean;
  releaseDate?: string;
  inputPrice?: number;
  outputPrice?: number;
  cacheReadPrice?: number;
  contextLimit?: number;
  outputLimit?: number;
  embedding?: boolean;
  imageOut?: boolean;
}

const catalogLoading = ref(false);
const allItems = ref<DevItem[]>([]);
const treeSearch = ref('');
const expandedProvider = ref<string | null>(null);
const selectedItem = ref<DevItem | null>(null);

async function loadCatalog() {
  if (allItems.value.length > 0) return;
  catalogLoading.value = true;
  const { data } = await fetchModelsDevCatalog();
  const items: DevItem[] = [];
  Object.entries(data ?? {}).forEach(([providerId, provider]) => {
    Object.entries(provider?.models ?? {}).forEach(([modelId, m]) => {
      items.push({
        providerId,
        providerName: provider?.name || providerId,
        modelId,
        modelName: m?.name || modelId,
        official: provider?.official,
        deprecated: m?.deprecated,
        releaseDate: m?.release_date,
        inputPrice: m?.cost?.input,
        outputPrice: m?.cost?.output,
        cacheReadPrice: m?.cost?.cache_read,
        contextLimit: m?.limit?.context,
        outputLimit: m?.limit?.output,
        embedding: /embedding/i.test(`${modelId} ${m?.name ?? ''}`),
        imageOut: m?.output?.includes('image')
      });
    });
  });
  // provider 名排序，组内按发布日期新→旧（Aether 同序）
  items.sort((a, b) => {
    const p = a.providerName.localeCompare(b.providerName);
    if (p !== 0) return p;
    const ad = a.releaseDate ? Date.parse(a.releaseDate) : 0;
    const bd = b.releaseDate ? Date.parse(b.releaseDate) : 0;
    if (ad !== bd) return bd - ad;
    return a.modelName.localeCompare(b.modelName);
  });
  allItems.value = items;
  catalogLoading.value = false;
}

interface ProviderGroup {
  providerId: string;
  providerName: string;
  models: DevItem[];
}

/** 无搜索词只显示官方提供商（数据无 official 标记时显示全部）；有词搜全部（多关键词 AND·Aether 同策略）。 */
const groupedModels = computed<ProviderGroup[]>(() => {
  const query = treeSearch.value.trim().toLowerCase();
  const keywords = query.split(/\s+/).filter(Boolean);
  let items = allItems.value.filter(m => !m.deprecated);
  if (query) {
    items = items.filter(m => {
      const text = `${m.providerId} ${m.providerName} ${m.modelId} ${m.modelName}`.toLowerCase();
      return keywords.every(k => text.includes(k));
    });
  } else if (allItems.value.some(m => m.official !== undefined)) {
    items = items.filter(m => m.official);
  }
  const groups = new Map<string, ProviderGroup>();
  for (const item of items) {
    if (!groups.has(item.providerId)) {
      groups.set(item.providerId, { providerId: item.providerId, providerName: item.providerName, models: [] });
    }
    groups.get(item.providerId)!.models.push(item);
  }
  const result = [...groups.values()];
  // 搜索词命中 provider 名的组排前（Aether 同策略），其余按名称
  result.sort((a, b) => {
    if (keywords.length > 0) {
      const aText = `${a.providerId} ${a.providerName}`.toLowerCase();
      const bText = `${b.providerId} ${b.providerName}`.toLowerCase();
      const aHit = keywords.some(k => aText.includes(k));
      const bHit = keywords.some(k => bText.includes(k));
      if (aHit && !bHit) return -1;
      if (!aHit && bHit) return 1;
    }
    return a.providerName.localeCompare(b.providerName);
  });
  return result;
});

watch(groupedModels, groups => {
  if (treeSearch.value && groups.length === 1) expandedProvider.value = groups[0].providerId;
});

function toggleProvider(id: string) {
  expandedProvider.value = expandedProvider.value === id ? null : id;
}

function logoUrl(providerId: string) {
  return `https://models.dev/logos/${providerId}.svg`;
}

function hideLogo(e: Event) {
  (e.target as HTMLImageElement).style.display = 'none';
}

/** 点选目录模型 → 自动填充表单（modelKey/别名/上下文/输出上限/费率·Aether selectModel）。 */
function selectItem(item: DevItem) {
  selectedItem.value = item;
  expandedProvider.value = item.providerId;
  form.value.modelKey = item.modelId;
  form.value.modelAlias = item.modelName;
  form.value.contextLength = item.contextLimit;
  form.value.maxOutputTokens = item.outputLimit;
  form.value.inputRate = item.inputPrice;
  form.value.outputRate = item.outputPrice;
  form.value.cachedInputRate = item.cacheReadPrice;
  form.value.modality = item.embedding ? 'EMBEDDING' : item.imageOut ? 'IMAGE' : 'CHAT';
}

function clearSelection() {
  selectedItem.value = null;
  form.value = { modality: 'CHAT', status: 1 };
}

// ===== 表单 =====
const MODALITIES = ['CHAT', 'EMBEDDING', 'IMAGE', 'RERANK'].map(v => ({ label: v, value: v }));

watch(show, open => {
  if (!open) return;
  if (props.model) {
    form.value = { ...props.model };
  } else {
    form.value = { modality: 'CHAT', status: 1 };
  }
  selectedItem.value = null;
  treeSearch.value = '';
  expandedProvider.value = null;
  if (!isEdit.value) loadCatalog();
});

async function submit() {
  if (!form.value.modelKey) {
    window.$message?.error('请填写模型ID');
    return;
  }
  submitting.value = true;
  const { error } = isEdit.value
    ? await fetchUpdateModel(form.value.id!, form.value)
    : await fetchAddModel(form.value);
  submitting.value = false;
  if (!error) {
    window.$message?.success(isEdit.value ? '模型更新成功' : '模型创建成功');
    emit('success');
    if (isEdit.value) {
      show.value = false;
    } else {
      // 创建模式保持打开以便连续添加（Aether 行为）
      clearSelection();
    }
  }
}
</script>

<template>
  <NModal
    v-model:show="show"
    preset="card"
    :title="isEdit ? '编辑模型' : '创建统一模型'"
    class="w-880px max-w-[94vw]"
  >
    <div class="flex gap-16px" :class="isEdit ? '' : 'h-560px'">
      <!-- 左侧：models.dev 目录树（仅创建模式） -->
      <div v-if="!isEdit" class="w-260px flex flex-col shrink-0">
        <NInput v-model:value="treeSearch" size="small" placeholder="搜索模型、提供商..." clearable class="mb-8px">
          <template #prefix>
            <SvgIcon icon="lucide:search" class="text-gray-400" />
          </template>
        </NInput>
        <div class="flex-1 overflow-y-auto border border-gray-200 rounded-8px min-h-0 dark:border-gray-700">
          <div v-if="catalogLoading" class="flex-center h-120px">
            <NSpin size="small" />
          </div>
          <template v-else>
            <div v-for="group in groupedModels" :key="group.providerId" class="border-b border-gray-100 last:border-b-0 dark:border-gray-800">
              <div
                class="flex items-center gap-6px px-8px py-6px cursor-pointer text-13px hover:bg-gray-50 dark:hover:bg-gray-800"
                @click="toggleProvider(group.providerId)"
              >
                <SvgIcon
                  icon="lucide:chevron-right"
                  class="text-12px text-gray-400 transition-transform shrink-0"
                  :class="expandedProvider === group.providerId ? 'rotate-90' : ''"
                />
                <img :src="logoUrl(group.providerId)" class="w-14px h-14px rounded shrink-0 dark:invert" @error="hideLogo" />
                <span class="flex-1 truncate font-medium text-12px">{{ group.providerName }}</span>
                <span class="text-10px text-gray-400 shrink-0">{{ group.models.length }}</span>
              </div>
              <div v-if="expandedProvider === group.providerId" class="bg-gray-50/60 dark:bg-gray-800/40">
                <div
                  v-for="item in group.models"
                  :key="item.modelId"
                  class="flex flex-col gap-1px pl-26px pr-8px py-5px cursor-pointer text-12px border-t border-gray-100 dark:border-gray-800"
                  :class="
                    selectedItem?.modelId === item.modelId && selectedItem?.providerId === item.providerId
                      ? 'bg-primary text-white'
                      : 'hover:bg-gray-100 dark:hover:bg-gray-700'
                  "
                  @click="selectItem(item)"
                >
                  <span class="truncate font-medium">{{ item.modelName }}</span>
                  <span
                    class="truncate text-10px"
                    :class="
                      selectedItem?.modelId === item.modelId && selectedItem?.providerId === item.providerId
                        ? 'text-white/70'
                        : 'text-gray-400'
                    "
                  >
                    {{ item.modelId }}
                  </span>
                </div>
              </div>
            </div>
            <div v-if="groupedModels.length === 0" class="py-32px text-center text-13px text-gray-400">
              {{ treeSearch ? '未找到模型' : '目录为空' }}
            </div>
          </template>
        </div>
      </div>

      <!-- 右侧：表单 -->
      <div class="flex-1 overflow-y-auto min-w-0" :class="isEdit ? 'max-h-[64vh]' : ''">
        <div class="space-y-16px">
          <section class="space-y-8px">
            <h4 class="text-13px font-semibold">基本信息</h4>
            <div class="grid grid-cols-2 gap-8px">
              <NFormItem label="名称" :show-feedback="false" size="small">
                <NInput v-model:value="form.modelAlias" placeholder="Claude 3.5 Sonnet" />
              </NFormItem>
              <NFormItem label="模型ID *" :show-feedback="false" size="small">
                <NInput v-model:value="form.modelKey" placeholder="claude-3-5-sonnet" :disabled="isEdit" />
              </NFormItem>
            </div>
            <NFormItem label="描述" :show-feedback="false" size="small">
              <NInput v-model:value="form.description" placeholder="简短描述此模型的特点" />
            </NFormItem>
            <div class="grid grid-cols-3 gap-8px">
              <NFormItem label="模态" :show-feedback="false" size="small">
                <NSelect v-model:value="form.modality" :options="MODALITIES" />
              </NFormItem>
              <NFormItem label="上下文窗口" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.contextLength" class="w-full" :min="0" placeholder="如 200000" />
              </NFormItem>
              <NFormItem label="最大输出 Token" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.maxOutputTokens" class="w-full" :min="0" placeholder="如 8192" />
              </NFormItem>
            </div>
          </section>

          <section class="space-y-8px">
            <h4 class="text-13px font-semibold">价格配置（$/M 当量·结构化费率）</h4>
            <div class="grid grid-cols-3 gap-8px">
              <NFormItem label="输入 (In)" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.inputRate" class="w-full" :min="0" :step="0.01" placeholder="$/M" />
              </NFormItem>
              <NFormItem label="输出 (Out)" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.outputRate" class="w-full" :min="0" :step="0.01" placeholder="$/M" />
              </NFormItem>
              <NFormItem label="缓存读取" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.cachedInputRate" class="w-full" :min="0" :step="0.01" placeholder="$/M" />
              </NFormItem>
            </div>
            <div class="grid grid-cols-2 gap-8px">
              <NFormItem label="按次计费" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.perRequestFee" class="w-full" :min="0" :step="0.001" placeholder="$/次（可与 Token 计费叠加）" />
              </NFormItem>
              <NFormItem label="按图计费" :show-feedback="false" size="small">
                <NInputNumber v-model:value="form.perImageFee" class="w-full" :min="0" :step="0.001" placeholder="$/张" />
              </NFormItem>
            </div>
            <NText depth="3" class="block text-12px">
              input/output 非对称 + cached 单独价（防 output-heavy 亏钱）；价格真相保存在本地模型目录，数据面按需读取。
            </NText>
            <div class="flex items-center gap-8px pt-4px">
              <NSwitch
                size="small"
                :value="form.syncOfficial !== 0"
                @update:value="(v: boolean) => (form.syncOfficial = v ? 1 : 0)"
              />
              <span class="text-12px">官方同步</span>
              <span class="text-11px text-gray-400">开启后「同步上游」可用 models.dev 元数据覆盖此模型；关闭=本地锁定</span>
            </div>
          </section>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-end gap-8px">
        <NButton v-if="selectedItem && !isEdit" quaternary size="small" @click="clearSelection">清空</NButton>
        <NButton size="small" @click="show = false">取消</NButton>
        <NButton size="small" type="primary" :loading="submitting" :disabled="!form.modelKey" @click="submit">
          {{ isEdit ? '保存' : '添加' }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>
