<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { fetchChannels, fetchModels } from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  editing: NexusAI.AccessKey | null;
  projectOptions: { label: string; value: string }[];
  submitting?: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  submit: [payload: NexusAI.AccessKeyCreateRequest & { status?: number }];
}>();
const show = defineModel<boolean>('show', { default: false });

// 端点（协议入口）固定选项 —— 「允许的端点」
const apiFormatOptions = [
  { label: 'OpenAI', value: 'openai' },
  { label: 'Anthropic', value: 'anthropic' },
  { label: 'Gemini', value: 'gemini' },
  { label: 'DeepSeek', value: 'deepseek' },
  { label: 'Qwen', value: 'qwen' }
];
const modelOptions = ref<{ label: string; value: string }[]>([]);
const providerOptions = ref<{ label: string; value: string }[]>([]);

interface FormState {
  projectCode: string | null;
  workspaceId: number | null;
  groupCode: string;
  name: string;
  expiresAt: number | null;
  autoDeleteOnExpiry: boolean;
  quotaUsd: number | null;
  unlimitedQuota: boolean;
  ipRulesText: string;
  piiEnabled: boolean;
  piiNotice: boolean;
  providerUnrestricted: boolean;
  apiFormatUnrestricted: boolean;
  modelUnrestricted: boolean;
  allowedProviders: string[];
  allowedApiFormats: string[];
  allowedModels: string[];
  rateLimitInherited: boolean;
  rateLimit: number | null;
  concurrentInherited: boolean;
  concurrentLimit: number | null;
  status: number;
}

const form = reactive<FormState>(emptyForm());

function emptyForm(): FormState {
  return {
    projectCode: null,
    workspaceId: null,
    groupCode: 'default',
    name: '',
    expiresAt: null,
    autoDeleteOnExpiry: false,
    quotaUsd: null,
    unlimitedQuota: true,
    ipRulesText: '',
    piiEnabled: false,
    piiNotice: true,
    providerUnrestricted: true,
    apiFormatUnrestricted: true,
    modelUnrestricted: true,
    allowedProviders: [],
    allowedApiFormats: [],
    allowedModels: [],
    rateLimitInherited: true,
    rateLimit: null,
    concurrentInherited: true,
    concurrentLimit: null,
    status: 1
  };
}

function parseArr(raw?: string): string[] {
  if (!raw) return [];
  try {
    const a = JSON.parse(raw);
    return Array.isArray(a) ? a.map(String) : [];
  } catch {
    return raw.split(',').map(s => s.trim()).filter(Boolean);
  }
}

async function loadOptions() {
  const [m, c] = await Promise.all([fetchModels({ pageNum: 1, pageSize: 1000 }), fetchChannels()]);
  modelOptions.value = (m.data?.records ?? [])
    .map(r => r.model)
    .filter(x => x?.modelKey)
    .map(x => ({ value: x.modelKey!, label: x.modelAlias ? `${x.modelKey}（${x.modelAlias}）` : x.modelKey! }));
  const channels = (c.data ?? []) as NexusAI.Channel[];
  providerOptions.value = channels
    .filter(x => x.id != null)
    .map(x => ({ value: String(x.id), label: x.name ?? String(x.id) }));
}

