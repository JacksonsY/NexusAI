<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  fetchAbilities,
  fetchCreateRoutingGroup,
  fetchDeleteRoutingGroup,
  fetchRoutingGroups,
  fetchSetDefaultRoutingGroup,
  fetchUpdateRoutingGroup
} from '@/service/api';
import PolicyModePicker from './modules/policy-mode-picker.vue';

defineOptions({ name: 'Routing' });

/**
 * 调度策略页（一比一RoutingProfiles）：左策略分组列表 + 右详情编辑器。
 * 策略 = 优先级模式(provider/key) × 调度策略(cache_affinity/load_balance/fixed_order)，
 * 统一调度作用全部模型；区分模型时可对单个模型覆盖。系统默认且启用的策略驱动运行时选路。
 */

const PRIORITY_OPTIONS = [
  { label: 'Provider', value: 'provider', icon: 'lucide:layers' },
  { label: 'Key', value: 'key', icon: 'lucide:key-round' }
];
const SCHEDULING_OPTIONS = [
  { label: '缓存亲和', value: 'cache_affinity' },
  { label: '负载均衡', value: 'load_balance' },
  { label: '固定顺序', value: 'fixed_order' }
];
const MODE_LABEL: Record<string, string> = {
  cache_affinity: '缓存亲和',
  load_balance: '负载均衡',
  fixed_order: '固定顺序',
  provider: 'Provider',
  key: 'Key'
};

interface DraftModelPolicy {
  model: string;
  priorityMode: string;
  schedulingMode: string;
}
interface Draft {
  id?: number | string;
  name: string;
  description: string;
  enabled: boolean;
  isSystemDefault: boolean;
  version?: number;
  updateDt?: string;
  defaultPolicy: { priorityMode: string; schedulingMode: string };
  modelPolicies: DraftModelPolicy[];
}

const loading = ref(false);
const saving = ref(false);
const deleting = ref(false);
const groups = ref<NexusAI.RoutingGroup[]>([]);
const selectedGroupId = ref<number | string | null>(null);
const isCreating = ref(false);
const draft = ref<Draft | null>(null);

/** 维度：统一调度 / 区分模型 */
const sortingScope = ref<'unified' | 'per_model'>('unified');

// ===== 区分模型 =====
const allModels = ref<string[]>([]);
const globalModelSearch = ref('');
const modelFilter = ref<'unconfigured' | 'configured'>('unconfigured');
const modelFilters = [
  { label: '未配置', value: 'unconfigured' as const },
  { label: '已配置', value: 'configured' as const }
];
const selectedPerModelName = ref<string | null>(null);

function parseConfig(json?: string): { defaultPolicy: Draft['defaultPolicy']; modelPolicies: DraftModelPolicy[] } {
  const fallback = { priorityMode: 'provider', schedulingMode: 'cache_affinity' };
  try {
    const cfg: NexusAI.RoutingGroupConfig = JSON.parse(json || '{}');
    return {
      defaultPolicy: {
        priorityMode: cfg.defaultPolicy?.priorityMode ?? fallback.priorityMode,
        schedulingMode: cfg.defaultPolicy?.schedulingMode ?? fallback.schedulingMode
      },
      modelPolicies: (cfg.modelPolicies ?? [])
        .filter(p => p.model)
        .map(p => ({
          model: p.model!,
          priorityMode: p.priorityMode ?? fallback.priorityMode,
          schedulingMode: p.schedulingMode ?? fallback.schedulingMode
        }))
    };
  } catch {
    return { defaultPolicy: { ...fallback }, modelPolicies: [] };
  }
}

function toDraft(g: NexusAI.RoutingGroup): Draft {
  const cfg = parseConfig(g.configJson);
  return {
    id: g.id,
    name: g.name ?? '',
    description: g.description ?? '',
    enabled: g.enabled !== false,
    isSystemDefault: g.isSystemDefault === true,
    version: g.version,
    updateDt: g.updateDt,
    defaultPolicy: cfg.defaultPolicy,
    modelPolicies: cfg.modelPolicies
  };
}

