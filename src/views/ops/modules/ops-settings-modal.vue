<script setup lang="ts">
import { ref, watch } from 'vue';
import { fetchOpsAlertSettings, fetchOpsThresholds, saveOpsAlertSettings, saveOpsThresholds } from '@/service/api';
import { defaultOpsSettings, loadOpsSettings, saveOpsSettings, type OpsPageSettings } from '../composables/use-ops-settings';

defineOptions({ name: 'OpsSettingsModal' });

/**
 * 运维监控设置弹窗（sub2api OpsSettingsDialog 多 Tab 对位）：
 * ① 显示与刷新（本地 localStorage） ② 告警评估（KV 开关+间隔） ③ 指标阈值（KV·健康分共用）。
 */
const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ saved: [settings: OpsPageSettings]; thresholdsSaved: [] }>();

const tab = ref('display');
const form = ref<OpsPageSettings>(defaultOpsSettings());

const alertSettings = ref<NexusAI.OpsAlertSettings>({ enabled: true, intervalSeconds: 60 });
const thresholds = ref<NexusAI.OpsThresholds>({
  slaPercentMin: 95,
  errorRatePercentMax: 5,
  p99LatencyMsMax: 30000,
  ttftP99MsMax: 5000
});
const saving = ref(false);

const INTERVAL_OPTIONS = [10, 30, 60, 120].map(n => ({ label: `${n} 秒`, value: n }));
const EVAL_INTERVAL_OPTIONS = [30, 60, 120, 300].map(n => ({ label: `${n} 秒`, value: n }));

async function loadRemote() {
  const [a, t] = await Promise.all([fetchOpsAlertSettings(), fetchOpsThresholds()]);
  if (!a.error && a.data) alertSettings.value = { ...a.data };
  if (!t.error && t.data) thresholds.value = { ...t.data };
}

async function handleSave() {
  saving.value = true;
  saveOpsSettings(form.value);
  const [a, t] = await Promise.all([
    saveOpsAlertSettings(alertSettings.value),
    saveOpsThresholds(thresholds.value)
  ]);
  saving.value = false;
  if (a.error || t.error) return;
  window.$message?.success('设置已保存');
  show.value = false;
  emit('saved', { ...form.value });
  emit('thresholdsSaved');
}

watch(show, v => {
  if (v) {
    form.value = loadOpsSettings();
    loadRemote();
  }
});
</script>

<template>
  <NModal v-model:show="show" preset="card" title="运维监控设置" class="max-w-520px">
    <NTabs v-model:value="tab" type="line" size="small">
      <NTabPane name="display" tab="显示与刷新">
        <div class="space-y-12px pt-8px">
          <div class="flex items-center justify-between">
            <span class="text-sm">显示「模型 Token 统计」卡片</span>
            <NSwitch v-model:value="form.showTokenStats" size="small" />
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm">显示「告警事件」卡片</span>
            <NSwitch v-model:value="form.showAlertEvents" size="small" />
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm">显示「并发与可用性」卡片</span>
            <NSwitch v-model:value="form.showConcurrency" size="small" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">自动刷新间隔</div>
            <NSelect v-model:value="form.autoRefreshSeconds" size="small" :options="INTERVAL_OPTIONS" />
          </div>
        </div>
      </NTabPane>
      <NTabPane name="alert" tab="告警评估">
        <div class="space-y-12px pt-8px">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-sm">启用告警评估</div>
              <div class="text-xs text-gray-400">关闭后评估器空转，不再产生新告警事件</div>
            </div>
            <NSwitch v-model:value="alertSettings.enabled" size="small" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">评估间隔（每轮检查全部启用规则·集群 leader 单跑）</div>
            <NSelect v-model:value="alertSettings.intervalSeconds" size="small" :options="EVAL_INTERVAL_OPTIONS" />
          </div>
        </div>
      </NTabPane>
      <NTabPane name="thresholds" tab="指标阈值">
        <div class="space-y-12px pt-8px">
          <div>
            <div class="mb-4px text-xs text-gray-400">SLA 下限（%·成功率低于此值判严重）</div>
            <NInputNumber v-model:value="thresholds.slaPercentMin" :min="0" :max="100" size="small" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">错误率上限（%）</div>
            <NInputNumber v-model:value="thresholds.errorRatePercentMax" :min="0" :max="100" size="small" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">P99 延迟上限（ms）</div>
            <NInputNumber v-model:value="thresholds.p99LatencyMsMax" :min="100" :step="1000" size="small" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">TTFT P99 上限（ms）</div>
            <NInputNumber v-model:value="thresholds.ttftP99MsMax" :min="100" :step="500" size="small" class="w-full" />
          </div>
          <p class="m-0 text-11px text-gray-400">阈值同时用于健康分诊断与头部指标着色</p>
        </div>
      </NTabPane>
    </NTabs>
    <template #footer>
      <div class="flex justify-end gap-8px">
        <NButton size="small" @click="show = false">取消</NButton>
        <NButton size="small" type="primary" :loading="saving" @click="handleSave">保存</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped></style>
