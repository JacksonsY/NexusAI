<script setup lang="tsx">
import { computed, ref, watch } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NButton, NPopconfirm, NSwitch, NTag } from 'naive-ui';
import { fetchAbilities, fetchDeleteAbility, fetchSaveAbility, fetchTestChannel, fetchUpdateChannel } from '@/service/api';
import UpstreamModelsModal from './upstream-models-modal.vue';

defineOptions({ name: 'ChannelDetailDrawer' });

/** 渠道详情抽屉（借鉴 Aether ProviderDetailDrawer 心智·渠道密钥视角）：概览(含 Key 指纹) / 模型路由 / 覆盖配置。 */
const props = defineProps<{
  summary: NexusAI.ChannelSummary | null;
}>();

const show = defineModel<boolean>('show', { required: true });
const emit = defineEmits<{ refresh: []; edit: [row: NexusAI.Channel] }>();

const channel = computed(() => props.summary?.channel ?? null);
const activeTab = ref('overview');
/** 从上游拉取模型弹窗（new-api FetchUpstreamModels） */
const upstreamShow = ref(false);
const testing = ref(false);

function num(value?: number | string | null): number {
  const n = Number(value);
  return Number.isNaN(n) ? 0 : n;
}

async function testChannel() {
  if (!channel.value?.id) return;
  testing.value = true;
  try {
    const { data } = await fetchTestChannel(channel.value.id);
    if (data?.ok) {
      window.$message?.success(`连通正常 · ${data.latencyMs}ms · HTTP ${data.httpStatus}`);
    } else {
      window.$message?.warning(`${data?.message ?? '探测失败'}${data?.httpStatus ? ` · HTTP ${data.httpStatus}` : ''}`);
    }
  } finally {
    testing.value = false;
  }
}

// ===== 模型路由 Tab =====
const abilities = ref<NexusAI.Ability[]>([]);
const abilitiesLoading = ref(false);
const newModel = ref('');
const addingModel = ref(false);

async function loadAbilities() {
  if (!channel.value?.id) return;
  abilitiesLoading.value = true;
  try {
    const { data, error } = await fetchAbilities({ channelId: channel.value.id });
    if (!error) abilities.value = data ?? [];
  } finally {
    abilitiesLoading.value = false;
  }
}

async function addModel() {
  if (!channel.value?.id || !newModel.value.trim()) return;
  addingModel.value = true;
  try {
    const { error } = await fetchSaveAbility({
      groupCode: 'default',
      model: newModel.value.trim(),
      channelId: channel.value.id,
      priority: 5,
      weight: 10,
      enabled: true,
      modality: 'CHAT'
    });
    if (!error) {
      window.$message?.success('模型已注册到该渠道');
      newModel.value = '';
      loadAbilities();
      emit('refresh');
    }
  } finally {
    addingModel.value = false;
  }
}

async function toggleAbility(row: NexusAI.Ability, enabled: boolean) {
  const { error } = await fetchSaveAbility({ ...row, enabled });
  if (!error) {
    window.$message?.success(enabled ? '已启用' : '已停用');
    loadAbilities();
    emit('refresh');
  }
}

async function removeAbility(row: NexusAI.Ability) {
  const { error } = await fetchDeleteAbility({
    groupCode: row.groupCode ?? 'default',
    model: row.model!,
    channelId: row.channelId!
  });
  if (!error) {
    window.$message?.success('已移除');
    loadAbilities();
    emit('refresh');
  }
}

const abilityColumns: DataTableColumns<NexusAI.Ability> = [
  { title: '模型', key: 'model', render: r => <span class="font-mono text-12px">{r.model}</span> },
  { title: '能力', key: 'modality', width: 80, render: r => <NTag size="tiny">{r.modality}</NTag> },
  { title: '优先级', key: 'priority', width: 70, align: 'center' },
  { title: '权重', key: 'weight', width: 64, align: 'center' },
  {
    title: '启用',
    key: 'enabled',
    width: 70,
    align: 'center',
    render: r => <NSwitch size="small" value={r.enabled === true} onUpdateValue={(v: boolean) => toggleAbility(r, v)} />
  },
  {
    title: '操作',
    key: 'actions',
    width: 70,
    align: 'center',
    render: r => (
      <NPopconfirm onPositiveClick={() => removeAbility(r)}>
        {{
          trigger: () => (
            <NButton size="tiny" type="error" quaternary>
              移除
            </NButton>
          ),
          default: () => '从该渠道移除此模型路由？'
        }}
      </NPopconfirm>
    )
  }
];

