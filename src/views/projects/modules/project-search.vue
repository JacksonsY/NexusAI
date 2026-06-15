<!-- 项目搜索卡（soybean 官方范式：独立 NCard 内联表单 + 搜索/重置）。 -->
<script setup lang="ts">
defineOptions({ name: 'ProjectSearch' });

const keyword = defineModel<string>('keyword', { default: '' });

const emit = defineEmits<{ reset: [] }>();

function reset() {
  keyword.value = '';
  emit('reset');
}
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <NForm inline label-placement="left" :show-feedback="false">
      <NFormItem label="模糊搜索" class="pr-24px">
        <NInput v-model:value="keyword" clearable placeholder="按名称/标识搜索项目" class="w-240px" @keyup.enter="() => {}" />
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
