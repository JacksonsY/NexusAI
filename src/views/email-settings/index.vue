<script setup lang="ts">
import { onMounted, ref } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchEmailConfig, saveEmailConfig, testEmailConnection, testEmailSend } from '@/service/api';

/**
 * 邮件配置（内置工具·EmailSettings 的 SMTP 段）：
 * SMTP 服务配置（密码 AES 加密·回显仅"已设置"占位）+ 测试连接 + 发送测试邮件。
 * 裁剪边界： 的"注册邮箱验证"段为登录业务（网关无终端用户体系）——不做。
 */
defineOptions({ name: 'EmailSettings' });

const loading = ref(false);
const saving = ref(false);
const testingConn = ref(false);
const testingSend = ref(false);
const passwordSet = ref(false);
const sentCount = ref(0);
const failedCount = ref(0);

const form = ref({
  host: '',
  port: 587,
  user: '',
  password: '',
  fromEmail: '',
  fromName: '',
  encryption: 'tls' as 'ssl' | 'tls' | 'none'
});

const testTo = ref('');

const encryptionOptions = [
  { label: 'TLS / STARTTLS', value: 'tls' },
  { label: 'SSL（隐式加密）', value: 'ssl' },
  { label: '无加密', value: 'none' }
];

async function load() {
  loading.value = true;
  const { data, error } = await fetchEmailConfig();
  if (!error && data) {
    form.value = {
      host: data.host,
      port: data.port,
      user: data.user,
      password: '',
      fromEmail: data.fromEmail,
      fromName: data.fromName,
      encryption: data.encryption || 'tls'
    };
    passwordSet.value = data.passwordSet;
    sentCount.value = data.sentCount;
    failedCount.value = data.failedCount;
  }
  loading.value = false;
}

async function handleSave() {
  saving.value = true;
  const payload: Record<string, unknown> = {
    host: form.value.host.trim(),
    port: form.value.port,
    user: form.value.user.trim(),
    fromEmail: form.value.fromEmail.trim(),
    fromName: form.value.fromName.trim(),
    encryption: form.value.encryption
  };
  // 密码留空=保持不变（不带 password 字段）
  if (form.value.password) payload.password = form.value.password;
  const { error } = await saveEmailConfig(payload);
  saving.value = false;
  if (error) return;
  window.$message?.success('邮件配置已保存');
  form.value.password = '';
  await load();
}

async function handleTestConnection() {
  testingConn.value = true;
  const { data, error } = await testEmailConnection();
  testingConn.value = false;
  if (error) return;
  window.$message?.success(data?.message ?? 'SMTP 连接成功');
}

async function handleTestSend() {
  if (!testTo.value.trim()) {
    window.$message?.warning('请输入收件邮箱');
    return;
  }
  testingSend.value = true;
  const { data, error } = await testEmailSend(testTo.value.trim());
  testingSend.value = false;
  if (error) return;
  window.$message?.success(data?.message ?? '测试邮件已发送');
  await load();
}

onMounted(load);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="邮件配置" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <div class="flex items-center gap-8px">
          <NTag size="small" :bordered="false" type="success">已发送 {{ sentCount }}</NTag>
          <NTag size="small" :bordered="false" :type="failedCount > 0 ? 'error' : 'default'">
            失败 {{ failedCount }}
          </NTag>
        </div>
      </template>
      <p class="mb-16px text-sm text-gray-400">
        配置 SMTP 服务用于发送运维告警邮件（通知服务的"邮件"通道）。保存后热生效，无需重启。
      </p>
      <NSpin :show="loading">
        <div class="grid grid-cols-1 gap-x-24px gap-y-16px md:grid-cols-2">
          <div>
            <div class="mb-4px text-sm font-medium">SMTP 服务器地址</div>
            <NInput v-model:value="form.host" placeholder="smtp.example.com" />
            <p class="mt-4px text-xs text-gray-400">邮件服务器地址</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">SMTP 端口</div>
            <NInputNumber v-model:value="form.port" :min="1" :max="65535" class="w-full" />
            <p class="mt-4px text-xs text-gray-400">常用端口：587（TLS）、465（SSL）、25（无加密）</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">SMTP 用户名</div>
            <NInput v-model:value="form.user" placeholder="your-email@example.com" />
            <p class="mt-4px text-xs text-gray-400">通常是您的邮箱地址</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">SMTP 密码</div>
            <NInput
              v-model:value="form.password"
              type="password"
              show-password-on="click"
              :input-props="{ autocomplete: 'new-password' }"
              :placeholder="passwordSet ? '已设置（输入新值覆盖·留空保持不变）' : '请输入密码'"
            />
            <p class="mt-4px text-xs text-gray-400">邮箱密码或应用专用密码（AES-256-GCM 加密存储）</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">发件人邮箱</div>
            <NInput v-model:value="form.fromEmail" placeholder="noreply@example.com" />
            <p class="mt-4px text-xs text-gray-400">显示为发件人的邮箱地址</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">发件人名称</div>
            <NInput v-model:value="form.fromName" placeholder="NexusAI" />
            <p class="mt-4px text-xs text-gray-400">显示为发件人的名称</p>
          </div>
          <div>
            <div class="mb-4px text-sm font-medium">加密方式</div>
            <NSelect v-model:value="form.encryption" :options="encryptionOptions" />
            <p class="mt-4px text-xs text-gray-400">Gmail 等服务推荐使用 SSL</p>
          </div>
        </div>
        <div class="mt-20px flex items-center gap-8px">
          <NButton type="primary" :loading="saving" @click="handleSave">保存</NButton>
          <NButton :loading="testingConn" @click="handleTestConnection">
            <template #icon>
              <SvgIcon icon="carbon:plug" />
            </template>
            测试连接
          </NButton>
        </div>
      </NSpin>
    </NCard>

    <NCard title="发送测试" :bordered="false" size="small" class="card-wrapper">
      <p class="mb-12px text-sm text-gray-400">按已保存配置发送一封测试邮件，验证 SMTP 通道连通性。</p>
      <div class="flex items-center gap-8px">
        <NInput v-model:value="testTo" placeholder="收件邮箱，如 ops@example.com" class="max-w-360px" />
        <NButton :loading="testingSend" @click="handleTestSend">
          <template #icon>
            <SvgIcon icon="carbon:send" />
          </template>
          发送测试邮件
        </NButton>
      </div>
    </NCard>
  </div>
</template>

<style scoped></style>
