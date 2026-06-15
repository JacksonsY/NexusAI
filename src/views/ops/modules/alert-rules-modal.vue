<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createAlertRule,
  deleteAlertRule,
  fetchAlertMetricTypes,
  fetchAlertRules,
  testAlertRule,
  updateAlertRule
} from '@/service/api';

defineOptions({ name: 'AlertRulesModal' });

/** 告警规则管理弹窗（sub2api OpsAlertRulesCard 对位）：CRUD + dry-run 测试。 */
const show = defineModel<boolean>('show', { default: false });

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const rules = ref<NexusAI.AlertRule[]>([]);
const metricTypes = ref<string[]>([]);
const editing = ref(false);
const editId = ref<number | null>(null);
const testResult = ref<NexusAI.AlertRuleTestResult | null>(null);

/** 指标中文标签（未知回落原始值） */
const METRIC_LABELS: Record<string, string> = {
  error_rate: '错误率（%）',
  success_rate: '成功率（%）',
  qps: 'QPS（请求/秒）',
  request_count: '请求数',
  error_count: '错误数',
  p99_latency_ms: 'P99 延迟（ms）',
  ttft_p99_ms: 'TTFT P99（ms）',
  inflight_concurrency: '在途并发',
  schedulable_key_ratio: '可调度 Key 比例（%）',
  circuit_open_count: '熔断数（渠道+Key）',
  node_offline_count: '离线节点数',
  failover_count: '故障转移次数',
  cpu_usage_percent: 'CPU 使用率（%）',
  memory_usage_percent: '堆内存使用率（%）'
};

const metricOptions = computed(() =>
  metricTypes.value.map(t => ({ label: METRIC_LABELS[t] ?? t, value: t }))
);

const OPERATOR_OPTIONS = ['>', '>=', '<', '<=', '==', '!='].map(o => ({ label: o, value: o }));
const SEVERITY_OPTIONS = ['P0', 'P1', 'P2', 'P3'].map(s => ({ label: s, value: s }));

const emptyForm = () => ({
  name: '',
  description: '',
  enabled: true,
  metricType: 'error_rate',
  operator: '>=',
  threshold: 5,
  windowMinutes: 5,
  sustainedMinutes: 1,
  cooldownMinutes: 10,
  severity: 'P2',
  notify: true
});

const form = ref(emptyForm());

function metricLabel(t: string) {
  return METRIC_LABELS[t] ?? t;
}

function severityType(s: string): 'error' | 'warning' | 'default' {
  if (s === 'P0' || s === 'P1') return 'error';
  if (s === 'P2') return 'warning';
  return 'default';
}

async function load() {
  loading.value = true;
  const [r, m] = await Promise.all([fetchAlertRules(), fetchAlertMetricTypes()]);
  if (!r.error && r.data) rules.value = r.data;
  if (!m.error && m.data) metricTypes.value = m.data;
  loading.value = false;
}

function startCreate() {
  editId.value = null;
  form.value = emptyForm();
  testResult.value = null;
  editing.value = true;
}

function startEdit(rule: NexusAI.AlertRule) {
  editId.value = rule.id;
  form.value = {
    name: rule.name,
    description: rule.description ?? '',
    enabled: rule.enabledFlag === 1,
    metricType: rule.metricType,
    operator: rule.operator,
    threshold: rule.threshold,
    windowMinutes: rule.windowMinutes,
    sustainedMinutes: rule.sustainedMinutes,
    cooldownMinutes: rule.cooldownMinutes,
    severity: rule.severity,
    notify: rule.notifyFlag === 1
  };
  testResult.value = null;
  editing.value = true;
}

async function handleSave() {
  saving.value = true;
  const body = { ...form.value };
  const { error } = editId.value == null ? await createAlertRule(body) : await updateAlertRule(editId.value, body);
  saving.value = false;
  if (error) return;
  window.$message?.success(editId.value == null ? '规则已创建' : '规则已更新');
  editing.value = false;
  load();
}

async function handleDelete(id: number) {
  const { error } = await deleteAlertRule(id);
  if (error) return;
  window.$message?.success('规则已删除');
  load();
}

async function toggleEnabled(rule: NexusAI.AlertRule) {
  const { error } = await updateAlertRule(rule.id, {
    name: rule.name,
    description: rule.description ?? '',
    enabled: rule.enabledFlag !== 1,
    metricType: rule.metricType,
    operator: rule.operator,
    threshold: rule.threshold,
    windowMinutes: rule.windowMinutes,
    sustainedMinutes: rule.sustainedMinutes,
    cooldownMinutes: rule.cooldownMinutes,
    severity: rule.severity,
    notify: rule.notifyFlag === 1
  });
  if (error) return;
  load();
}

/** dry-run：按当前表单口径现算指标值 */
async function handleTest() {
  testing.value = true;
  const { data, error } = await testAlertRule({
    metricType: form.value.metricType,
    operator: form.value.operator,
    threshold: form.value.threshold,
    windowMinutes: form.value.windowMinutes
  });
  testing.value = false;
  if (error) return;
  testResult.value = data ?? null;
}

watch(show, v => {
  if (v) {
    editing.value = false;
    load();
  }
});
</script>

