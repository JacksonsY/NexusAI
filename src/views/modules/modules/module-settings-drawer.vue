<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchModuleSettings, runModuleAction, saveModuleSettings } from '@/service/api';

/**
 * 模块设置抽屉（schema 驱动· 模块配置页对位）：
 * 顶部运行指标格 → 参数表单（number/bool 可写·readonly 启动配置只读展示）→
 * 热生效/重启级标签 → 底部"查看监控"链接（adminRoute）。
 */
defineOptions({ name: 'ModuleSettingsDrawer' });

const props = defineProps<{ moduleName: string | null }>();
const show = defineModel<boolean>('show', { required: true });

const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const settings = ref<NexusAI.ModuleSettings | null>(null);
const form = ref<Record<string, unknown>>({});

const hotParams = computed(() => (settings.value?.params ?? []).filter(p => p.hot));
const readonlyParams = computed(() => (settings.value?.params ?? []).filter(p => !p.hot));

const hasChanges = computed(() =>
  hotParams.value.some(p => {
    if (p.type === 'secret') return Boolean(form.value[p.key]);
    return form.value[p.key] !== undefined && form.value[p.key] !== p.value;
  })
);

/** 模块动作（通知服务=发送测试 / 配置备份=立即备份） */
const ACTION_LABELS: Record<string, string> = {
  important_notification: '发送测试通知',
  config_backup: '立即备份'
};
const actionLabel = computed(() => (props.moduleName ? ACTION_LABELS[props.moduleName] : undefined));
const actionRunning = ref(false);

async function handleAction() {
  if (!props.moduleName) return;
  actionRunning.value = true;
  const { data, error } = await runModuleAction(props.moduleName);
  actionRunning.value = false;
  if (error) return;
  window.$message?.success(data?.message ?? '动作已执行');
  await load();
}

const fmtNum = (n: unknown) => (n === null || n === undefined ? '0' : Number(n).toLocaleString());

async function load() {
  if (!props.moduleName) return;
  loading.value = true;
  settings.value = null;
  const { data, error } = await fetchModuleSettings(props.moduleName);
  if (!error && data) {
    settings.value = data;
    const next: Record<string, unknown> = {};
    for (const p of data.params) {
      if (p.hot) next[p.key] = p.value;
    }
    form.value = next;
  }
  loading.value = false;
}

async function handleSave() {
  if (!props.moduleName || !settings.value) return;
  const changes: Record<string, unknown> = {};
  for (const p of hotParams.value) {
    if (p.type === 'secret') {
      if (form.value[p.key]) changes[p.key] = form.value[p.key];
    } else if (form.value[p.key] !== p.value) {
      changes[p.key] = form.value[p.key];
    }
  }
  if (Object.keys(changes).length === 0) return;
  saving.value = true;
  const { error } = await saveModuleSettings(props.moduleName, changes);
  saving.value = false;
  if (error) return;
  window.$message?.success('参数已保存，5 秒内热生效');
  await load();
}

function goMonitor() {
  if (settings.value?.adminRoute) {
    show.value = false;
    router.push(settings.value.adminRoute);
  }
}

watch(
  () => [show.value, props.moduleName],
  ([visible]) => {
    if (visible) load();
  }
);
</script>

