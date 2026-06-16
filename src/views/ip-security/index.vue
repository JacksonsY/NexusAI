<script setup lang="ts">
import { onMounted, ref } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  addIpBlacklist,
  addIpWhitelist,
  fetchIpBlacklist,
  fetchIpWhitelist,
  removeIpBlacklist,
  removeIpWhitelist
} from '@/service/api';

/**
 * IP 安全（内置工具·IPSecurity）：
 * 黑名单（命中拒绝一切访问·支持 TTL 自动过期·Redis）+ 白名单（豁免黑名单·支持 CIDR·KV）。
 * 数据面 5s 本地快照判定——relay/admin 入口统一拦截。
 */
defineOptions({ name: 'IpSecurity' });

const loadingBlack = ref(false);
const loadingWhite = ref(false);
const blacklist = ref<NexusAI.IpBlacklistEntry[]>([]);
const whitelist = ref<string[]>([]);

const showAddBlack = ref(false);
const blackForm = ref({ ip: '', reason: '', ttl: null as number | null });
const showAddWhite = ref(false);
const whiteForm = ref({ ip: '' });

async function loadBlacklist() {
  loadingBlack.value = true;
  const { data, error } = await fetchIpBlacklist();
  if (!error) blacklist.value = data ?? [];
  loadingBlack.value = false;
}

async function loadWhitelist() {
  loadingWhite.value = true;
  const { data, error } = await fetchIpWhitelist();
  if (!error) whitelist.value = data ?? [];
  loadingWhite.value = false;
}

function formatTtl(ttlSeconds: number) {
  if (ttlSeconds < 0) return '永久';
  if (ttlSeconds <= 0) return '即将过期';
  const days = Math.floor(ttlSeconds / 86400);
  if (days > 0) return `${days} 天`;
  const hours = Math.floor(ttlSeconds / 3600);
  if (hours > 0) return `${hours} 小时`;
  const minutes = Math.floor(ttlSeconds / 60);
  if (minutes > 0) return `${minutes} 分钟`;
  return `${ttlSeconds} 秒`;
}

async function handleAddBlacklist() {
  const { error } = await addIpBlacklist({
    ip: blackForm.value.ip.trim(),
    reason: blackForm.value.reason.trim(),
    ttlSeconds: blackForm.value.ttl ?? undefined
  });
  if (error) return;
  window.$message?.success(`IP ${blackForm.value.ip} 已加入黑名单`);
  showAddBlack.value = false;
  blackForm.value = { ip: '', reason: '', ttl: null };
  await loadBlacklist();
}

async function handleRemoveBlacklist(ip: string) {
  const { error } = await removeIpBlacklist(ip);
  if (error) return;
  window.$message?.success(`IP ${ip} 已从黑名单移除`);
  await loadBlacklist();
}

async function handleAddWhitelist() {
  const { error } = await addIpWhitelist(whiteForm.value.ip.trim());
  if (error) return;
  window.$message?.success(`${whiteForm.value.ip} 已加入白名单`);
  showAddWhite.value = false;
  whiteForm.value = { ip: '' };
  await loadWhitelist();
}

async function handleRemoveWhitelist(ip: string) {
  const { error } = await removeIpWhitelist(ip);
  if (error) return;
  window.$message?.success(`${ip} 已从白名单移除`);
  await loadWhitelist();
}