async function load(selectId?: number | string | null) {
  loading.value = true;
  const { data, error } = await fetchRoutingGroups();
  loading.value = false;
  if (error) return;
  groups.value = data ?? [];
  const target =
    groups.value.find(g => g.id === (selectId ?? selectedGroupId.value)) ??
    groups.value.find(g => g.isSystemDefault) ??
    groups.value[0];
  if (target) selectGroup(target);
  else {
    selectedGroupId.value = null;
    draft.value = null;
  }
}

async function loadModels() {
  const { data } = await fetchAbilities();
  allModels.value = [...new Set((data ?? []).map(a => a.model).filter(Boolean) as string[])].sort();
}

function selectGroup(g: NexusAI.RoutingGroup) {
  isCreating.value = false;
  selectedGroupId.value = g.id ?? null;
  draft.value = toDraft(g);
  sortingScope.value = draft.value.modelPolicies.length > 0 ? 'per_model' : 'unified';
  selectedPerModelName.value = null;
}

function startCreate() {
  isCreating.value = true;
  selectedGroupId.value = null;
  sortingScope.value = 'unified';
  selectedPerModelName.value = null;
  draft.value = {
    name: '',
    description: '',
    enabled: true,
    isSystemDefault: groups.value.length === 0,
    defaultPolicy: { priorityMode: 'provider', schedulingMode: 'cache_affinity' },
    modelPolicies: []
  };
}

function modelRangeText(g: NexusAI.RoutingGroup) {
  const n = parseConfig(g.configJson).modelPolicies.length;
  return n > 0 ? `${n} 个模型覆盖` : '全部模型统一';
}

const canSaveDraft = computed(() => !!draft.value?.name.trim() && !saving.value);

function buildConfigJson(d: Draft): string {
  return JSON.stringify({
    defaultPolicy: d.defaultPolicy,
    // 统一调度维度下保存即清空模型覆盖（与 removeGeneratedModelSchedulingRules 同语义）
    modelPolicies: sortingScope.value === 'per_model' ? d.modelPolicies : []
  });
}

async function saveDraft() {
  const d = draft.value;
  if (!d || !canSaveDraft.value) return;
  saving.value = true;
  const body: NexusAI.RoutingGroup = {
    name: d.name.trim(),
    description: d.description,
    enabled: d.enabled,
    isSystemDefault: d.isSystemDefault,
    configJson: buildConfigJson(d)
  };
  const { data, error } = d.id
    ? await fetchUpdateRoutingGroup(d.id, body).then(r => ({ data: d.id, error: r.error }))
    : await fetchCreateRoutingGroup(body);
  saving.value = false;
  if (!error) {
    window.$message?.success('策略已保存并即时生效');
    isCreating.value = false;
    load(data ?? d.id);
  }
}

async function deleteDraft() {
  const d = draft.value;
  if (!d?.id) return;
  deleting.value = true;
  const { error } = await fetchDeleteRoutingGroup(d.id);
  deleting.value = false;
  if (!error) {
    window.$message?.success('策略已删除');
    selectedGroupId.value = null;
    load(null);
  }
}

async function setAsDefault() {
  const d = draft.value;
  if (!d?.id || d.isSystemDefault) return;
  const { error } = await fetchSetDefaultRoutingGroup(d.id);
  if (!error) {
    window.$message?.success('已设为系统默认（运行时选路依据）');
    load(d.id);
  }
}

/** 列表项 Power 启停（即存） */
async function toggleGroupEnabled(g: NexusAI.RoutingGroup) {
  if (!g.id) return;
  const { error } = await fetchUpdateRoutingGroup(g.id, { ...g, enabled: g.enabled === false });
  if (!error) {
    window.$message?.success(g.enabled === false ? '策略已启用' : '策略已停用');
    load(g.id);
  }
}

