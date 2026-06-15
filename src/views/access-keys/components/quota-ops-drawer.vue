<script setup lang="ts">
import { ref, watch } from 'vue';
import { fetchAdjustAccessKeyQuota, fetchAccessKeyBalance } from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  target: NexusAI.AccessKey | null;
}
const props = defineProps<Props>();
const emit = defineEmits<{ changed: [] }>();
const show = defineModel<boolean>('show', { default: false });

const loading = ref(false);
const saving = ref(false);
const balance = ref<Record<string, any> | null>(null);
const quotaUsd = ref<number | null>(null);
const unlimited = ref(true);

watch(show, async v => {
  if (v && props.target) {
    const q = Number(props.target.quotaUsd ?? 0);
    unlimited.value = !(q > 0);
    quotaUsd.value = q > 0 ? q : null;
    balance.value = null;
    await loadBalance();
  }
});

async function loadBalance() {
  if (!props.target?.id) return;
  loading.value = true;
  const { data, error } = await fetchAccessKeyBalance(props.target.id);
  loading.value = false;
  if (!error) balance.value = data as Record<string, any>;
}

async function save() {
  if (!props.target?.id) return;
  saving.value = true;
  const { error } = await fetchAdjustAccessKeyQuota(props.target.id, unlimited.value ? null : (quotaUsd.value ?? null));
  saving.value = false;
  if (!error) {
    window.$message?.success('子限额已更新');
    show.value = false;
    emit('changed');
  }
}

/** 美元余额展示（后端回美元数值/字符串·保留 2 位小数）。 */
function fmt(v: unknown) {
  const n = Number(v ?? 0);
  return Number.isFinite(n) ? `$${n.toFixed(2)}` : '-';
}
</script>

<template>
  <NDrawer v-model:show="show" :width="430" placement="right">
    <NDrawerContent title="额度管理" closable>
      <NSpin :show="loading">
        <div class="flex flex-col gap-12px">
          <div class="rounded-8px bg-[var(--n-color-embedded)] p-12px">
            <div class="text-12px op-60">所属项目</div>
            <div class="text-14px font-medium">{{ balance?.projectCode ?? target?.projectCode ?? '-' }}</div>
            <div class="mt-2px text-12px op-55">{{ balance?.workspaceId ? `工作区 ${balance.workspaceId}` : '项目池直扣' }}</div>
          </div>

          <div class="grid grid-cols-2 gap-10px">
            <div class="rounded-8px border border-[var(--n-border-color)] p-12px">
              <div class="text-12px op-60">项目池余额</div>
              <div class="mt-2px text-18px font-bold tabular-nums">
                {{ balance?.projectUnlimited ? '∞' : fmt(balance?.projectBalance) }}
              </div>
            </div>
            <div v-if="balance?.workspaceId != null" class="rounded-8px border border-[var(--n-border-color)] p-12px">
              <div class="text-12px op-60">工作区余额</div>
              <div class="mt-2px text-18px font-bold tabular-nums">{{ fmt(balance?.workspaceBalance) }}</div>
            </div>
          </div>

          <NAlert type="info" :bordered="false" class="text-12px">
            独立密钥复用项目三级闸门：实际扣<b>项目池 / 工作区</b>额度，此处仅调整本 Key 的<b>子限额上限</b>（防单 Key
            打爆项目池）。给项目池充值请到「项目管理」。
          </NAlert>

          <div>
            <div class="mb-6px flex items-center justify-between">
              <span class="text-13px font-medium">Key 子限额（USD $）</span>
              <NSwitch v-model:value="unlimited" size="small">
                <template #checked>不设上限</template>
                <template #unchecked>设上限</template>
              </NSwitch>
            </div>
            <NInputNumber
              v-model:value="quotaUsd"
              class="w-full"
              :min="0"
              :precision="2"
              :disabled="unlimited"
              :placeholder="unlimited ? '不设上限·仅受项目池约束' : '该 Key 可消耗 USD 上限'"
            >
              <template #prefix>$</template>
            </NInputNumber>
          </div>
        </div>
      </NSpin>

      <template #footer>
        <div class="flex justify-end gap-8px">
          <NButton @click="show = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="save">
            <template #icon><SvgIcon icon="lucide:save" /></template>
            保存
          </NButton>
        </div>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
