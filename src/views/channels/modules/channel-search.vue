<!-- 提供商搜索卡（soybean 官方范式：独立 NCard 内联表单 + 筛选/重置·对齐 projects 页）。 -->
<script setup lang="ts">
defineOptions({ name: 'ChannelSearch' });

const keyword = defineModel<string>('keyword', { default: '' });
const status = defineModel<'all' | '1' | '0'>('status', { default: 'all' });
const protocol = defineModel<string>('protocol', { default: 'all' });
const model = defineModel<string>('model', { default: 'all' });

defineProps<{
  protocolOptions: { label: string; value: string }[];
  modelOptions: { label: string; value: string }[];
}>();

const emit = defineEmits<{ reset: [] }>();

const STATUS_OPTIONS = [
  { label: '全部状态', value: 'all' },
  { label: '活跃', value: '1' },
  { label: '停用', value: '0' }
];

function reset() {
  keyword.value = '';
  status.value = 'all';
  protocol.value = 'all';
  model.value = 'all';
  emit('reset');
}
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <NForm inline label-placement="left" :show-feedback="false">
      <NFormItem label="模糊搜索" class="pr-16px">
        <NInput v-model:value="keyword" clearable placeholder="按名称/备注/地址搜索" class="w-220px" :spellcheck="false" />
      </NFormItem>
      <NFormItem label="状态" class="pr-16px">
        <NSelect v-model:value="status" :options="STATUS_OPTIONS" class="w-120px" />
      </NFormItem>
      <NFormItem label="协议" class="pr-16px">
        <NSelect v-model:value="protocol" :options="protocolOptions" class="w-150px" />
      </NFormItem>
      <NFormItem label="模型" class="pr-16px">
        <NSelect v-model:value="model" :options="modelOptions" filterable class="w-180px" />
      </NFormItem>
      <NFormItem>
        <NButton quaternary @click="reset">
          <template #icon>
            <SvgIcon icon="ic:round-refresh" />
          </template>
          重置
        </NButton>
      </NFormItem>
    </NForm>
  </NCard>
</template>
