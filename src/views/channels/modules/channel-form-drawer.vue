<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchAddChannel, fetchUpdateChannel } from '@/service/api';
import { useNaiveForm } from '@/hooks/common/form';

defineOptions({ name: 'ChannelFormDrawer' });

/** 渠道表单抽屉（新增/编辑·渠道=配置档③：协议家族选代码适配器 + 扁平覆盖字段直编）。 */
const props = defineProps<{
  /** 编辑行（null=新增） */
  row: NexusAI.Channel | null;
}>();

const show = defineModel<boolean>('show', { required: true });
const emit = defineEmits<{ saved: [] }>();

const { formRef, validate, restoreValidation } = useNaiveForm();
const saving = ref(false);
const model = ref<NexusAI.Channel>({});

const PROTOCOL_OPTIONS = ['openai', 'openai-compatible', 'anthropic', 'gemini', 'azure', 'bedrock', 'vertex'].map(v => ({
  label: v,
  value: v
}));
const AUTH_OPTIONS = ['bearer', 'x-api-key', 'query', 'aws-sigv4', 'gcp-oauth'].map(v => ({ label: v, value: v }));
const STATUS_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 }
];
const BILLING_OPTIONS = [
  { label: '按量付费', value: 'pay_as_you_go' },
  { label: '月卡额度', value: 'monthly_quota' },
  { label: '免费套餐', value: 'free_tier' }
];

/** 月卡额度（后端 BigDecimal 序列化可能为 string·与 NInputNumber 双向适配） */
const quotaUsdNum = computed<number | null>({
  get: () => {
    const v = model.value.quotaUsd;
    return v === undefined || v === null || v === '' ? null : Number(v);
  },
  set: v => {
    model.value.quotaUsd = v ?? undefined;
  }
});

/** 毫秒时间戳 ↔ NDatePicker（0/空 = 未设置） */
function tsModel(field: 'quotaStartAt' | 'quotaExpiresAt') {
  return computed<number | null>({
    get: () => {
      const v = Number(model.value[field] ?? 0);
      return v > 0 ? v : null;
    },
    set: v => {
      model.value[field] = v ?? 0;
    }
  });
}
const quotaStartAtTs = tsModel('quotaStartAt');
const quotaExpiresAtTs = tsModel('quotaExpiresAt');

