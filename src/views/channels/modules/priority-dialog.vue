<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  fetchChannelKeys,
  fetchSchedulingConfig,
  fetchUpdateChannelKey,
  fetchUpdateChannelPriorities,
  fetchUpdateScheduling
} from '@/service/api';

defineOptions({ name: 'PriorityDialog' });

/**
 * 优先级管理（PriorityManagementDialog 心智）：
 * 双 Tab——提供商优先 / Key 优先；拖拽调整顺序，点击序号可直接编辑（相同数字为同级）；
 * footer 选调度策略（缓存亲和/负载均衡/固定顺序），保存后自动切换为当前 Tab 对应的优先级生效层。
 */
const props = defineProps<{
  channels: NexusAI.ChannelSummary[];
}>();

const show = defineModel<boolean>('show', { required: true });
const emit = defineEmits<{ saved: [] }>();

const activeTab = ref<'provider' | 'key'>('provider');
const saving = ref(false);

// ===== 调度模式（系统配置·保存时随优先级一并提交） =====
const schedulingMode = ref<'cache_affinity' | 'load_balance' | 'fixed_order'>('cache_affinity');

const SCHEDULING_OPTIONS = [
  { label: '缓存亲和', value: 'cache_affinity', tip: '优先使用已缓存的渠道，利用上游 Prompt Cache' },
  { label: '负载均衡', value: 'load_balance', tip: '同优先级内加权随机轮换，不考虑缓存' },
  { label: '固定顺序', value: 'fixed_order', tip: '严格按优先级顺序，不考虑缓存' }
] as const;

async function loadScheduling() {
  const { data, error } = await fetchSchedulingConfig();
  if (!error && data) {
    if (data.schedulingMode === 'cache_affinity' || data.schedulingMode === 'load_balance' || data.schedulingMode === 'fixed_order') {
      schedulingMode.value = data.schedulingMode;
    }
    // 优先级生效层回显到激活 Tab（provider=提供商优先 / key=Key 优先）
    activeTab.value = data.priorityMode === 'key' ? 'key' : 'provider';
  }
}

// ===== 提供商优先级（拖拽排序 + 序号直编） =====
interface ChannelRow {
  id: number;
  name: string;
  protocol?: string;
  status?: number | null;
  priority: number;
}

const channelRows = ref<ChannelRow[]>([]);
const dragIndex = ref<number | null>(null);
const dragOverIndex = ref<number | null>(null);
const editingId = ref<number | null>(null);

function initChannelRows() {
  channelRows.value = props.channels
    .map(r => ({
      id: Number(r.channel.id),
      name: r.channel.name ?? '',
      protocol: r.channel.protocol,
      status: r.channel.status,
      priority: r.channel.priority ?? 0
    }))
    .sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name));
}

function onDragStart(index: number, event: DragEvent) {
  dragIndex.value = index;
  event.dataTransfer?.setData('text/plain', String(index));
}

function onDrop(index: number) {
  if (dragIndex.value === null || dragIndex.value === index) {
    dragIndex.value = null;
    dragOverIndex.value = null;
    return;
  }
  const rows = [...channelRows.value];
  const [moved] = rows.splice(dragIndex.value, 1);
  rows.splice(index, 0, moved);
  // 拖拽后按落位重排优先级（1..n·顺序即优先级）
  rows.forEach((row, i) => {
    row.priority = i + 1;
  });
  channelRows.value = rows;
  dragIndex.value = null;
  dragOverIndex.value = null;
}

function finishEditPriority(row: ChannelRow, event: Event) {
  const value = Number((event.target as HTMLInputElement).value);
  if (Number.isFinite(value) && value >= 1) {
    row.priority = Math.round(value);
    channelRows.value = [...channelRows.value].sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name));
  }
  editingId.value = null;
}

// ===== Key 优先级（左侧渠道分组 + 右侧该渠道 Key 排序·Key 优先 Tab 形态） =====
const keys = ref<NexusAI.ChannelKey[]>([]);
const keysLoading = ref(false);
const activeChannelId = ref<number | null>(null);
const editingKeyId = ref<number | null>(null);
const dirtyKeyIds = ref<Set<number>>(new Set());

const channelsWithKeys = computed(() => {
  const ids = new Set(keys.value.map(k => Number(k.channelId)));
  return props.channels.filter(r => ids.has(Number(r.channel.id)));
});

const activeChannelKeys = computed(() =>
  keys.value
    .filter(k => Number(k.channelId) === activeChannelId.value)
    .sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0))
);

