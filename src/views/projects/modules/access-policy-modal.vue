<!-- 项目访问策略弹窗（Project Profiles 对话框·单策略简化）：
     允许的模型 / 允许的能力 / 允许的渠道 / 允许的渠道标签 + 匹配模式。每项留空 = 不限制。
     运行时由网关 ProjectPolicyFilter 执行（模型/能力越权 403·渠道候选硬过滤），约束项目下全部 sk- Key。 -->
<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useThemeVars } from 'naive-ui';
import { fetchChannels, fetchModels, fetchUpdateProject } from '@/service/api';

defineOptions({ name: 'AccessPolicyModal' });

const show = defineModel<boolean>('show', { default: false });

const props = defineProps<{ project: NexusAI.Project | null }>();

const emit = defineEmits<{ saved: [] }>();

const theme = useThemeVars();

const MODALITY_OPTIONS = [
  { label: '对话 chat', value: 'chat' },
  { label: '向量 embedding', value: 'embedding' },
  { label: '图像 image', value: 'image' },
  { label: '重排 rerank', value: 'rerank' }
];

const MATCH_MODE_OPTIONS = [
  { label: '包含任一标签', value: 'any' },
  { label: '包含全部标签', value: 'all' },
  { label: '不包含任一标签', value: 'none' }
];

/** 表单态（数组形式·提交时序列化为 JSON 串） */
const form = reactive({
  models: [] as string[],
  modalities: [] as string[],
  // id 一律 string（雪花 19 位超 JS 安全整数·后端 Long 已转字符串下发，转 Number 会丢精度）
  channelIds: [] as string[],
  channelTags: [] as string[],
  matchMode: 'any'
});

const modelOptions = ref<{ label: string; value: string }[]>([]);
const channelOptions = ref<{ label: string; value: string }[]>([]);

onMounted(async () => {
  const [m, c] = await Promise.all([fetchModels(), fetchChannels()]);
  const models = Array.isArray(m.data) ? m.data : (m.data?.records ?? []);
  modelOptions.value = models.map(x => ({
    label: String(x.modelKey ?? x.name ?? ''),
    value: String(x.modelKey ?? x.name ?? '')
  }));
  channelOptions.value = (c.data ?? []).map(x => ({ label: `${x.name}（#${x.id}）`, value: String(x.id) }));
});

const parseArr = (json?: string): unknown[] => {
  if (!json) {
    return [];
  }
  try {
    const v = JSON.parse(json);
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
};

watch(show, v => {
  if (!v || !props.project) {
    return;
  }
  form.models = parseArr(props.project.allowedModels).map(String);
  form.modalities = parseArr(props.project.allowedModalities).map(String);
  form.channelIds = parseArr(props.project.allowedChannelIds).map(String);
  form.channelTags = parseArr(props.project.allowedChannelTags).map(String);
  form.matchMode = props.project.channelTagsMatchMode || 'any';
});

const saving = ref(false);

/** 数组 → JSON 串；空数组传 ""（MyBatis-Plus 非 null 更新 → 落空串=清除限制）。 */
const ser = (arr: unknown[]) => (arr.length ? JSON.stringify(arr) : '');

async function save() {
  if (!props.project) {
    return;
  }
  saving.value = true;
  const { error } = await fetchUpdateProject(props.project.id!, {
    allowedModels: ser(form.models),
    allowedModalities: ser(form.modalities),
    allowedChannelIds: ser(form.channelIds),
    allowedChannelTags: ser(form.channelTags),
    channelTagsMatchMode: form.matchMode
  } as NexusAI.Project);
  saving.value = false;
  if (!error) {
    window.$message?.success('访问策略已更新（约 1 分钟内对流量生效）');
    show.value = false;
    emit('saved');
  }
}

const restricted = computed(
  () => form.models.length || form.modalities.length || form.channelIds.length || form.channelTags.length
);
</script>

<template>
  <NModal v-model:show="show" preset="card" class="w-640px" title="访问策略">
    <p class="mb-16px mt-0 text-13px" :style="{ color: theme.textColor3 }">
      管理项目「{{ project?.name }}」的模型、能力与渠道访问范围，约束其下全部访问密钥。每项留空表示不限制。
    </p>
    <NForm label-placement="top">
      <NFormItem label="允许的模型">
        <div class="w-full">
          <NSelect v-model:value="form.models" multiple filterable tag :options="modelOptions" placeholder="选择或输入模型名，留空=不限制" />
          <div class="mt-4px text-12px" :style="{ color: theme.textColor3 }">仅允许调用这些模型。可输入未登记的模型名。</div>
        </div>
      </NFormItem>
      <NFormItem label="允许的能力">
        <div class="w-full">
          <NCheckboxGroup v-model:value="form.modalities">
            <NSpace>
              <NCheckbox v-for="o in MODALITY_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
            </NSpace>
          </NCheckboxGroup>
          <div class="mt-4px text-12px" :style="{ color: theme.textColor3 }">仅允许调用勾选的能力端点。全不勾=不限制。</div>
        </div>
      </NFormItem>
      <NFormItem label="允许的渠道">
        <div class="w-full">
          <NSelect v-model:value="form.channelIds" multiple :options="channelOptions" placeholder="留空=不限制" />
          <div class="mt-4px text-12px" :style="{ color: theme.textColor3 }">仅使用这些渠道进行路由。留空表示不限制。</div>
        </div>
      </NFormItem>
      <NFormItem label="允许的渠道标签">
        <div class="w-full">
          <NDynamicTags v-model:value="form.channelTags" />
          <div class="mt-4px text-12px" :style="{ color: theme.textColor3 }">仅使用匹配标签的渠道（标签在渠道管理页维护）。</div>
        </div>
      </NFormItem>
      <NFormItem v-if="form.channelTags.length" label="标签匹配模式">
        <NRadioGroup v-model:value="form.matchMode">
          <NSpace>
            <NRadio v-for="o in MATCH_MODE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>
    </NForm>
    <NAlert v-if="!restricted" type="info" :show-icon="true">当前未设置任何限制，项目下的访问密钥可访问全部模型、能力与渠道。</NAlert>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="show = false">取消</NButton>
        <NButton type="primary" :loading="saving" @click="save">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>
