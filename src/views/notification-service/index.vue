<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchNotificationConfig, saveNotificationConfig, testNotification } from '@/service/api';

/**
 * 通知服务（内置工具·一比一 Aether ImportantNotification 独立页）：
 * 全局推送服务 + 总开关 + 三通道就绪状态（邮件/Server酱/Bark）+ 通知项（开关/通道/模板）+ 测试发送。
 * 裁剪边界：Aether 的"用户邮件"开关挂终端用户体系——网关只发管理员收件人，不做。
 */
defineOptions({ name: 'NotificationService' });

const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);

const config = ref<NexusAI.NotificationConfig>({
  enabled: false,
  emailEnabled: false,
  emailRecipients: '',
  defaultChannel: 'all',
  items: [],
  readiness: { emailReady: false, smtpConfigured: false, serverChanReady: false, barkReady: false },
  sentCount: 0,
  failedCount: 0
});

const testItemKey = ref('');
const testChannel = ref('global');
const testResults = ref<NexusAI.NotificationTestResult[]>([]);

const channelOptions = [
  { label: '所有可用服务', value: 'all' },
  { label: '邮件', value: 'email' },
  { label: 'Server 酱', value: 'server_chan' },
  { label: 'Bark', value: 'bark' }
];

const itemChannelOptions = [{ label: '使用全局', value: 'global' }, ...channelOptions];
const testChannelOptions = [{ label: '按通知项', value: 'global' }, ...channelOptions];

const itemOptions = computed(() => config.value.items.map(i => ({ label: i.name || i.key, value: i.key })));

const CHANNEL_LABELS: Record<string, string> = {
  email: '邮件',
  server_chan: 'Server 酱',
  bark: 'Bark',
  none: '无可用服务'
};

async function load() {
  loading.value = true;
  const { data, error } = await fetchNotificationConfig();
  if (!error && data) {
    config.value = data;
    if (!config.value.items.some(i => i.key === testItemKey.value)) {
      testItemKey.value = config.value.items[0]?.key ?? '';
    }
  }
  loading.value = false;
}

async function handleSave() {
  saving.value = true;
  const { error } = await saveNotificationConfig({
    enabled: config.value.enabled,
    emailEnabled: config.value.emailEnabled,
    emailRecipients: config.value.emailRecipients,
    defaultChannel: config.value.defaultChannel,
    items: config.value.items
  });
  saving.value = false;
  if (error) return;
  window.$message?.success('通知服务配置已保存');
  await load();
}

async function handleTest() {
  if (!testItemKey.value) return;
  testing.value = true;
  const { data, error } = await testNotification({
    itemKey: testItemKey.value,
    channel: testChannel.value === 'global' ? undefined : testChannel.value
  });
  testing.value = false;
  if (error) return;
  testResults.value = data ?? [];
  const anyOk = testResults.value.some(r => r.success);
  if (anyOk) window.$message?.success('测试通知已发送');
  else window.$message?.error('测试发送失败');
  await load();
}

function addItem() {
  const key = `custom_${Date.now().toString(36)}`;
  config.value.items.push({
    key,
    name: '自定义通知',
    enabled: true,
    channel: 'global',
    titleTemplate: '{title}',
    bodyTemplate: '{body}',
    system: false
  });
  testItemKey.value = key;
}

function removeItem(index: number) {
  const [removed] = config.value.items.splice(index, 1);
  if (removed?.key === testItemKey.value) {
    testItemKey.value = config.value.items[0]?.key ?? '';
  }
}

