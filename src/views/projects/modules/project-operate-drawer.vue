<!-- 项目创建/编辑抽屉（soybean OperateDrawer 范式·useNaiveForm 校验：必填星 + 失焦验证 + 提交聚焦错误）。
     projectCode 为 M2M 稳定标识编辑禁改；状态走行操作归档/激活；创建成功上抛含一次性明文令牌的完整项目。 -->
<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { fetchAddProject, fetchUpdateProject } from '@/service/api';

defineOptions({ name: 'ProjectOperateDrawer' });

const visible = defineModel<boolean>('visible', { default: false });

const props = defineProps<{
  /** null=创建；非空=编辑该项目 */
  editing: NexusAI.Project | null;
}>();

const emit = defineEmits<{
  /** 创建成功（携含明文令牌的完整项目） */
  created: [project: NexusAI.Project];
  /** 编辑保存成功 */
  saved: [];
}>();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const rules = {
  projectCode: defaultRequiredRule,
  name: defaultRequiredRule
};

const title = computed(() => (props.editing ? '编辑项目' : '新建项目'));

const form = reactive<NexusAI.Project>({ projectCode: '', name: '', description: '', unlimited: false });

watch(visible, v => {
  if (!v) {
    return;
  }
  restoreValidation();
  if (props.editing) {
    // token 不回填（脱敏值不可作更新输入·留空=不改）；status 不进表单（走归档/激活操作）；
    // quotaUsd 不进表单（额度走「充值/调整额度」流·防止编辑表单整存整取覆盖运行时余额）
    Object.assign(form, {
      ...props.editing,
      token: undefined,
      status: undefined,
      quotaUsd: undefined,
      unlimited: Boolean(props.editing.unlimited)
    });
  } else {
    Object.assign(form, { id: undefined, projectCode: '', name: '', description: '', unlimited: false });
  }
});

async function submit() {
  await validate();
  if (props.editing) {
    const { error } = await fetchUpdateProject(form.id!, form);
    if (!error) {
      window.$message?.success('项目信息已更新');
      visible.value = false;
      emit('saved');
    }
    return;
  }
  const { data, error } = await fetchAddProject(form);
  if (!error && data) {
    visible.value = false;
    emit('created', data); // data 含明文 token
  }
}
</script>

<template>
  <NDrawer v-model:show="visible" display-directive="show" :width="420">
    <NDrawerContent :title="title" :native-scrollbar="false" closable>
      <NForm ref="formRef" :model="form" :rules="rules" label-placement="top">
        <NFormItem label="项目标识" path="projectCode">
          <div class="w-full">
            <NInput
              v-model:value="form.projectCode"
              :disabled="!!editing"
              :spellcheck="false"
              placeholder="如 ruoyi-saas…"
            />
            <div class="mt-4px text-12px op-60">M2M 接入的稳定标识，创建后不可修改</div>
          </div>
        </NFormItem>
        <NFormItem label="项目名称" path="name">
          <NInput v-model:value="form.name" placeholder="如 商城后台…" />
        </NFormItem>
        <NFormItem label="项目描述" path="description">
          <NInput v-model:value="form.description" type="textarea" :rows="3" placeholder="这个项目用于…" />
        </NFormItem>
        <NFormItem label="额度模式" path="unlimited">
          <div class="w-full">
            <NSwitch v-model:value="form.unlimited">
              <template #checked>不限额</template>
              <template #unchecked>额度池</template>
            </NSwitch>
            <div class="mt-4px text-12px op-60">
              不限额：内部自用项目，闸门不扣减；额度池：余额用尽即拒，经列表「充值 / 调整额度」管理
            </div>
          </div>
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace :size="16">
          <NButton @click="visible = false">取消</NButton>
          <NButton type="primary" @click="submit">{{ editing ? '保存' : '创建' }}</NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