async function loadKeys() {
  keysLoading.value = true;
  try {
    const { data, error } = await fetchChannelKeys();
    if (!error) {
      keys.value = data ?? [];
      dirtyKeyIds.value = new Set();
      if (activeChannelId.value == null && channelsWithKeys.value.length) {
        activeChannelId.value = Number(channelsWithKeys.value[0].channel.id);
      }
    }
  } finally {
    keysLoading.value = false;
  }
}

function finishEditKeyPriority(row: NexusAI.ChannelKey, event: Event) {
  const value = Number((event.target as HTMLInputElement).value);
  if (Number.isFinite(value) && value >= 1 && row.id != null) {
    row.priority = Math.round(value);
    dirtyKeyIds.value = new Set(dirtyKeyIds.value).add(row.id);
  }
  editingKeyId.value = null;
}

// ===== 保存（优先级 + 调度模式 + 优先级生效层一并提交·与 同语义） =====
async function save() {
  saving.value = true;
  try {
    if (activeTab.value === 'provider') {
      const { error } = await fetchUpdateChannelPriorities(channelRows.value.map(r => ({ id: r.id, priority: r.priority })));
      if (error) return;
    } else {
      for (const key of keys.value) {
        if (key.id != null && dirtyKeyIds.value.has(key.id)) {
          await fetchUpdateChannelKey(key.id, { priority: key.priority });
        }
      }
    }
    // 保存后自动切换为当前 Tab 对应的优先级生效层（同语义）
    await fetchUpdateScheduling({
      schedulingMode: schedulingMode.value,
      priorityMode: activeTab.value
    });
    window.$message?.success('优先级与调度策略已保存并生效');
    show.value = false;
    emit('saved');
  } finally {
    saving.value = false;
  }
}

function channelName(channelId?: number | string) {
  const found = props.channels.find(r => Number(r.channel.id) === Number(channelId));
  return found?.channel.name ?? `渠道 ${channelId}`;
}

watch(show, visible => {
  if (!visible) return;
  initChannelRows();
  loadKeys();
  loadScheduling();
});
</script>