// ===== 区分模型：行/过滤/覆盖编辑 =====
const modelRows = computed(() => {
  const kw = globalModelSearch.value.trim().toLowerCase();
  const configured = new Set(draft.value?.modelPolicies.map(p => p.model) ?? []);
  return allModels.value
    .filter(m => (kw ? m.toLowerCase().includes(kw) : true))
    .filter(m => (modelFilter.value === 'configured' ? configured.has(m) : !configured.has(m) || selectedPerModelName.value === m))
    .map(m => ({ name: m, configured: configured.has(m) }));
});

function modelPolicy(model: string): DraftModelPolicy {
  const d = draft.value!;
  return (
    d.modelPolicies.find(p => p.model === model) ?? {
      model,
      priorityMode: d.defaultPolicy.priorityMode,
      schedulingMode: d.defaultPolicy.schedulingMode
    }
  );
}

function upsertModelPolicy(model: string, patch: Partial<DraftModelPolicy>) {
  const d = draft.value;
  if (!d) return;
  const idx = d.modelPolicies.findIndex(p => p.model === model);
  if (idx >= 0) d.modelPolicies[idx] = { ...d.modelPolicies[idx], ...patch };
  else d.modelPolicies.push({ ...modelPolicy(model), ...patch });
}

function removeModelPolicy(model: string) {
  const d = draft.value;
  if (!d) return;
  d.modelPolicies = d.modelPolicies.filter(p => p.model !== model);
  if (selectedPerModelName.value === model) selectedPerModelName.value = null;
}

function selectGlobalModel(name: string) {
  selectedPerModelName.value = selectedPerModelName.value === name ? null : name;
}

/** 复制其他已配置模型的覆盖 */
const copySourceCandidates = computed(() =>
  (draft.value?.modelPolicies ?? []).filter(p => p.model !== selectedPerModelName.value)
);
const copyDropdownOptions = computed(() =>
  copySourceCandidates.value.map(p => ({
    label: `${p.model}（${MODE_LABEL[p.priorityMode]} · ${MODE_LABEL[p.schedulingMode]}）`,
    key: p.model
  }))
);
function copyModelConfig(sourceModel: string) {
  const src = draft.value?.modelPolicies.find(p => p.model === sourceModel);
  if (!src || !selectedPerModelName.value) return;
  upsertModelPolicy(selectedPerModelName.value, {
    priorityMode: src.priorityMode,
    schedulingMode: src.schedulingMode
  });
  window.$message?.success(`已加载 ${sourceModel} 的配置`);
}

function fmtTime(s?: string) {
  return s ? s.replace('T', ' ').slice(0, 19) : '—';
}

onMounted(() => {
  load();
  loadModels();
});
</script>

