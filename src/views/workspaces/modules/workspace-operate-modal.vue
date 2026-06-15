<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { NButton, NFormItem, NInput, NModal, NSelect, NSwitch } from 'naive-ui';
import { fetchUpsertWorkspace } from '@/service/api';

/**
 * 项目内工作区创建/编辑弹窗：Upsert 幂等键 (projectCode, externalId)——编辑时两者禁改；
 * 钱包额度不在此处改（走充值弹窗·防双写竞态）。
 */
defineOptions({ name: 'WorkspaceOperateModal' });

const props = defineProps<{
  editing: NexusAI.Workspace | null;
  projectOptions: { label: string; value: string }[];
}>();
const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ saved: [] }>();

const form = reactive<NexusAI.Workspace>({ projectCode: '', externalId: '', displayName: '', unlimited: false });
const title = computed(() => (props.editing ? '编辑工作区' : '新建工作区'));

watch(show, v => {
  if (!v) {
    return;
  }
  if (props.editing) {
    Object.assign(form, {
      projectCode: props.editing.projectCode,
      externalId: props.editing.externalId,
      displayName: props.editing.displayName ?? '',
      unlimited: Boolean(props.editing.unlimited)
    });
  } else {
    Object.assign(form, { projectCode: props.projectOptions[0]?.value ?? '', externalId: '', displayName: '', unlimited: false });
  }
});

async function submit() {
  if (!form.projectCode || !form.externalId?.trim()) {
    window.$message?.warning('项目与工作区标识必填');
    return;
  }
  const { error } = await fetchUpsertWorkspace({
    projectCode: form.projectCode,
    externalId: form.externalId.trim(),
    displayName: form.displayName?.trim() || undefined,
    unlimited: form.unlimited
  });
  if (!error) {
    window.$message?.success(props.editing ? '工作区已更新' : '工作区已创建');
    show.value = false;
    emit('saved');
  }
}
</script>

<template>
  <NModal v-model:show="show" preset="card" :title="title" class="w-440px" :bordered="false" size="small">
    <div class="flex flex-col gap-4px">
      <NFormItem label="归属项目" :show-feedback="false" class="mb-8px">
        <NSelect v-model:value="form.projectCode" :options="projectOptions" :disabled="!!editing" placeholder="选择项目" />
      </NFormItem>
      <NFormItem label="工作区标识 externalId" :show-feedback="false" class="mb-8px">
        <NInput
          v-model:value="form.externalId"
          :disabled="!!editing"
          :spellcheck="false"
          placeholder="业务面工作区标识（项目内唯一·幂等键）"
        />
      </NFormItem>
      <NFormItem label="展示名（可选）" :show-feedback="false" class="mb-8px">
        <NInput v-model:value="form.displayName" placeholder="如 张三" />
      </NFormItem>
      <NFormItem label="额度模式" :show-feedback="false" class="mb-8px">
        <div class="w-full">
          <NSwitch v-model:value="form.unlimited">
            <template #checked>不限额</template>
            <template #unchecked>钱包</template>
          </NSwitch>
          <div class="mt-4px text-12px op-60">钱包模式余额用尽即拒；充值经列表「充值」操作（契约③ workspace 维度）</div>
        </div>
      </NFormItem>
      <div class="flex justify-end gap-8px">
        <NButton size="small" @click="show = false">取消</NButton>
        <NButton size="small" type="primary" @click="submit">{{ editing ? '保存' : '创建' }}</NButton>
      </div>
    </div>
  </NModal>
</template>