<template>
  <NModal v-model:show="show" preset="card" class="w-680px" title="优先级管理" :bordered="false">
    <template #header-extra>
      <span class="text-12px op-60">拖拽调整顺序，点击序号可编辑（相同数字为同级）</span>
    </template>

    <NTabs v-model:value="activeTab" type="segment" animated>
      <NTab name="provider">提供商优先</NTab>
      <NTab name="key">Key 优先</NTab>
    </NTabs>

    <div class="mt-12px h-420px">
      <!-- 提供商优先级 -->
      <div v-show="activeTab === 'provider'" class="h-full overflow-y-auto pr-4px">
        <div v-if="!channelRows.length" class="flex flex-col items-center justify-center py-64px op-60">
          <SvgIcon icon="lucide:layers" class="mb-8px text-32px op-30" />
          <span class="text-13px">暂无提供商</span>
        </div>
        <div v-else class="flex-col gap-4px">
          <div
            v-for="(row, index) in channelRows"
            :key="row.id"
            class="prio-row flex items-center gap-10px border rounded-10px px-10px py-6px transition-all duration-150"
            :class="
              dragIndex === index
                ? 'border-primary/50 bg-primary/5 shadow-md'
                : dragOverIndex === index
                  ? 'border-primary/30 bg-primary/5'
                  : 'border-gray-500/20 hover:bg-gray-500/5'
            "
            draggable="true"
            @dragstart="onDragStart(index, $event)"
            @dragend="dragIndex = null"
            @dragover.prevent="dragOverIndex = index"
            @dragleave="dragOverIndex = null"
            @drop="onDrop(index)"
          >
            <SvgIcon icon="lucide:grip-vertical" class="cursor-grab text-14px op-30 active:cursor-grabbing" />
            <input
              v-if="editingId === row.id"
              type="number"
              min="1"
              :value="row.priority"
              class="prio-input h-22px w-34px border border-primary rounded-6px bg-transparent text-center text-12px font-medium focus:outline-none"
              @blur="finishEditPriority(row, $event)"
              @keydown.enter="($event.target as HTMLInputElement).blur()"
              @keydown.escape="editingId = null"
            />
            <div
              v-else
              class="h-22px w-22px flex shrink-0 cursor-pointer items-center justify-center rounded-6px bg-gray-500/10 text-12px font-medium op-70 hover:bg-gray-500/20"
              title="点击编辑优先级，相同数字为同级"
              @click="editingId = row.id"
            >
              {{ row.priority }}
            </div>
            <span class="min-w-0 flex-1 truncate text-13px font-medium">{{ row.name }}</span>
            <NTag v-if="row.status !== 1" size="tiny">停用</NTag>
            <NTag size="tiny" class="font-mono">{{ row.protocol }}</NTag>
          </div>
        </div>
      </div>

      <!-- Key 优先级（左侧渠道 + 右侧该渠道 Key·序号直编） -->
      <div v-show="activeTab === 'key'" class="h-full">
        <div v-if="keysLoading" class="flex items-center justify-center py-64px op-60">
          <SvgIcon icon="lucide:loader-2" class="animate-spin text-18px" />
        </div>
        <div v-else-if="!channelsWithKeys.length" class="flex flex-col items-center justify-center py-64px op-60">
          <SvgIcon icon="lucide:key-round" class="mb-8px text-32px op-30" />
          <span class="text-13px">暂无 API Key（在提供商表单中填写 Key）</span>
        </div>
        <div v-else class="h-full flex gap-12px">
          <div class="w-160px shrink-0 overflow-y-auto border-r border-gray-500/15 pr-8px">
            <button
              v-for="row in channelsWithKeys"
              :key="String(row.channel.id)"
              type="button"
              class="mb-2px w-full cursor-pointer truncate border-none rounded-8px bg-transparent px-10px py-6px text-left text-12px font-medium transition-colors"
              :class="activeChannelId === Number(row.channel.id) ? 'channel-active' : 'op-60 hover:bg-gray-500/8 hover:op-100'"
              @click="activeChannelId = Number(row.channel.id)"
            >
              {{ row.channel.name }}
            </button>
          </div>
          <div class="min-w-0 flex-1 overflow-y-auto pr-4px">
            <div class="flex-col gap-4px">
              <div
                v-for="key in activeChannelKeys"
                :key="key.id"
                class="flex items-center gap-10px border border-gray-500/20 rounded-10px px-10px py-6px hover:bg-gray-500/5"
                :class="{ 'op-50': key.status !== 1 }"
              >
                <input
                  v-if="editingKeyId === key.id"
                  type="number"
                  min="1"
                  :value="key.priority"
                  class="prio-input h-22px w-34px border border-primary rounded-6px bg-transparent text-center text-12px font-medium focus:outline-none"
                  @blur="finishEditKeyPriority(key, $event)"
                  @keydown.enter="($event.target as HTMLInputElement).blur()"
                  @keydown.escape="editingKeyId = null"
                />
                <div
                  v-else
                  class="h-22px w-22px flex shrink-0 cursor-pointer items-center justify-center rounded-6px bg-gray-500/10 text-12px font-medium op-70 hover:bg-gray-500/20"
                  title="点击编辑优先级（数字小者先选）"
                  @click="editingKeyId = key.id ?? null"
                >
                  {{ key.priority ?? 0 }}
                </div>
                <span class="min-w-0 flex-1 truncate text-12px font-mono op-80">{{ key.credential }}</span>
                <NTag v-if="key.status !== 1" size="tiny">停用</NTag>
                <span class="shrink-0 text-11px op-50">{{ channelName(key.channelId) }} · 权重 {{ key.weight ?? 0 }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="w-full flex flex-wrap items-center justify-between gap-12px">
        <div class="flex items-center gap-12px">
          <span class="whitespace-nowrap text-12px op-60">
            当前模式:
            <span class="ml-2px font-medium op-90">{{ activeTab === 'provider' ? '提供商优先' : 'Key 优先' }}</span>
          </span>
          <div class="flex items-center gap-6px border-l border-gray-500/20 pl-12px">
            <span class="text-12px op-60">调度:</span>
            <div class="flex gap-2px rounded-8px bg-gray-500/10 p-2px">
              <NTooltip v-for="opt in SCHEDULING_OPTIONS" :key="opt.value">
                <template #trigger>
                  <NButton
                    size="tiny"
                    :quaternary="schedulingMode !== opt.value"
                    :type="schedulingMode === opt.value ? 'primary' : 'default'"
                    @click="schedulingMode = opt.value"
                  >
                    {{ opt.label }}
                  </NButton>
                </template>
                {{ opt.tip }}
              </NTooltip>
            </div>
          </div>
        </div>
        <NSpace :size="8">
          <NButton @click="show = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="save">保存</NButton>
        </NSpace>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.prio-input {
  appearance: textfield;
}

.channel-active {
  background: rgba(100, 108, 255, 0.12);
  opacity: 1;
}
</style>