onMounted(() => {
  loadBlacklist();
  loadWhitelist();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <!-- 统计卡片 -->
    <div class="grid grid-cols-1 gap-16px md:grid-cols-2">
      <NCard :bordered="false" size="small" class="card-wrapper">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm text-gray-400">黑名单 IP 数量</p>
            <p class="mt-8px text-2xl font-bold tabular-nums">{{ blacklist.length }}</p>
          </div>
          <div class="h-48px w-48px flex-center rounded-full bg-red-500/10">
            <SvgIcon icon="carbon:close-filled" class="text-24px text-red-500" />
          </div>
        </div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm text-gray-400">白名单 IP 数量</p>
            <p class="mt-8px text-2xl font-bold tabular-nums">{{ whitelist.length }}</p>
          </div>
          <div class="h-48px w-48px flex-center rounded-full bg-primary/10">
            <SvgIcon icon="carbon:checkmark-filled" class="text-24px text-primary" />
          </div>
        </div>
      </NCard>
    </div>

    <!-- 黑名单 -->
    <NCard title="IP 黑名单" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <div class="flex items-center gap-8px">
          <NButton size="small" type="error" ghost @click="showAddBlack = true">
            <template #icon>
              <SvgIcon icon="carbon:add" />
            </template>
            添加黑名单
          </NButton>
          <NButton size="small" quaternary :loading="loadingBlack" @click="loadBlacklist">
            <template #icon>
              <SvgIcon icon="carbon:renew" />
            </template>
          </NButton>
        </div>
      </template>
      <p class="mb-12px text-sm text-gray-400">被加入黑名单的 IP 将无法访问任何接口（relay 与控制台入口统一拦截·5 秒内生效）</p>
      <NSpin :show="loadingBlack">
        <NEmpty v-if="blacklist.length === 0" description="暂无黑名单 IP" class="py-24px" />
        <NTable v-else size="small" :bordered="false" :single-line="false">
          <thead>
            <tr>
              <th>IP 地址</th>
              <th>原因</th>
              <th>剩余时长</th>
              <th class="w-100px text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in blacklist" :key="entry.ip">
              <td class="font-mono">{{ entry.ip }}</td>
              <td class="max-w-400px truncate" :title="entry.reason">{{ entry.reason || '-' }}</td>
              <td>{{ formatTtl(entry.ttlSeconds) }}</td>
              <td class="text-right">
                <NPopconfirm @positive-click="handleRemoveBlacklist(entry.ip)">
                  <template #trigger>
                    <NButton size="tiny" quaternary type="error">移除</NButton>
                  </template>
                  确定要从黑名单移除 {{ entry.ip }} 吗？
                </NPopconfirm>
              </td>
            </tr>
          </tbody>
        </NTable>
      </NSpin>
    </NCard>

    <!-- 白名单 -->
    <NCard title="IP 白名单" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <div class="flex items-center gap-8px">
          <NButton size="small" type="primary" ghost @click="showAddWhite = true">
            <template #icon>
              <SvgIcon icon="carbon:add" />
            </template>
            添加白名单
          </NButton>
          <NButton size="small" quaternary :loading="loadingWhite" @click="loadWhitelist">
            <template #icon>
              <SvgIcon icon="carbon:renew" />
            </template>
          </NButton>
        </div>
      </template>
      <p class="mb-12px text-sm text-gray-400">白名单中的 IP / 网段豁免黑名单封禁（支持单 IP 与 IPv4 CIDR 格式）</p>
      <NSpin :show="loadingWhite">
        <NEmpty v-if="whitelist.length === 0" description="暂无白名单 IP" class="py-24px" />
        <NTable v-else size="small" :bordered="false" :single-line="false">
          <thead>
            <tr>
              <th>IP 地址 / CIDR</th>
              <th class="w-100px text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ip in whitelist" :key="ip">
              <td class="font-mono">{{ ip }}</td>
              <td class="text-right">
                <NPopconfirm @positive-click="handleRemoveWhitelist(ip)">
                  <template #trigger>
                    <NButton size="tiny" quaternary type="error">移除</NButton>
                  </template>
                  确定要从白名单移除 {{ ip }} 吗？
                </NPopconfirm>
              </td>
            </tr>
          </tbody>
        </NTable>
      </NSpin>
    </NCard>

    <!-- 添加黑名单对话框 -->
    <NModal v-model:show="showAddBlack" preset="dialog" title="添加 IP 到黑名单" :show-icon="false">
      <div class="space-y-12px pt-8px">
        <div>
          <div class="mb-4px text-sm font-medium">IP 地址</div>
          <NInput v-model:value="blackForm.ip" placeholder="例如: 192.168.1.100" class="font-mono" />
        </div>
        <div>
          <div class="mb-4px text-sm font-medium">原因</div>
          <NInput v-model:value="blackForm.reason" placeholder="加入黑名单的原因" maxlength="200" />
        </div>
        <div>
          <div class="mb-4px text-sm font-medium">过期时间（可选）</div>
          <NInputNumber v-model:value="blackForm.ttl" :min="1" placeholder="留空表示永久，单位：秒" class="w-full" />
          <p class="mt-4px text-xs text-gray-400">留空表示永久封禁，或输入秒数（如 3600 表示 1 小时）</p>
        </div>
      </div>
      <template #action>
        <NButton size="small" @click="showAddBlack = false">取消</NButton>
        <NButton size="small" type="error" :disabled="!blackForm.ip || !blackForm.reason" @click="handleAddBlacklist">
          添加到黑名单
        </NButton>
      </template>
    </NModal>

    <!-- 添加白名单对话框 -->
    <NModal v-model:show="showAddWhite" preset="dialog" title="添加 IP 到白名单" :show-icon="false">
      <div class="space-y-12px pt-8px">
        <div>
          <div class="mb-4px text-sm font-medium">IP 地址或 CIDR</div>
          <NInput v-model:value="whiteForm.ip" placeholder="例如: 192.168.1.0/24 或 192.168.1.100" class="font-mono" />
          <p class="mt-4px text-xs text-gray-400">支持单个 IP 或 IPv4 CIDR 网段格式</p>
        </div>
      </div>
      <template #action>
        <NButton size="small" @click="showAddWhite = false">取消</NButton>
        <NButton size="small" type="primary" :disabled="!whiteForm.ip" @click="handleAddWhitelist">添加到白名单</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
