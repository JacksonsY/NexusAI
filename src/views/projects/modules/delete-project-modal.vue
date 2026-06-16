<!-- 删除项目弹窗（DeleteProjectDialog 流程：红色警示 + 必须输入项目名称才能确认删除）。 -->
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchDeleteProject } from '@/service/api';

defineOptions({ name: 'DeleteProjectModal' });

const show = defineModel<boolean>('show', { default: false });

const props = defineProps<{ project: NexusAI.Project | null }>();

const emit = defineEmits<{ deleted: [] }>();

const confirmName = ref('');
const deleting = ref(false);

watch(show, v => {
  if (v) {
    confirmName.value = '';
  }
});

const canDelete = computed(() => !!props.project && confirmName.value.trim() === props.project.name);

async function doDelete() {
  if (!canDelete.value || !props.project) {
    return;
  }
  deleting.value = true;
  const { error } = await fetchDeleteProject(props.project.id!);
  deleting.value = false;
  if (!error) {
    window.$message?.success('项目已删除');
    show.value = false;
    emit('deleted');
  }
}
</script>

<template>
  <NModal v-model:show="show" preset="card" class="w-520px">
    <template #header>
      <span class="flex items-center gap-6px" :style="{ color: '#d03050' }">
        <SvgIcon icon="lucide:triangle-alert" class="text-18px" />
        删除项目
      </span>
    </template>
    <div class="flex flex-col gap-16px">
      <NAlert type="error" title="警告" :show-icon="true">
        此操作无法撤销。项目的 M2M 接入凭证将立即失效，使用该凭证的接入方将无法再调用契约端点。
      </NAlert>
      <div class="flex flex-col gap-8px">
        <span class="text-14px">
          请输入项目名称
          <strong>{{ project?.name }}</strong>
          以确认删除：
        </span>
        <NInput v-model:value="confirmName" :placeholder="project?.name" />
      </div>
    </div>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="show = false">取消</NButton>
        <NButton type="error" :disabled="!canDelete" :loading="deleting" @click="doDelete">
          {{ deleting ? '删除中...' : '删除项目' }}
        </NButton>
      </NSpace>
    </template>
  </NModal>
</template>