<template>
  <NModal v-model:show="show" preset="card" title="告警规则管理" class="max-w-880px">
    <!-- 列表 -->
    <template v-if="!editing">
      <div class="mb-12px flex items-center justify-between">
        <p class="m-0 text-sm text-gray-400">评估器每分钟扫描启用规则；连续违反达「持续时长」后触发并走通知服务</p>
        <NButton size="small" type="primary" @click="startCreate">
          <template #icon>
            <SvgIcon icon="carbon:add" />
          </template>
          新增规则
        </NButton>
      </div>
      <NSpin :show="loading">
        <NEmpty v-if="rules.length === 0" description="暂无告警规则" class="py-24px" />
        <NTable v-else size="small" :bordered="false" :single-line="false">
          <thead>
            <tr>
              <th>名称</th>
              <th class="w-180px">条件</th>
              <th class="w-130px">窗口/持续/冷却</th>
              <th class="w-60px">级别</th>
              <th class="w-140px">最后触发</th>
              <th class="w-70px">启用</th>
              <th class="w-110px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rules" :key="r.id">
              <td>
                <div class="text-sm">{{ r.name }}</div>
                <div v-if="r.description" class="text-xs text-gray-400">{{ r.description }}</div>
              </td>
              <td class="text-xs">{{ metricLabel(r.metricType) }} {{ r.operator }} {{ r.threshold }}</td>
              <td class="text-xs tabular-nums">{{ r.windowMinutes }}m / {{ r.sustainedMinutes }}m / {{ r.cooldownMinutes }}m</td>
              <td>
                <NTag size="tiny" :bordered="false" :type="severityType(r.severity)">{{ r.severity }}</NTag>
              </td>
              <td class="text-xs tabular-nums">{{ r.lastTriggeredDt?.replace('T', ' ') ?? '从未' }}</td>
              <td>
                <NSwitch size="small" :value="r.enabledFlag === 1" @update:value="toggleEnabled(r)" />
              </td>
              <td>
                <div class="flex items-center gap-4px">
                  <NButton size="tiny" quaternary @click="startEdit(r)">编辑</NButton>
                  <NPopconfirm @positive-click="handleDelete(r.id)">
                    <template #trigger>
                      <NButton size="tiny" quaternary type="error">删除</NButton>
                    </template>
                    确认删除规则「{{ r.name }}」？
                  </NPopconfirm>
                </div>
              </td>
            </tr>
          </tbody>
        </NTable>
      </NSpin>
    </template>

    <!-- 编辑表单 -->
    <template v-else>
      <div class="grid grid-cols-1 gap-12px md:grid-cols-2">
        <div>
          <div class="mb-4px text-xs text-gray-400">规则名称</div>
          <NInput v-model:value="form.name" size="small" placeholder="如：错误率超 5%" />
        </div>
        <div>
          <div class="mb-4px text-xs text-gray-400">描述</div>
          <NInput v-model:value="form.description" size="small" placeholder="可选" />
        </div>
        <div>
          <div class="mb-4px text-xs text-gray-400">指标</div>
          <NSelect v-model:value="form.metricType" size="small" :options="metricOptions" filterable />
        </div>
        <div class="grid grid-cols-2 gap-8px">
          <div>
            <div class="mb-4px text-xs text-gray-400">比较符</div>
            <NSelect v-model:value="form.operator" size="small" :options="OPERATOR_OPTIONS" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">阈值</div>
            <NInputNumber v-model:value="form.threshold" size="small" class="w-full" />
          </div>
        </div>
        <div class="grid grid-cols-3 gap-8px">
          <div>
            <div class="mb-4px text-xs text-gray-400">窗口（分钟）</div>
            <NInputNumber v-model:value="form.windowMinutes" size="small" :min="1" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">持续（分钟）</div>
            <NInputNumber v-model:value="form.sustainedMinutes" size="small" :min="1" class="w-full" />
          </div>
          <div>
            <div class="mb-4px text-xs text-gray-400">冷却（分钟）</div>
            <NInputNumber v-model:value="form.cooldownMinutes" size="small" :min="0" class="w-full" />
          </div>
        </div>
        <div class="flex items-end gap-16px">
          <div>
            <div class="mb-4px text-xs text-gray-400">级别</div>
            <NSelect v-model:value="form.severity" size="small" :options="SEVERITY_OPTIONS" class="w-90px" />
          </div>
          <div class="flex items-center gap-6px pb-4px">
            <NSwitch v-model:value="form.enabled" size="small" />
            <span class="text-xs">启用</span>
          </div>
          <div class="flex items-center gap-6px pb-4px">
            <NSwitch v-model:value="form.notify" size="small" />
            <span class="text-xs">触发推送通知</span>
          </div>
        </div>
      </div>

      <!-- dry-run 测试 -->
      <div class="mt-12px flex items-center gap-8px rounded-8px bg-gray-50 px-12px py-8px dark:bg-gray-800">
        <NButton size="small" :loading="testing" @click="handleTest">
          <template #icon>
            <SvgIcon icon="carbon:play" />
          </template>
          测试规则
        </NButton>
        <span v-if="testResult" class="text-xs">
          当前值
          <b class="tabular-nums">{{ testResult.value ?? '无样本' }}</b>
          ·
          <NTag size="tiny" :bordered="false" :type="testResult.breached ? 'error' : 'success'">
            {{ testResult.breached ? '将触发' : '不触发' }}
          </NTag>
        </span>
        <span v-else class="text-xs text-gray-400">按当前指标口径现算一次，不落事件</span>
      </div>

      <div class="mt-16px flex justify-end gap-8px">
        <NButton size="small" @click="editing = false">返回列表</NButton>
        <NButton size="small" type="primary" :loading="saving" :disabled="!form.name" @click="handleSave">保存</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped></style>