<template>
  <div class="grid gap-16px xl:grid-cols-[320px_minmax(0,1fr)]">
    <!-- 左：策略分组列表 -->
    <NCard :bordered="false" size="small" class="card-wrapper self-start">
      <div class="flex items-center justify-between gap-12px border-b border-gray-500/15 pb-12px">
        <div>
          <h2 class="m-0 text-14px font-semibold">策略分组</h2>
          <p class="m-0 mt-4px text-12px op-60">共 {{ groups.length }} 个</p>
        </div>
        <NButton quaternary size="small" :disabled="loading" title="添加策略" @click="startCreate">
          <template #icon>
            <SvgIcon icon="lucide:plus" />
          </template>
        </NButton>
      </div>

      <div class="max-h-[calc(100vh-300px)] overflow-y-auto pt-10px space-y-8px">
        <div v-if="loading" class="py-40px text-center text-13px op-60">正在加载调度策略</div>
        <template v-else>
          <div
            v-if="groups.length === 0 && !isCreating"
            class="border border-gray-500/30 rounded-10px border-dashed px-16px py-28px text-center"
          >
            <p class="m-0 text-13px font-medium">暂无调度策略</p>
            <p class="m-0 mt-4px text-12px op-60">可以先创建一个默认分组</p>
          </div>

          <!-- 新建草稿占位项 -->
          <div v-if="isCreating && draft" class="border border-primary/60 rounded-10px bg-primary/10 px-14px py-10px">
            <div class="flex items-start justify-between gap-10px">
              <div class="min-w-0">
                <p class="m-0 truncate text-13px font-medium">{{ draft.name || '新调度策略' }}</p>
                <p class="m-0 mt-2px line-clamp-2 text-12px op-60">{{ draft.description || '未填写描述' }}</p>
              </div>
              <NTag size="small" :type="draft.enabled ? 'primary' : 'default'">{{ draft.enabled ? '启用' : '停用' }}</NTag>
            </div>
            <div class="mt-6px flex items-center justify-between gap-10px text-12px op-60">
              <span>
                <template v-if="draft.isSystemDefault">系统默认 · </template>
                {{ draft.modelPolicies.length > 0 ? `${draft.modelPolicies.length} 个模型覆盖` : '全部模型统一' }}
              </span>
              <NButton
                quaternary
                size="tiny"
                :type="draft.enabled ? 'primary' : 'default'"
                :title="draft.enabled ? '已启用，点击停用' : '已停用，点击启用'"
                @click="draft.enabled = !draft.enabled"
              >
                <template #icon>
                  <SvgIcon icon="lucide:power" />
                </template>
              </NButton>
            </div>
          </div>

          <div
            v-for="g in groups"
            :key="g.id"
            role="button"
            tabindex="0"
            class="cursor-pointer border rounded-10px px-14px py-10px transition-colors"
            :class="
              g.id === selectedGroupId
                ? 'border-primary/60 bg-primary/10'
                : 'border-gray-500/20 hover:border-primary/40 hover:bg-gray-500/5'
            "
            @click="selectGroup(g)"
            @keydown.enter.prevent="selectGroup(g)"
          >
            <div class="flex items-start justify-between gap-10px">
              <div class="min-w-0">
                <p class="m-0 truncate text-13px font-medium">{{ g.name }}</p>
                <p class="m-0 mt-2px line-clamp-2 text-12px op-60">{{ g.description || '未填写描述' }}</p>
              </div>
              <NTag size="small" :type="g.enabled !== false ? 'primary' : 'default'">
                {{ g.enabled !== false ? '启用' : '停用' }}
              </NTag>
            </div>
            <div class="mt-6px flex items-center justify-between gap-10px text-12px op-60">
              <span>
                <template v-if="g.isSystemDefault">系统默认 · </template>
                {{ modelRangeText(g) }}
              </span>
              <NButton
                quaternary
                size="tiny"
                :type="g.enabled !== false ? 'primary' : 'default'"
                :title="g.enabled !== false ? '已启用，点击停用' : '已停用，点击启用'"
                @click.stop="toggleGroupEnabled(g)"
              >
                <template #icon>
                  <SvgIcon icon="lucide:power" />
                </template>
              </NButton>
            </div>
          </div>
        </template>
      </div>
    </NCard>

    <!-- 右：详情编辑器 -->
    <NCard v-if="draft" :bordered="false" size="small" class="card-wrapper self-start">
      <div class="flex flex-col gap-10px border-b border-gray-500/15 pb-12px lg:flex-row lg:items-start lg:justify-between">
        <div>
          <div class="flex flex-wrap items-center gap-8px">
            <h2 class="m-0 text-15px font-semibold">{{ isCreating ? '新建调度策略' : draft.name || '未命名策略' }}</h2>
            <NTag v-if="draft.isSystemDefault" size="small">系统默认</NTag>
          </div>
          <p class="m-0 mt-4px text-12px op-60">更新时间 {{ fmtTime(draft.updateDt) }} · v{{ draft.version ?? 1 }}</p>
        </div>
        <NSpace :size="4">
          <NButton
            quaternary
            size="small"
            :type="draft.isSystemDefault ? 'primary' : 'default'"
            :disabled="isCreating ? false : draft.isSystemDefault"
            :title="draft.isSystemDefault ? '系统默认' : '设为系统默认'"
            @click="isCreating ? (draft.isSystemDefault = !draft.isSystemDefault) : setAsDefault()"
          >
            <template #icon>
              <SvgIcon icon="lucide:star" />
            </template>
          </NButton>
          <NButton quaternary size="small" :disabled="!canSaveDraft" :loading="saving" title="保存" @click="saveDraft">
            <template #icon>
              <SvgIcon icon="lucide:save" />
            </template>
          </NButton>
          <NPopconfirm v-if="!isCreating" @positive-click="deleteDraft">
            <template #trigger>
              <NButton quaternary size="small" type="error" :loading="deleting" title="删除">
                <template #icon>
                  <SvgIcon icon="lucide:trash-2" />
                </template>
              </NButton>
            </template>
            确认删除调度策略「{{ draft.name }}」？此操作无法撤销。
          </NPopconfirm>
        </NSpace>
      </div>

      <div class="pt-14px space-y-18px">
        <!-- 基础信息 -->
        <div class="grid gap-12px lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.2fr)_300px]">
          <div class="text-13px space-y-4px">
            <span class="op-60">名称</span>
            <NInput v-model:value="draft.name" placeholder="新调度策略" :spellcheck="false" />
          </div>
          <div class="text-13px space-y-4px">
            <span class="op-60">描述</span>
            <NInput v-model:value="draft.description" placeholder="例如：默认策略 / 高推理策略 / 号池优先策略" :spellcheck="false" />
          </div>
          <div class="text-13px space-y-4px">
            <span class="op-60">维度</span>
            <PolicyModePicker
              v-model="sortingScope"
              :options="[
                { label: '统一调度', value: 'unified' },
                { label: '区分模型', value: 'per_model' }
              ]"
            />
          </div>
        </div>

        <!-- 统一调度 -->
        <section v-if="sortingScope === 'unified'" class="border border-gray-500/20 rounded-10px p-16px space-y-12px">
          <div>
            <h3 class="m-0 text-13px font-medium">优先级模式与调度策略</h3>
            <p class="m-0 mt-4px text-12px op-60">统一作用于策略范围内的全部模型。</p>
          </div>
          <div class="grid gap-12px lg:grid-cols-2">
            <div class="text-13px space-y-4px">
              <span class="op-60">优先级模式</span>
              <PolicyModePicker v-model="draft.defaultPolicy.priorityMode" :options="PRIORITY_OPTIONS" />
              <p class="m-0 text-12px op-50">
                Provider=按渠道优先级选路（默认）；Key=按路由表优先级，Key 级优先级在渠道密钥选号打分生效。
              </p>
            </div>
            <div class="text-13px space-y-4px">
              <span class="op-60">调度策略</span>
              <PolicyModePicker v-model="draft.defaultPolicy.schedulingMode" :options="SCHEDULING_OPTIONS" />
              <p class="m-0 text-12px op-50">缓存亲和=同会话粘渠道（利用上游 Prompt Cache）；负载均衡=加权随机；固定顺序=严格按序。</p>
            </div>
          </div>
        </section>

        <!-- 区分模型 -->
        <section v-else class="max-h-560px flex flex-col gap-12px overflow-hidden border border-gray-500/20 rounded-10px p-12px">
          <div class="grid grid-cols-2 gap-12px">
            <NInput v-model:value="globalModelSearch" placeholder="搜索模型" clearable :spellcheck="false" />
            <PolicyModePicker v-model="modelFilter" :options="modelFilters" />
          </div>
          <div class="rounded-8px bg-primary/5 px-12px py-8px text-12px op-70">
            默认策略：{{ MODE_LABEL[draft.defaultPolicy.priorityMode] }} · {{ MODE_LABEL[draft.defaultPolicy.schedulingMode] }}（未覆盖的模型沿用）·
            可切回「统一调度」维度修改（保存时将清空模型覆盖）。
          </div>

          <div
            v-if="modelRows.length === 0"
            class="border border-gray-500/30 rounded-8px border-dashed px-12px py-24px text-center text-12px op-60"
          >
            {{ globalModelSearch.trim() ? '未匹配到模型' : modelFilter === 'configured' ? '暂无已配置模型' : '暂无未配置模型' }}
          </div>
          <div v-else class="min-h-0 flex-1 overflow-y-auto pr-4px space-y-8px">
            <div
              v-for="row in modelRows"
              :key="row.name"
              class="border rounded-10px transition-colors"
              :class="selectedPerModelName === row.name ? 'border-primary/50 bg-primary/5' : 'border-gray-500/20'"
            >
              <div class="flex items-center gap-10px px-14px py-10px">
                <button
                  type="button"
                  class="min-w-0 flex flex-1 cursor-pointer items-center gap-10px border-none bg-transparent p-0 text-left text-13px"
                  @click="selectGlobalModel(row.name)"
                >
                  <span v-if="row.configured" class="h-8px w-8px shrink-0 rounded-full bg-primary" aria-hidden="true" />
                  <SvgIcon v-else icon="lucide:plus" class="shrink-0 text-12px op-60" />
                  <span class="min-w-0 flex-1 truncate font-mono font-medium">{{ row.name }}</span>
                  <NTag v-if="row.configured" size="tiny" :bordered="false">
                    {{ MODE_LABEL[modelPolicy(row.name).priorityMode] }} · {{ MODE_LABEL[modelPolicy(row.name).schedulingMode] }}
                  </NTag>
                </button>
                <template v-if="selectedPerModelName === row.name">
                  <NDropdown
                    :options="copyDropdownOptions"
                    :disabled="copyDropdownOptions.length === 0"
                    trigger="click"
                    @select="copyModelConfig"
                  >
                    <NButton quaternary size="tiny" title="加载其他模型配置" :disabled="copyDropdownOptions.length === 0">
                      <template #icon>
                        <SvgIcon icon="lucide:copy" />
                      </template>
                    </NButton>
                  </NDropdown>
                  <NButton
                    v-if="row.configured"
                    quaternary
                    size="tiny"
                    type="error"
                    title="移除当前模型覆盖"
                    @click="removeModelPolicy(row.name)"
                  >
                    <template #icon>
                      <SvgIcon icon="lucide:trash-2" />
                    </template>
                  </NButton>
                </template>
                <button
                  type="button"
                  class="shrink-0 cursor-pointer border-none bg-transparent p-0"
                  @click="selectGlobalModel(row.name)"
                >
                  <SvgIcon
                    icon="lucide:chevron-down"
                    class="text-14px op-60 transition-transform"
                    :class="selectedPerModelName === row.name ? 'rotate-180' : ''"
                  />
                </button>
              </div>

              <div v-if="selectedPerModelName === row.name" class="border-t border-gray-500/15 p-14px">
                <div class="border border-gray-500/20 rounded-10px p-14px space-y-10px">
                  <h3 class="m-0 text-13px font-medium">优先级模式与调度策略</h3>
                  <p class="m-0 text-12px op-60">仅作用于 {{ row.name }}（编辑即写入草稿·顶部保存生效）。</p>
                  <div class="grid gap-12px lg:grid-cols-2">
                    <div class="text-13px space-y-4px">
                      <span class="op-60">优先级模式</span>
                      <PolicyModePicker
                        :model-value="modelPolicy(row.name).priorityMode"
                        :options="PRIORITY_OPTIONS"
                        @update:model-value="v => upsertModelPolicy(row.name, { priorityMode: v })"
                      />
                    </div>
                    <div class="text-13px space-y-4px">
                      <span class="op-60">调度策略</span>
                      <PolicyModePicker
                        :model-value="modelPolicy(row.name).schedulingMode"
                        :options="SCHEDULING_OPTIONS"
                        @update:model-value="v => upsertModelPolicy(row.name, { schedulingMode: v })"
                      />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </NCard>

    <!-- 空态 -->
    <NCard v-else :bordered="false" size="small" class="card-wrapper min-h-360px flex-center self-start">
      <div class="py-80px text-center">
        <SvgIcon icon="lucide:sliders-horizontal" class="mx-auto text-28px op-50" />
        <p class="m-0 mt-12px text-13px font-medium">请选择或新建调度策略</p>
      </div>
    </NCard>
  </div>
</template>