function reset() {
  const e = props.editing;
  if (!e) {
    Object.assign(form, emptyForm());
    form.projectCode = props.projectOptions[0]?.value ?? null;
    return;
  }
  const quota = Number(e.quotaUsd ?? 0);
  const exp = Number(e.expiredTime ?? 0);
  let pii = { enabled: false, notice: true };
  try {
    const fs = e.featureSettings ? JSON.parse(e.featureSettings) : null;
    pii = {
      enabled: !!fs?.chat_pii_redaction?.enabled,
      notice: fs?.chat_pii_redaction?.injectModelInstruction !== false
    };
  } catch {
    /* ignore */
  }
  Object.assign(form, {
    projectCode: e.projectCode ?? null,
    workspaceId: e.workspaceId ? Number(e.workspaceId) : null,
    groupCode: e.groupCode ?? 'default',
    name: e.name ?? '',
    expiresAt: exp > 0 ? exp : null,
    autoDeleteOnExpiry: e.autoDeleteOnExpiry === 1,
    quotaUsd: quota > 0 ? quota : null,
    unlimitedQuota: !(quota > 0),
    ipRulesText: e.allowIps ? parseArr(e.allowIps).join(', ') : '',
    piiEnabled: pii.enabled,
    piiNotice: pii.notice,
    providerUnrestricted: !e.allowProviders,
    apiFormatUnrestricted: !e.allowApiFormats,
    modelUnrestricted: !e.modelLimits,
    allowedProviders: parseArr(e.allowProviders),
    allowedApiFormats: parseArr(e.allowApiFormats),
    allowedModels: parseArr(e.modelLimits),
    rateLimitInherited: !(Number(e.rpmLimit ?? 0) > 0),
    rateLimit: Number(e.rpmLimit ?? 0) > 0 ? Number(e.rpmLimit) : null,
    concurrentInherited: !(Number(e.concurrentLimit ?? 0) > 0),
    concurrentLimit: Number(e.concurrentLimit ?? 0) > 0 ? Number(e.concurrentLimit) : null,
    status: e.status === 0 ? 0 : 1
  } satisfies FormState);
}

watch(show, v => {
  if (v) {
    reset();
    loadOptions();
  }
});

// 清空过期日期（同时清空到期删除·clearExpiryDate）
function clearExpiry() {
  form.expiresAt = null;
  form.autoDeleteOnExpiry = false;
}

function buildFeatureSettings(): string {
  if (!form.piiEnabled) return '';
  return JSON.stringify({ chat_pii_redaction: { enabled: true, injectModelInstruction: form.piiNotice } });
}

function submit() {
  if (!form.name.trim()) {
    window.$message?.warning('请输入密钥名称');
    return;
  }
  if (!form.projectCode) {
    window.$message?.warning('请选择归属项目');
    return;
  }
  const arrToJson = (unrestricted: boolean, arr: string[]) => (unrestricted ? '' : JSON.stringify(arr));
  const payload: NexusAI.AccessKeyCreateRequest & { status?: number } = {
    name: form.name.trim(),
    projectCode: form.projectCode,
    workspaceId: form.workspaceId ?? null,
    groupCode: form.groupCode.trim() || 'default',
    modelLimits: arrToJson(form.modelUnrestricted, form.allowedModels),
    allowProviders: arrToJson(form.providerUnrestricted, form.allowedProviders),
    allowApiFormats: arrToJson(form.apiFormatUnrestricted, form.allowedApiFormats),
    allowIps: form.ipRulesText.trim(),
    quotaUsd: form.unlimitedQuota ? null : (form.quotaUsd ?? null),
    rpmLimit: form.rateLimitInherited ? 0 : (form.rateLimit ?? 0),
    tpmLimit: props.editing ? Number(props.editing.tpmLimit ?? 0) : 0,
    concurrentLimit: form.concurrentInherited ? 0 : (form.concurrentLimit ?? 0),
    expiredTime: form.expiresAt ?? 0,
    autoDeleteOnExpiry: form.autoDeleteOnExpiry ? 1 : 0,
    featureSettings: buildFeatureSettings()
  };
  if (props.editing) payload.status = form.status;
  emit('submit', payload);
}
</script>

