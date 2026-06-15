<script setup lang="ts">
import { reactive, ref } from 'vue';
import { fetchRevokeKey, fetchSyncKey } from '@/service/api';

defineOptions({ name: 'KeySync' });

/**
 * 契约① Key 同步调试（IA·运维台）：手工编排一份 KeySync 快照 PUT 到网关 `/admin/keys`（模拟 ruoyi 控制面下发），
 * 用于联调/排障。<b>生产由 ruoyi 业务面自动推送</b>，此页仅供运维手动触发与按 id 吊销，不是常规入口。
 */
const form = reactive<NexusAI.KeySync>({
  keyHash: '',
  projectCode: '',
  externalWorkspaceId: '',
  groupCode: '',
  modelLimits: '',
  quotaUsd: undefined,
  status: 1
});

const syncing = ref(false);
const lastId = ref<number | null>(null);
const revokeId = ref<number | null>(null);

async function doSync() {
  if (!form.keyHash) {
    window.$message?.warning('keyHash 必填（sk- 的 SHA-256）');
    return;
  }
  if (!form.projectCode) {
    window.$message?.warning('projectCode 必填（项目制：Key 必须归属项目）');
    return;
  }
  syncing.value = true;
  const { data, error } = await fetchSyncKey({ ...form });
  syncing.value = false;
  if (!error) {
    lastId.value = data ?? null;
    window.$message?.success(`同步成功 · token id=${data}`);
  }
}

async function doRevoke() {
  if (!revokeId.value) {
    window.$message?.warning('请输入要吊销的 token id');
    return;
  }
  const { error } = await fetchRevokeKey(revokeId.value);
  if (!error) {
    window.$message?.success(`已吊销 token id=${revokeId.value}`);
  }
}
</script>

<template>
  <NSpace vertical :size="16">
    <NCard title="契约① Key 同步（调试）" :bordered="false" size="small" class="card-wrapper">
      <NText depth="3" class="mb-3 block text-12px">
        模拟 ruoyi 控制面把 Key 快照下发到网关（PUT /admin/keys·by keyHash upsert）。生产为 ruoyi 自动推送，此页仅联调/排障用。
      </NText>
      <NForm label-placement="left" :label-width="120">
        <NGrid :cols="2" :x-gap="16">
          <NGridItem>
            <NFormItem label="keyHash" required>
              <NInput v-model:value="form.keyHash" placeholder="sk- 的 SHA-256 摘要" />
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem label="分组 groupCode">
              <NInput v-model:value="form.groupCode" placeholder="default" />
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem label="归属项目" required>
              <NInput v-model:value="form.projectCode" placeholder="projectCode，如 ruoyi-saas" />
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem label="归属工作区(可选)">
              <NInput v-model:value="form.externalWorkspaceId" placeholder="externalWorkspaceId·非空=扣该工作区钱包(B2C)" />
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem label="子限额 $(可选)">
              <NInputNumber v-model:value="form.quotaUsd" class="w-full" :min="0" :precision="2" placeholder="USD·空=不设·仅受项目池">
                <template #prefix>$</template>
              </NInputNumber>
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem label="模型白名单">
              <NInput v-model:value="form.modelLimits" placeholder='["gpt-4o","claude-3-5"]' />
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem label="状态 status">
              <NSelect
                v-model:value="form.status"
                :options="[
                  { label: '启用(1)', value: 1 },
                  { label: '停用(0)', value: 0 }
                ]"
              />
            </NFormItem>
          </NGridItem>
        </NGrid>
        <NButton type="primary" :loading="syncing" @click="doSync">下发同步</NButton>
        <NText v-if="lastId" class="ml-3 text-12px" type="success">上次同步 token id = {{ lastId }}</NText>
      </NForm>
    </NCard>

    <NCard title="契约① Key 吊销（调试）" :bordered="false" size="small" class="card-wrapper">
      <NSpace align="center">
        <NText depth="3" class="text-12px">按 token id 吊销（POST /admin/keys/{id}/revoke）：</NText>
        <NInputNumber v-model:value="revokeId" placeholder="token id" class="w-200px" />
        <NButton type="error" ghost @click="doRevoke">吊销</NButton>
      </NSpace>
    </NCard>
  </NSpace>
</template>