// ===== 覆盖配置 Tab（JSON 直编） =====
const overrideForm = ref<{ modelMapping?: string; paramOverride?: string; headerOverride?: string; setting?: string }>({});
const savingOverride = ref(false);

async function saveOverride() {
  if (!channel.value?.id) return;
  savingOverride.value = true;
  try {
    const { error } = await fetchUpdateChannel(channel.value.id, { ...overrideForm.value });
    if (!error) {
      window.$message?.success('覆盖配置已保存，缓存已失效');
      emit('refresh');
    }
  } finally {
    savingOverride.value = false;
  }
}

watch([show, () => channel.value?.id], ([visible]) => {
  if (!visible || !channel.value) return;
  activeTab.value = 'overview';
  overrideForm.value = {
    modelMapping: channel.value.modelMapping,
    paramOverride: channel.value.paramOverride,
    headerOverride: channel.value.headerOverride,
    setting: channel.value.setting
  };
  loadAbilities();
});

/** 标签数组（展示用） */
const tagList = computed<string[]>(() => {
  try {
    const v = JSON.parse(channel.value?.tags || '[]');
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
});
</script>

<template>
  <NDrawer v-model:show="show" :width="720" display-directive="if">
    <NDrawerContent closable>
      <template #header>
        <div class="flex items-center gap-10px">
          <span>{{ channel?.name ?? '渠道详情' }}</span>
          <NTag size="small" class="font-mono">{{ channel?.protocol }}</NTag>
          <NTag size="small" :type="channel?.status === 1 ? 'success' : 'warning'">
            {{ channel?.status === 1 ? '活跃' : '停用' }}
          </NTag>
        </div>
      </template>

      <NTabs v-model:value="activeTab" type="line" animated>
        <!-- 概览 -->
        <NTabPane name="overview" tab="概览">
          <div class="flex-col gap-16px">
            <div class="grid grid-cols-2 gap-12px sm:grid-cols-4">
              <div class="border border-gray-500/20 rounded-10px px-12px py-10px">
                <div class="text-12px op-60">近 24h 请求</div>
                <div class="mt-4px text-18px font-semibold tabular-nums">{{ num(summary?.requests24h) }}</div>
              </div>
              <div class="border border-gray-500/20 rounded-10px px-12px py-10px">
                <div class="text-12px op-60">近 24h 成功率</div>
                <div class="mt-4px text-18px font-semibold tabular-nums">
                  {{ summary?.successRate24h == null ? '-' : `${(summary.successRate24h * 100).toFixed(2)}%` }}
                </div>
              </div>
              <div class="border border-gray-500/20 rounded-10px px-12px py-10px">
                <div class="text-12px op-60">注册模型</div>
                <div class="mt-4px text-18px font-semibold tabular-nums">{{ num(summary?.activeModels) }}/{{ num(summary?.totalModels) }}</div>
              </div>
              <div class="border border-gray-500/20 rounded-10px px-12px py-10px">
                <div class="text-12px op-60">运行时健康分</div>
                <div class="mt-4px text-18px font-semibold tabular-nums">{{ summary?.healthScore ?? 100 }}</div>
              </div>
            </div>

            <NDescriptions label-placement="left" :column="1" bordered size="small">
              <NDescriptionsItem label="备注">{{ channel?.description || '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="Base URL">
                <span class="font-mono text-12px">{{ channel?.baseUrl }}</span>
              </NDescriptionsItem>
              <NDescriptionsItem label="API Key">
                <span class="font-mono text-12px">{{ summary?.maskedCredential ?? '未配置' }}</span>
                <span class="ml-8px text-11px op-50">编辑渠道可替换（AES-256-GCM 加密存储）</span>
              </NDescriptionsItem>
              <NDescriptionsItem label="厂商">{{ channel?.vendor || '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="鉴权方式">{{ channel?.authType }}</NDescriptionsItem>
              <NDescriptionsItem label="权重 / 优先级">{{ channel?.weight }} / {{ channel?.priority }}</NDescriptionsItem>
              <NDescriptionsItem label="标签">
                <NSpace v-if="tagList.length" :size="4">
                  <NTag v-for="tag in tagList" :key="tag" size="tiny">{{ tag }}</NTag>
                </NSpace>
                <span v-else>-</span>
              </NDescriptionsItem>
            </NDescriptions>

            <NSpace>
              <NButton size="small" :loading="testing" @click="testChannel">
                <template #icon>
                  <SvgIcon icon="lucide:radio" />
                </template>
                测速探活
              </NButton>
              <NButton size="small" @click="channel && emit('edit', channel)">
                <template #icon>
                  <SvgIcon icon="lucide:pencil" />
                </template>
                编辑配置
              </NButton>
            </NSpace>
          </div>
        </NTabPane>

        <!-- 模型路由 -->
        <NTabPane name="models" tab="模型路由">
          <div class="flex-col gap-12px">
            <div class="flex items-center gap-8px">
              <NInput v-model:value="newModel" placeholder="模型名（如 gpt-4o）注册到该渠道" :spellcheck="false" class="flex-1" />
              <NButton type="primary" :loading="addingModel" :disabled="!newModel.trim()" @click="addModel">注册模型</NButton>
              <NButton @click="upstreamShow = true">
                <template #icon>
                  <SvgIcon icon="lucide:cloud-download" />
                </template>
                从上游拉取
              </NButton>
            </div>
            <NDataTable
              :columns="abilityColumns"
              :data="abilities"
              :loading="abilitiesLoading"
              size="small"
              :bordered="true"
              :max-height="420"
              :row-key="r => `${r.model}-${r.channelId}`"
            />
            <p class="m-0 text-12px op-60">模型路由 = 能力表（调度第一级：模型 → 渠道集合·优先级/权重选路）。</p>
          </div>
        </NTabPane>

        <!-- 覆盖配置 -->
        <NTabPane name="override" tab="覆盖配置">
          <div class="flex-col gap-12px">
            <NForm label-placement="top">
              <NFormItem label="模型映射 modelMapping">
                <NInput v-model:value="overrideForm.modelMapping" type="textarea" :rows="3" placeholder='{"gpt-4o":"gpt-4o-2024-11-20"}' :spellcheck="false" />
              </NFormItem>
              <NFormItem label="参数覆盖 paramOverride">
                <NInput v-model:value="overrideForm.paramOverride" type="textarea" :rows="3" placeholder='{"temperature":0.7}' :spellcheck="false" />
              </NFormItem>
              <NFormItem label="请求头覆盖 headerOverride">
                <NInput v-model:value="overrideForm.headerOverride" type="textarea" :rows="3" placeholder='{"X-Region":"us-east-1"}' :spellcheck="false" />
              </NFormItem>
              <NFormItem label="高级设置 setting">
                <NInput v-model:value="overrideForm.setting" type="textarea" :rows="3" placeholder='{"canonical":true}' :spellcheck="false" />
              </NFormItem>
            </NForm>
            <NSpace justify="end">
              <NButton type="primary" :loading="savingOverride" @click="saveOverride">保存并刷新缓存</NButton>
            </NSpace>
            <p class="m-0 text-12px op-60">渠道间差异的唯一受控覆盖手段（代码式适配·无规则引擎）；保存后当前节点缓存立即失效。</p>
          </div>
        </NTabPane>
      </NTabs>

      <UpstreamModelsModal
        v-model:show="upstreamShow"
        :channel-id="channel?.id ?? null"
        :channel-name="channel?.name"
        @success="
          loadAbilities();
          emit('refresh');
        "
      />
    </NDrawerContent>
  </NDrawer>
</template>