<template>
  <NDrawer v-model:show="show" :width="440">
    <NDrawerContent :title="settings ? `${settings.displayName} · 设置` : '模块设置'" closable>
      <NSpin :show="loading">
        <div v-if="settings" class="space-y-20px">
          <!-- 运行指标 -->
          <div v-if="settings.metrics.length > 0">
            <div class="mb-8px text-xs text-gray-400 font-semibold tracking-wider uppercase">运行指标</div>
            <div class="grid grid-cols-2 gap-8px sm:grid-cols-3">
              <div
                v-for="m in settings.metrics"
                :key="m.label"
                class="border border-gray-100 rounded-8px bg-gray-50 px-12px py-8px dark:border-gray-800 dark:bg-gray-900"
              >
                <div class="text-10px text-gray-400">{{ m.label }}</div>
                <div class="mt-2px text-sm font-semibold tabular-nums">{{ fmtNum(m.value) }}</div>
              </div>
            </div>
          </div>

          <!-- 可调参数（热生效） -->
          <div v-if="hotParams.length > 0">
            <div class="mb-8px flex items-center gap-6px">
              <span class="text-xs text-gray-400 font-semibold tracking-wider uppercase">可调参数</span>
              <NTag size="tiny" type="success" :bordered="false">保存后 5s 热生效</NTag>
            </div>
            <div class="space-y-16px">
              <div v-for="p in hotParams" :key="p.key">
                <div class="mb-4px flex items-center justify-between">
                  <span class="text-sm font-medium">{{ p.label }}</span>
                  <span v-if="p.unit" class="text-xs text-gray-400">{{ p.unit }}</span>
                </div>
                <NInputNumber
                  v-if="p.type === 'number'"
                  :value="Number(form[p.key])"
                  :min="p.min"
                  :max="p.max"
                  class="w-full"
                  @update:value="(v: number | null) => (form[p.key] = v ?? p.defaultValue)"
                />
                <NSwitch
                  v-else-if="p.type === 'bool'"
                  :value="Boolean(form[p.key])"
                  @update:value="(v: boolean) => (form[p.key] = v)"
                />
                <NInput
                  v-else-if="p.type === 'string'"
                  :value="String(form[p.key] ?? '')"
                  :placeholder="String(p.defaultValue ?? '')"
                  @update:value="(v: string) => (form[p.key] = v)"
                />
                <NInput
                  v-else-if="p.type === 'secret'"
                  :value="String(form[p.key] ?? '')"
                  type="password"
                  show-password-on="click"
                  :input-props="{ autocomplete: 'new-password' }"
                  :placeholder="p.configured ? '已配置（输入新值覆盖·留空保持不变）' : '未配置'"
                  @update:value="(v: string) => (form[p.key] = v)"
                />
                <p v-if="p.hint" class="mt-4px text-xs text-gray-400">{{ p.hint }}</p>
              </div>
            </div>
          </div>

          <!-- 启动配置（只读） -->
          <div v-if="readonlyParams.length > 0">
            <div class="mb-8px flex items-center gap-6px">
              <span class="text-xs text-gray-400 font-semibold tracking-wider uppercase">启动配置</span>
              <NTag size="tiny" :bordered="false">重启生效</NTag>
            </div>
            <div class="space-y-8px">
              <div
                v-for="p in readonlyParams"
                :key="p.key"
                class="border border-gray-100 rounded-8px px-12px py-8px dark:border-gray-800"
              >
                <div class="flex items-center justify-between gap-8px">
                  <span class="text-sm">{{ p.label }}</span>
                  <span class="truncate text-sm font-mono text-gray-500" :title="String(p.defaultValue)">
                    {{ p.defaultValue }}
                  </span>
                </div>
                <p v-if="p.hint" class="mt-2px text-xs text-gray-400">{{ p.hint }}</p>
                <p class="mt-2px text-10px text-gray-400 font-mono">{{ p.key }}</p>
              </div>
            </div>
          </div>

          <div v-if="hotParams.length === 0 && readonlyParams.length === 0" class="py-24px text-center text-sm text-gray-400">
            该模块无可配置参数
          </div>
        </div>
      </NSpin>

      <template #footer>
        <div class="w-full flex items-center justify-between">
          <div class="flex items-center gap-8px">
            <NButton v-if="settings?.adminRoute" size="small" quaternary @click="goMonitor">
              <template #icon>
                <SvgIcon icon="carbon:chart-line" />
              </template>
              查看监控
            </NButton>
            <NButton v-if="actionLabel" size="small" :loading="actionRunning" @click="handleAction">
              <template #icon>
                <SvgIcon icon="carbon:play" />
              </template>
              {{ actionLabel }}
            </NButton>
          </div>
          <div class="flex items-center gap-8px">
            <NButton size="small" @click="show = false">关闭</NButton>
            <NButton
              v-if="hotParams.length > 0"
              size="small"
              type="primary"
              :disabled="!hasChanges"
              :loading="saving"
              @click="handleSave"
            >
              保存
            </NButton>
          </div>
        </div>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped></style>