const rules = {
  name: [{ required: true, message: '请输入渠道名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
  credential: [{ required: true, message: '请粘贴上游 API Key', trigger: 'blur' }]
};

/** 渠道标签（JSON 串 ↔ 数组·供项目访问策略按标签圈渠道） */
const tagsArr = computed<string[]>({
  get: () => {
    try {
      const v = JSON.parse(model.value.tags || '[]');
      return Array.isArray(v) ? v : [];
    } catch {
      return [];
    }
  },
  set: v => {
    model.value.tags = v.length ? JSON.stringify(v) : '';
  }
});

watch(show, visible => {
  if (!visible) return;
  model.value = props.row
    ? { ...props.row, credential: '' }
    : { protocol: 'openai', authType: 'bearer', status: 1, weight: 10, priority: 1, billingType: 'pay_as_you_go' };
  restoreValidation();
});

async function save() {
  await validate();
  saving.value = true;
  try {
    const m = model.value;
    const { error } = m.id ? await fetchUpdateChannel(m.id, m) : await fetchAddChannel(m);
    if (!error) {
      window.$message?.success(m.id ? '渠道已更新' : '渠道已创建，请在详情中继续添加凭证与模型');
      show.value = false;
      emit('saved');
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <NDrawer v-model:show="show" :width="560" display-directive="if">
    <NDrawerContent :title="row?.id ? '编辑渠道' : '新增渠道'" closable>
      <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100">
        <NFormItem label="名称" path="name">
          <NInput v-model:value="model.name" placeholder="如 OpenAI 官方直连" :spellcheck="false" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="model.description" placeholder="用途说明（列表行内可改）" :spellcheck="false" />
        </NFormItem>
        <NFormItem label="主站链接">
          <NInput v-model:value="model.website" placeholder="https://platform.openai.com（名称旁外链）" :spellcheck="false" />
        </NFormItem>
        <NFormItem label="协议家族">
          <NSelect v-model:value="model.protocol" :options="PROTOCOL_OPTIONS" />
        </NFormItem>
        <NFormItem label="厂商">
          <NInput v-model:value="model.vendor" placeholder="如 openai / anthropic（可空）" :spellcheck="false" />
        </NFormItem>
        <NFormItem label="Base URL" path="baseUrl">
          <NInput v-model:value="model.baseUrl" placeholder="https://api.openai.com" :spellcheck="false" />
        </NFormItem>
        <NFormItem label="API Key" :path="row?.id ? undefined : 'credential'">
          <NInput
            v-model:value="model.credential"
            type="password"
            show-password-on="click"
            :input-props="{ autocomplete: 'new-password' }"
            :placeholder="row?.id ? '留空不修改' : '粘贴上游 API Key（AES-256-GCM 加密存储）'"
            :spellcheck="false"
          />
        </NFormItem>
        <NFormItem label="鉴权方式">
          <NSelect v-model:value="model.authType" :options="AUTH_OPTIONS" />
        </NFormItem>
        <NFormItem label="权重">
          <NInputNumber v-model:value="model.weight" class="w-full" :min="0" />
        </NFormItem>
        <NFormItem label="优先级">
          <NInputNumber v-model:value="model.priority" class="w-full" :min="0" />
        </NFormItem>
        <NFormItem label="标签">
          <NDynamicTags v-model:value="tagsArr" />
        </NFormItem>
        <NFormItem label="状态">
          <NSelect v-model:value="model.status" :options="STATUS_OPTIONS" />
        </NFormItem>
        <NDivider title-placement="left" class="!text-12px !op-60">计费与限流（借鉴 Aether 提供商表单）</NDivider>
        <NFormItem label="计费类型">
          <NSelect v-model:value="model.billingType" :options="BILLING_OPTIONS" />
        </NFormItem>
        <template v-if="model.billingType === 'monthly_quota'">
          <NFormItem label="周期额度">
            <NInputNumber v-model:value="quotaUsdNum" class="w-full" :min="0" :precision="2">
              <template #suffix>USD</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="重置周期">
            <NInputNumber v-model:value="model.quotaResetDays" class="w-full" :min="1" :max="365" placeholder="30">
              <template #suffix>天</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="周期开始">
            <NDatePicker v-model:value="quotaStartAtTs" type="datetime" class="w-full" clearable placeholder="本周期开始时间" />
          </NFormItem>
        </template>
        <NFormItem label="过期时间">
          <NDatePicker v-model:value="quotaExpiresAtTs" type="datetime" class="w-full" clearable placeholder="留空=永不过期" />
        </NFormItem>
        <NFormItem label="首字节超时">
          <NInputNumber v-model:value="model.firstByteTimeout" class="w-full" :min="0" :max="300" placeholder="0 = 全局默认">
            <template #suffix>秒</template>
          </NInputNumber>
        </NFormItem>
        <NFormItem label="请求超时">
          <NInputNumber v-model:value="model.requestTimeout" class="w-full" :min="0" :max="600" placeholder="0 = 不限（仅非流式生效）">
            <template #suffix>秒</template>
          </NInputNumber>
        </NFormItem>
        <NDivider title-placement="left" class="!text-12px !op-60">覆盖配置（渠道间差异·JSON 直编）</NDivider>
        <NFormItem label="模型映射">
          <NInput v-model:value="model.modelMapping" type="textarea" :rows="2" placeholder='{"gpt-4o":"gpt-4o-2024-11-20"}' :spellcheck="false" />
        </NFormItem>
        <NFormItem label="参数覆盖">
          <NInput v-model:value="model.paramOverride" type="textarea" :rows="2" placeholder='{"temperature":0.7}' :spellcheck="false" />
        </NFormItem>
        <NFormItem label="请求头覆盖">
          <NInput v-model:value="model.headerOverride" type="textarea" :rows="2" placeholder='{"X-Region":"us-east-1"}' :spellcheck="false" />
        </NFormItem>
        <NFormItem label="高级设置">
          <NInput v-model:value="model.setting" type="textarea" :rows="2" placeholder='{"canonical":true}' :spellcheck="false" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="show = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="save">保存</NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
