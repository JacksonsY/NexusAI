<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NAlert, NButton, NInputNumber, NModal, NRadioButton, NRadioGroup, NSpin, NTag } from 'naive-ui';
import { fetchProjectBalance, fetchQuotaOp } from '@/service/api';

/**
 * 项目额度池充值/扣减弹窗（契约③ QuotaOperation·项目制）：打开即拉 Redis 实时余额，
 * add/subtract 带 requestId 幂等提交，成功后回显新余额。不限额项目只读提示。
 */
defineOptions({ name: 'QuotaModal' });

const props = defineProps<{ project: NexusAI.Project | null }>();
const show = defineModel<boolean>('show', { default: false });
const emit = defineEmits<{ saved: [] }>();

const loading = ref(false);
const submitting = ref(false);
const balance = ref<NexusAI.QuotaBalance | null>(null);
const op = ref<'add' | 'subtract'>('add');
const usd = ref<number | null>(null);

const isUnlimited = computed(() => Boolean(balance.value?.unlimited ?? props.project?.unlimited));

/** 美元余额展示（后端回美元数值/字符串·保留 2 位小数）。 */
function fmtUsd(v?: number | string | null) {
  const n = Number(v ?? 0);
  return Number.isFinite(n) ? `$${n.toFixed(2)}` : '$0.00';
}

async function loadBalance() {
  if (!props.project?.projectCode) {
    return;
  }
  loading.value = true;
  const { data, error } = await fetchProjectBalance(props.project.projectCode);
  if (!error) {
    balance.value = data;
  }
  loading.value = false;
}

watch(show, v => {
  if (v) {
    op.value = 'add';
    usd.value = null;
    balance.value = null;
    loadBalance();
  }
});

async function submit() {
  if (!props.project?.projectCode || !usd.value || usd.value <= 0) {
    window.$message?.warning('请输入大于 0 的美元金额');
    return;
  }
  submitting.value = true;
  const { data, error } = await fetchQuotaOp({
    projectCode: props.project.projectCode,
    op: op.value,
    usd: usd.value,
    requestId: `console-${crypto.randomUUID()}`   // 幂等去重（契约③ M11）
  });
  submitting.value = false;
  if (!error) {
    window.$message?.success(`${op.value === 'add' ? '充值' : '扣减'}成功，当前余额 ${fmtUsd(data)}`);
    usd.value = null;
    await loadBalance();
    emit('saved');
  }
}
</script>

<template>
  <NModal
    v-model:show="show"
    preset="card"
    :title="`额度池 · ${project?.name ?? ''}`"
    class="w-440px"
    :bordered="false"
    size="small"
  >
    <NSpin :show="loading">
      <div class="flex flex-col gap-16px">
        <!-- 实时余额（Redis 运行时真值） -->
        <div class="flex items-center justify-between rounded-8px bg-gray-50 px-16px py-12px dark:bg-gray-800/60">
          <span class="text-13px text-gray-400">当前余额（实时·$）</span>
          <NTag v-if="isUnlimited" type="warning" size="small">∞ 不限额</NTag>
          <span v-else class="text-18px font-bold tabular-nums">
            {{ fmtUsd(balance?.balance) }}
          </span>
        </div>

        <NAlert v-if="isUnlimited" type="info" :show-icon="true" :bordered="false">
          不限额项目闸门不扣减，无需充值。可在「编辑」中关闭不限额后再管理额度池。
        </NAlert>

        <template v-else>
          <div class="flex items-center gap-12px">
            <NRadioGroup v-model:value="op" size="small">
              <NRadioButton value="add">充值</NRadioButton>
              <NRadioButton value="subtract">扣减</NRadioButton>
            </NRadioGroup>
            <NInputNumber
              v-model:value="usd"
              class="flex-1"
              size="small"
              :min="0.01"
              :precision="2"
              :show-button="false"
              placeholder="美元金额（如 12.34）"
            >
              <template #prefix>$</template>
            </NInputNumber>
          </div>
          <div class="flex justify-end gap-8px">
            <NButton size="small" @click="show = false">关闭</NButton>
            <NButton size="small" type="primary" :loading="submitting" @click="submit">
              {{ op === 'add' ? '确认充值' : '确认扣减' }}
            </NButton>
          </div>
        </template>
      </div>
    </NSpin>
  </NModal>
</template>

<style scoped></style>
