<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchModelDetail } from '@/service/api';
import { fmtTokens, fmtUsageCount, num } from '../composables/use-model-format';
import ModelRoutingTab from './model-routing-tab.vue';
import ModelMappingsTab from './model-mappings-tab.vue';

defineOptions({ name: 'ModelDetailDrawer' });

interface Props {
  row: NexusAI.ModelRow | null;
}

const props = defineProps<Props>();
const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{
  edit: [row: NexusAI.ModelRow];
  toggleStatus: [row: NexusAI.ModelRow];
  assignChannels: [modelKey: string];
  refresh: [];
}>();

const tab = ref<'basic' | 'routing' | 'mappings'>('basic');
const detail = ref<NexusAI.ModelRow | null>(null);

const current = computed(() => detail.value ?? props.row);
const model = computed(() => current.value?.model);

watch(show, async open => {
  if (!open) {
    detail.value = null;
    return;
  }
  tab.value = 'basic';
  if (props.row?.model.id) {
    const { data } = await fetchModelDetail(props.row.model.id);
    if (data) detail.value = data;
  }
});

function copyKey() {
  if (!model.value?.modelKey) return;
  navigator.clipboard?.writeText(model.value.modelKey);
  window.$message?.success('已复制模型 ID');
}

function fmtDate(value?: string | null) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 16);
}

function fmtRate(value?: number | string | null, digits = 2) {
  const n = num(value);
  return n === null || n === 0 ? '-' : `$${n.toFixed(digits)}`;
}

async function reloadDetail() {
  if (props.row?.model.id) {
    const { data } = await fetchModelDetail(props.row.model.id);
    if (data) detail.value = data;
  }
  emit('refresh');
}
</script>

<template>
  <NDrawer v-model:show="show" :width="700" class="max-w-[94vw]">
    <NDrawerContent v-if="model" closable :native-scrollbar="false">
      <template #header>
        <div class="flex items-center justify-between gap-12px">
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-8px min-w-0">
              <span class="text-16px font-bold truncate">{{ model.modelAlias || model.modelKey }}</span>
              <NTag size="small" :type="model.status === 1 ? 'success' : 'default'">
                {{ model.status === 1 ? '活跃' : '停用' }}
              </NTag>
              <span class="font-mono text-12px text-gray-400 truncate">{{ model.modelKey }}</span>
              <NButton text size="tiny" title="复制模型 ID" @click="copyKey">
                <SvgIcon icon="lucide:copy" class="text-12px" />
              </NButton>
            </div>
            <div v-if="model.description" class="mt-2px text-12px font-normal text-gray-400 truncate">
              {{ model.description }}
            </div>
          </div>
          <NSpace :size="4" class="shrink-0">
            <NButton quaternary circle size="small" title="编辑模型" @click="emit('edit', current!)">
              <SvgIcon icon="lucide:pencil" />
            </NButton>
            <NButton
              quaternary
              circle
              size="small"
              :title="model.status === 1 ? '点击停用' : '点击启用'"
              @click="emit('toggleStatus', current!)"
            >
              <SvgIcon icon="lucide:power" />
            </NButton>
          </NSpace>
        </div>
      </template>

      <NTabs v-model:value="tab" type="segment" size="small" animated>
        <!-- Tab 1: 基本信息 -->
        <NTabPane name="basic" tab="基本信息">
          <div class="space-y-20px pt-8px">
            <div class="space-y-8px">
              <h4 class="text-13px font-semibold">基础属性</h4>
              <div class="grid grid-cols-2 gap-8px sm:grid-cols-4">
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">创建时间</div>
                  <div class="mt-2px text-13px">{{ fmtDate(model.createDt) }}</div>
                </div>
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">模态</div>
                  <div class="mt-2px text-13px">{{ model.modality || '-' }}</div>
                </div>
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">上下文窗口</div>
                  <div class="mt-2px text-13px font-mono">{{ fmtTokens(model.contextLength) }}</div>
                </div>
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">最大输出</div>
                  <div class="mt-2px text-13px font-mono">{{ fmtTokens(model.maxOutputTokens) }}</div>
                </div>
              </div>
            </div>

            <div class="space-y-8px">
              <h4 class="text-13px font-semibold">默认定价</h4>
              <div class="grid grid-cols-2 gap-8px">
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">输入价格 ($/M)</div>
                  <div class="mt-2px text-16px font-semibold">{{ fmtRate(model.inputRate) }}</div>
                </div>
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">输出价格 ($/M)</div>
                  <div class="mt-2px text-16px font-semibold">{{ fmtRate(model.outputRate) }}</div>
                </div>
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">缓存读取 ($/M)</div>
                  <div class="mt-2px text-13px font-mono">{{ fmtRate(model.cachedInputRate) }}</div>
                </div>
                <div class="rounded-8px border border-gray-200 p-10px dark:border-gray-700">
                  <div class="text-11px text-gray-400">按次 / 按图</div>
                  <div class="mt-2px text-13px font-mono">
                    {{ fmtRate(model.perRequestFee, 3) }} / {{ fmtRate(model.perImageFee, 3) }}
                  </div>
                </div>
              </div>
            </div>

            <div class="space-y-8px">
              <h4 class="text-13px font-semibold">统计信息</h4>
              <div class="grid grid-cols-2 gap-8px">
                <div class="rounded-8px border border-gray-200 bg-gray-50/50 p-12px dark:border-gray-700 dark:bg-gray-800/40">
                  <div class="flex items-center justify-between">
                    <span class="text-11px text-gray-400">关联提供商</span>
                    <SvgIcon icon="lucide:building-2" class="text-gray-400" />
                  </div>
                  <div class="mt-4px text-22px font-bold">
                    {{ num(current?.activeChannelCount) ?? 0 }}
                    <span class="text-13px font-normal text-gray-400">/ {{ num(current?.channelCount) ?? 0 }}</span>
                  </div>
                </div>
                <div class="rounded-8px border border-gray-200 bg-gray-50/50 p-12px dark:border-gray-700 dark:bg-gray-800/40">
                  <div class="flex items-center justify-between">
                    <span class="text-11px text-gray-400">调用次数（30 天）</span>
                    <SvgIcon icon="lucide:bar-chart-3" class="text-gray-400" />
                  </div>
                  <div class="mt-4px text-22px font-bold">{{ fmtUsageCount(current?.usageCount) }}</div>
                </div>
              </div>
            </div>
          </div>
        </NTabPane>

        <!-- Tab 2: 链路控制 -->
        <NTabPane name="routing" tab="链路控制">
          <ModelRoutingTab
            v-if="model.modelKey"
            :model-key="model.modelKey"
            :active="tab === 'routing' && show"
            @assign-channels="emit('assignChannels', model.modelKey!)"
          />
        </NTabPane>

        <!-- Tab 3: 模型映射 -->
        <NTabPane name="mappings" tab="模型映射">
          <ModelMappingsTab v-if="model" :model="model" @saved="reloadDetail" />
        </NTabPane>
      </NTabs>
    </NDrawerContent>
  </NDrawer>
</template>