<template>
  <NModal v-model:show="show" preset="card" class="w-880px max-w-[94vw]" :bordered="false" size="small">
    <template #header>
      <div class="flex items-center gap-12px">
        <div class="flex h-36px w-36px items-center justify-center rounded-8px" style="background: rgba(var(--primary-color), 0.1)">
          <SvgIcon :icon="editing ? 'lucide:square-pen' : 'lucide:plus'" class="text-18px text-primary" />
        </div>
        <div>
          <div class="text-16px font-semibold leading-tight">
            {{ editing ? '编辑独立余额 Access Key' : '创建独立余额 Access Key' }}
          </div>
          <div class="text-12px op-60">
            {{ editing ? '修改密钥名称、有效期和访问限制' : '控制台签发，挂项目计费，可设子限额或不限额' }}
          </div>
        </div>
      </div>
    </template>

    <div class="grid grid-cols-1 gap-x-24px gap-y-4px md:grid-cols-2">
      <!-- 左侧：基础设置 -->
      <div class="flex flex-col gap-14px md:pr-24px">
        <div class="border-b border-[var(--n-border-color)] pb-6px text-13px font-medium">基础设置</div>

        <div class="grid grid-cols-2 gap-10px">
          <div class="col-span-2">
            <div class="mb-4px text-13px font-medium">归属项目 <span class="text-error">*</span></div>
            <NSelect v-model:value="form.projectCode" :options="projectOptions" filterable placeholder="选择项目" />
          </div>
          <div>
            <div class="mb-4px text-12px op-70">工作区 ID</div>
            <NInputNumber v-model:value="form.workspaceId" class="w-full" :min="1" :precision="0" placeholder="空=项目池" />
          </div>
          <div>
            <div class="mb-4px text-12px op-70">分组</div>
            <NInput v-model:value="form.groupCode" placeholder="default" />
          </div>
        </div>

        <div>
          <div class="mb-4px text-13px font-medium">密钥名称</div>
          <NInput v-model:value="form.name" maxlength="100" placeholder="例如: 用户A专用" />
        </div>

        <!-- 有效期设置（date + 到期删除 + 说明）-->
        <div>
          <div class="mb-4px text-13px font-medium">有效期设置</div>
          <div class="flex items-center gap-8px">
            <NDatePicker v-model:value="form.expiresAt" type="date" clearable class="flex-1" placeholder="永不过期" @clear="clearExpiry" />
            <NCheckbox v-model:checked="form.autoDeleteOnExpiry" :disabled="!form.expiresAt" class="whitespace-nowrap text-12px">
              到期删除
            </NCheckbox>
          </div>
          <div class="mt-4px text-12px op-55">
            {{ form.expiresAt ? `到期后${form.autoDeleteOnExpiry ? '自动删除' : '仅禁用'}` : '留空表示永不过期' }}
          </div>
        </div>

        <!-- 额度（子限额 + 无限额 Switch）映射 初始余额 -->
        <div>
          <div class="mb-4px text-13px font-medium">额度（子限额 USD $）</div>
          <div class="flex items-center gap-12px">
            <NInputNumber
              v-model:value="form.quotaUsd"
              class="flex-1"
              :min="0"
              :precision="2"
              :disabled="form.unlimitedQuota"
              :placeholder="form.unlimitedQuota ? '不设上限·仅受项目池约束' : '该 Key 可消耗 USD 上限'"
            >
              <template #prefix>$</template>
            </NInputNumber>
            <NSwitch v-model:value="form.unlimitedQuota" class="shrink-0">
              <template #checked>不限</template>
              <template #unchecked>设限</template>
            </NSwitch>
          </div>
          <div class="mt-4px text-12px op-55">独立密钥复用项目三级闸门：实际扣项目池/工作区额度，此处为本 Key 子限额上限。</div>
        </div>

        <!-- IP 限制 -->
        <div>
          <div class="mb-4px text-13px font-medium">IP 限制</div>
          <NInput v-model:value="form.ipRulesText" placeholder="例如：203.0.113.10, 10.0.0.0/24" />
          <div class="mt-4px text-12px op-55">留空表示不限制；支持 IP、IPv4 CIDR，多个规则用英文逗号分隔</div>
        </div>

        <!-- 敏感信息保护卡片 -->
        <div class="flex flex-col gap-8px rounded-8px border border-[var(--n-border-color)] bg-[var(--n-color-embedded)] p-12px">
          <div class="flex items-center justify-between">
            <span class="text-13px font-medium">敏感信息保护</span>
            <NSwitch v-model:value="form.piiEnabled" size="small" />
          </div>
          <div class="flex items-center justify-between">
            <span class="text-13px font-medium">占位符说明</span>
            <NSwitch v-model:value="form.piiNotice" :disabled="!form.piiEnabled" size="small" />
          </div>
        </div>
      </div>

      <!-- 右侧：访问限制 -->
      <div class="flex flex-col gap-14px md:border-l md:border-[var(--n-border-color)] md:pl-24px">
        <div class="border-b border-[var(--n-border-color)] pb-6px text-13px font-medium">访问限制</div>

        <!-- 允许的提供商 -->
        <div>
          <div class="mb-4px text-13px font-medium">允许的提供商</div>
          <div class="flex items-center gap-12px">
            <NSelect
              v-model:value="form.allowedProviders"
              :options="providerOptions"
              multiple
              filterable
              class="flex-1"
              :disabled="form.providerUnrestricted"
              :placeholder="form.providerUnrestricted ? '不限制' : '未选择（全部禁用）'"
            />
            <NSwitch v-model:value="form.providerUnrestricted" class="shrink-0" />
          </div>
        </div>

        <!-- 允许的端点 -->
        <div>
          <div class="mb-4px text-13px font-medium">允许的端点</div>
          <div class="flex items-center gap-12px">
            <NSelect
              v-model:value="form.allowedApiFormats"
              :options="apiFormatOptions"
              multiple
              class="flex-1"
              :disabled="form.apiFormatUnrestricted"
              :placeholder="form.apiFormatUnrestricted ? '不限制' : '未选择（全部禁用）'"
            />
            <NSwitch v-model:value="form.apiFormatUnrestricted" class="shrink-0" />
          </div>
        </div>

        <!-- 允许的模型 -->
        <div>
          <div class="mb-4px text-13px font-medium">允许的模型</div>
          <div class="flex items-center gap-12px">
            <NSelect
              v-model:value="form.allowedModels"
              :options="modelOptions"
              multiple
              filterable
              class="flex-1"
              :disabled="form.modelUnrestricted"
              :placeholder="form.modelUnrestricted ? '不限制' : '未选择（全部禁用）'"
            />
            <NSwitch v-model:value="form.modelUnrestricted" class="shrink-0" />
          </div>
        </div>

        <!-- 速率限制 -->
        <div>
          <div class="mb-4px text-13px font-medium">速率限制 (请求/分钟)</div>
          <div class="flex items-center gap-12px">
            <NInputNumber
              v-model:value="form.rateLimit"
              class="flex-1"
              :min="0"
              :max="100000"
              :precision="0"
              :disabled="form.rateLimitInherited"
              :placeholder="form.rateLimitInherited ? '不限制' : '0 = 不限速'"
            />
            <NSwitch v-model:value="form.rateLimitInherited" class="shrink-0" />
          </div>
        </div>

        <!-- 并发限制 -->
        <div>
          <div class="mb-4px text-13px font-medium">并发限制</div>
          <div class="flex items-center gap-12px">
            <NInputNumber
              v-model:value="form.concurrentLimit"
              class="flex-1"
              :min="0"
              :max="100000"
              :precision="0"
              :disabled="form.concurrentInherited"
              :placeholder="form.concurrentInherited ? '不限制' : '0 = 不限制'"
            />
            <NSwitch v-model:value="form.concurrentInherited" class="shrink-0" />
          </div>
          <div class="mt-4px text-12px op-55">留空或填 0 均表示不限制并发</div>
        </div>

        <div v-if="editing">
          <div class="mb-4px text-13px font-medium">状态</div>
          <NSelect
            v-model:value="form.status"
            :options="[
              { label: '活跃', value: 1 },
              { label: '禁用', value: 0 }
            ]"
          />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-8px">
        <NButton @click="show = false">取消</NButton>
        <NButton type="primary" :loading="submitting" @click="submit">{{ editing ? '更新' : '创建' }}</NButton>
      </div>
    </template>
  </NModal>
</template>