onMounted(load);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <!-- 服务配置 -->
    <NCard title="通知服务配置" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <div class="flex items-center gap-8px">
          <NTag size="small" :bordered="false" type="success">已发送 {{ config.sentCount }}</NTag>
          <NTag size="small" :bordered="false" :type="config.failedCount > 0 ? 'error' : 'default'">
            失败 {{ config.failedCount }}
          </NTag>
          <NButton size="small" type="primary" :loading="saving" @click="handleSave">保存</NButton>
        </div>
      </template>
      <NSpin :show="loading">
        <div class="grid grid-cols-1 gap-16px lg:grid-cols-2">
          <div>
            <div class="mb-4px text-sm font-medium">全局推送服务</div>
            <NSelect v-model:value="config.defaultChannel" :options="channelOptions" />
            <p class="mt-4px text-xs text-gray-400">通知项选择"使用全局"时走此服务</p>
          </div>
          <div class="flex items-center justify-between rounded-8px border border-gray-100 px-16px py-12px dark:border-gray-800">
            <div>
              <div class="text-sm font-medium">启用通知服务</div>
              <p class="mt-2px text-xs text-gray-400">关闭后所有通知静默跳过</p>
            </div>
            <NSwitch v-model:value="config.enabled" />
          </div>
        </div>

        <!-- 三通道状态 -->
        <div class="mt-16px grid grid-cols-1 gap-16px border-t border-gray-100 pt-16px lg:grid-cols-3 dark:border-gray-800">
          <div class="space-y-8px">
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-8px">
                <span class="text-sm font-medium">邮件</span>
                <NTag size="tiny" :bordered="false" :type="config.readiness.emailReady ? 'success' : 'default'">
                  {{ config.readiness.emailReady ? '可用' : '未就绪' }}
                </NTag>
              </div>
              <NSwitch v-model:value="config.emailEnabled" size="small" :disabled="!config.readiness.smtpConfigured" />
            </div>
            <p class="text-xs text-gray-400">
              SMTP 在
              <NButton text type="primary" size="tiny" @click="router.push('/email-settings')">邮件配置</NButton>
              中维护
            </p>
            <NInput
              v-model:value="config.emailRecipients"
              type="textarea"
              :rows="3"
              placeholder="管理员收件人（每行一个邮箱）&#10;ops@example.com"
            />
          </div>
          <div class="space-y-8px">
            <div class="flex items-center gap-8px">
              <span class="text-sm font-medium">Server 酱</span>
              <NTag size="tiny" :bordered="false" :type="config.readiness.serverChanReady ? 'success' : 'default'">
                {{ config.readiness.serverChanReady ? '可用' : '未就绪' }}
              </NTag>
            </div>
            <p class="text-xs text-gray-400">第三方推送服务在扩展模块中独立启用与配置</p>
            <NButton size="small" @click="router.push('/modules')">配置 Server 酱推送</NButton>
          </div>
          <div class="space-y-8px">
            <div class="flex items-center gap-8px">
              <span class="text-sm font-medium">Bark</span>
              <NTag size="tiny" :bordered="false" :type="config.readiness.barkReady ? 'success' : 'default'">
                {{ config.readiness.barkReady ? '可用' : '未就绪' }}
              </NTag>
            </div>
            <p class="text-xs text-gray-400">通过 Bark 向 iOS 设备推送通知</p>
            <NButton size="small" @click="router.push('/modules')">配置 Bark 推送</NButton>
          </div>
        </div>
      </NSpin>
    </NCard>

    <!-- 通知项 -->
    <NCard title="通知项" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <NButton size="small" ghost @click="addItem">
          <template #icon>
            <SvgIcon icon="carbon:add" />
          </template>
          添加通知项
        </NButton>
      </template>
      <p class="mb-12px text-sm text-gray-400">每个通知项可以继承全局服务，也可以单独指定推送服务；模板支持 {title} / {body} 占位符</p>
      <div class="space-y-12px">
        <div
          v-for="(item, index) in config.items"
          :key="item.key"
          class="rounded-8px border border-gray-100 p-16px dark:border-gray-800"
        >
          <div class="flex flex-wrap items-start justify-between gap-8px">
            <div class="flex items-center gap-8px">
              <span class="text-sm font-semibold">{{ item.name || item.key }}</span>
              <NTag v-if="item.system" size="tiny" :bordered="false">内置</NTag>
              <span class="text-xs text-gray-400 font-mono">{{ item.key }}</span>
            </div>
            <div class="flex items-center gap-8px">
              <NSwitch v-model:value="item.enabled" size="small" />
              <NButton v-if="!item.system" size="tiny" quaternary type="error" @click="removeItem(index)">
                <template #icon>
                  <SvgIcon icon="carbon:trash-can" />
                </template>
              </NButton>
            </div>
          </div>
          <div class="mt-12px grid grid-cols-1 gap-12px md:grid-cols-3">
            <div>
              <div class="mb-4px text-xs text-gray-400">名称</div>
              <NInput v-model:value="item.name" size="small" placeholder="自定义通知" />
            </div>
            <div>
              <div class="mb-4px text-xs text-gray-400">通知键</div>
              <NInput v-model:value="item.key" size="small" :disabled="item.system" placeholder="custom_event" />
            </div>
            <div>
              <div class="mb-4px text-xs text-gray-400">推送服务</div>
              <NSelect v-model:value="item.channel" size="small" :options="itemChannelOptions" />
            </div>
            <div>
              <div class="mb-4px text-xs text-gray-400">标题模板</div>
              <NInput v-model:value="item.titleTemplate" size="small" placeholder="{title}" />
            </div>
            <div class="md:col-span-2">
              <div class="mb-4px text-xs text-gray-400">正文模板</div>
              <NInput v-model:value="item.bodyTemplate" size="small" placeholder="{body}" />
            </div>
          </div>
        </div>
      </div>
    </NCard>

    <!-- 测试通知 -->
    <NCard title="测试通知" :bordered="false" size="small" class="card-wrapper">
      <p class="mb-12px text-sm text-gray-400">按已保存配置发送测试通知（同步返回各通道投递结果）</p>
      <div class="flex flex-wrap items-center gap-8px">
        <NSelect v-model:value="testItemKey" :options="itemOptions" placeholder="选择通知项" class="w-220px" />
        <NSelect v-model:value="testChannel" :options="testChannelOptions" class="w-160px" />
        <NButton :loading="testing" :disabled="!testItemKey" @click="handleTest">
          <template #icon>
            <SvgIcon icon="carbon:send" />
          </template>
          发送测试
        </NButton>
      </div>
      <div v-if="testResults.length > 0" class="mt-12px space-y-8px">
        <div
          v-for="r in testResults"
          :key="r.channel"
          class="flex items-center justify-between rounded-6px border border-gray-100 px-12px py-8px text-sm dark:border-gray-800"
        >
          <span>{{ CHANNEL_LABELS[r.channel] ?? r.channel }}</span>
          <span :class="r.success ? 'text-green-600' : 'text-red-500'">{{ r.message }}</span>
        </div>
      </div>
    </NCard>
  </div>
</template>

<style scoped></style>
