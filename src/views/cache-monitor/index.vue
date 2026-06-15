<script setup lang="tsx">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NPopconfirm, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  clearAffinitySingle,
  clearAllAffinities,
  clearRedisCategory,
  fetchAffinity,
  fetchAliasSnapshot,
  fetchCacheStats,
  fetchRedisCategories,
  refreshAliasSnapshot
} from '@/service/api/ops';
import TtlAnalysisCard from './modules/ttl-analysis-card.vue';

/**
 * 缓存监控（一比一 Aether CacheMonitoring·项目制）：
 * 标题 → 4 统计卡（活跃亲和性/Provider 切换/缓存失效/预留比例）→ 亲和性列表（搜索/图标钮/秒级倒计时）→
 * 模型映射缓存（全局模型→映射模型→提供商）→ Redis 缓存管理 → TTL 分析。
 */
defineOptions({ name: 'CacheMonitor' });

// ==================== 亲和统计与列表 ====================

const loading = ref(false);
const stats = ref<NexusAI.AffinityStats | null>(null);
const affinityList = ref<NexusAI.AffinityItem[]>([]);
const keyword = ref('');
const clearingAll = ref(false);
const currentTime = ref(Date.now());
let countdownTimer: ReturnType<typeof setInterval> | null = null;
let searchTimer: ReturnType<typeof setTimeout> | null = null;

const activeList = computed(() => affinityList.value.filter(i => i.expireAt > currentTime.value));

const reservationPct = computed(() => Math.round((stats.value?.anchorMinScoreRatio ?? 0.5) * 100));

async function loadAffinity() {
  loading.value = true;
  const { data, error } = await fetchAffinity(keyword.value.trim() || undefined);
  if (!error) {
    stats.value = data?.stats ?? null;
    affinityList.value = data?.items ?? [];
    currentTime.value = Date.now();
  }
  loading.value = false;
}

function onKeywordChange() {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(loadAffinity, 600);
}

async function clearSingle(row: NexusAI.AffinityItem) {
  const { error } = await clearAffinitySingle(row.cacheKey);
  if (!error) {
    window.$message?.success('清除成功');
    await loadAffinity();
  }
}

async function clearAll() {
  clearingAll.value = true;
  const { data, error } = await clearAllAffinities();
  if (!error) {
    window.$message?.success(`已清除 ${data?.cleared ?? 0} 条缓存亲和性`);
    await loadAffinity();
  }
  clearingAll.value = false;
}

function remaining(expireAt: number): string {
  const s = Math.max(0, Math.floor((expireAt - currentTime.value) / 1000));
  if (s >= 60) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return `${s}s`;
}

/** 亲和键 "tokenId:hash" → Token 标签与会话 hash 副文 */
function tokenLabel(affinityKey: string): { token: string; session: string } {
  const idx = affinityKey.indexOf(':');
  if (idx > 0) return { token: `Token #${affinityKey.slice(0, idx)}`, session: affinityKey.slice(idx + 1) };
  return { token: `Token #${affinityKey}`, session: '---' };
}

const affinityColumns = computed<DataTableColumns<NexusAI.AffinityItem>>(() => [
  {
    title: '项目',
    key: 'projectCode',
    minWidth: 120,
    render: row => (
      <span class="text-sm font-medium" title={row.projectCode ?? undefined}>
        {row.projectName || row.projectCode || '未知'}
      </span>
    )
  },
  {
    title: 'Token',
    key: 'affinityKey',
    minWidth: 140,
    render: row => {
      const { token, session } = tokenLabel(row.affinityKey);
      return (
        <div>
          <div class="text-sm">{token}</div>
          <div class="text-xs text-gray-400 font-mono" title={row.affinityKey}>
            {session}
          </div>
        </div>
      );
    }
  },
  {
    title: 'Provider',
    key: 'channelName',
    minWidth: 110,
    render: row => (
      <span class="text-sm" title={row.channelName ?? undefined}>
        {row.channelName || '未知'}
      </span>
    )
  },
  {
    title: '模型',
    key: 'model',
    minWidth: 140,
    render: row => <span class="text-sm">{row.model || '---'}</span>
  },
  {
    title: '客户端',
    key: 'clientFamily',
    width: 100,
    render: row => (
      <NTag size="small" bordered>
        {row.clientFamily || 'unknown'}
      </NTag>
    )
  },
  {
    title: '键来源',
    key: 'keySource',
    width: 86,
    align: 'center',
    render: row => {
      const type = row.keySource === 'metadata' ? 'success' : row.keySource === 'digest' ? 'info' : 'default';
      return (
        <NTag size="small" bordered={false} type={type}>
          {row.keySource || 'token'}
        </NTag>
      );
    }
  },
  {
    title: '命中 Tokens',
    key: 'cachedTokens',
    width: 100,
    align: 'center',
    render: row => <span class="text-sm">{Number(row.cachedTokens || 0).toLocaleString()}</span>
  },
  {
    title: 'API 格式 / Key',
    key: 'apiFormat',
    minWidth: 130,
    render: row => (
      <div>
        <div class="text-sm">{row.apiFormat || '---'}</div>
        <div class="text-xs text-gray-400">Key #{row.keyId}</div>
      </div>
    )
  },
  {
    title: '剩余',
    key: 'expireAt',
    width: 80,
    align: 'center',
    render: row => <span class="text-xs">{remaining(row.expireAt)}</span>
  },
  {
    title: '次数',
    key: 'requestCount',
    width: 60,
    align: 'center',
    render: row => <span class="text-sm">{row.requestCount}</span>
  },
  {
    title: '操作',
    key: 'op',
    width: 56,
    align: 'right',
    render: row => (
      <NButton size="tiny" quaternary title="清除缓存" onClick={() => clearSingle(row)}>
        <SvgIcon icon="carbon:trash-can" class="text-14px text-gray-400 hover:text-error" />
      </NButton>
    )
  }
]);

// ==================== 模型映射缓存 ====================

const aliasLoading = ref(false);
const alias = ref<NexusAI.AliasSnapshot | null>(null);

async function loadAlias() {
  aliasLoading.value = true;
  const { data, error } = await fetchAliasSnapshot();
  if (!error) alias.value = data;
  aliasLoading.value = false;
}

async function refreshAlias() {
  aliasLoading.value = true;
  const { data, error } = await refreshAliasSnapshot();
  if (!error) {
    alias.value = data;
    window.$message?.success('映射快照已刷新');
  }
  aliasLoading.value = false;
}

const aliasColumns: DataTableColumns<NexusAI.AliasRule> = [
  {
    title: '全局模型',
    key: 'modelKey',
    minWidth: 180,
    render: row => (
      <div>
        <div class="text-sm font-medium">{row.modelAlias || row.modelKey}</div>
        {row.modelAlias && row.modelAlias !== row.modelKey && (
          <div class="text-xs text-gray-400 font-mono">{row.modelKey}</div>
        )}
      </div>
    )
  },
  {
    title: '',
    key: 'arrow',
    width: 40,
    align: 'center',
    render: () => <SvgIcon icon="carbon:arrow-right" class="inline text-14px text-gray-400" />
  },
  {
    title: '映射模型',
    key: 'pattern',
    minWidth: 220,
    render: row => <span class="text-sm font-mono">{row.pattern}</span>
  },
  {
    title: '提供商',
    key: 'providers',
    minWidth: 160,
    render: row =>
      row.providers?.length ? (
        <div class="flex flex-wrap gap-4px">
          {row.providers.slice(0, 3).map(p => (
            <NTag key={p} size="small" bordered>
              {p}
            </NTag>
          ))}
          {row.providers.length > 3 && (
            <NTag size="small" bordered>
              +{row.providers.length - 3}
            </NTag>
          )}
        </div>
      ) : (
        <span class="text-sm text-gray-400">-</span>
      )
  },
  {
    title: '剩余',
    key: 'ttl',
    width: 80,
    align: 'center',
    render: () => <span class="text-xs text-gray-400">{aliasTtl.value}</span>
  }
];

const aliasTtl = computed(() => {
  if (!alias.value?.loadedAt) return '-';
  const elapsed = Math.floor((currentTime.value - alias.value.loadedAt) / 1000);
  const left = Math.max(0, Math.floor((alias.value.refreshMs ?? 30000) / 1000) - elapsed);
  return `${left}s`;
});

// ==================== Redis 缓存管理 ====================

const redisLoading = ref(false);
const redisData = ref<NexusAI.RedisCategoriesResponse | null>(null);
const clearingCategory = ref<string | null>(null);

const redisWithKeys = computed(() => (redisData.value?.categories ?? []).filter(c => c.count > 0));
const redisEmpty = computed(() => (redisData.value?.categories ?? []).filter(c => c.count === 0));

async function loadRedis() {
  redisLoading.value = true;
  const { data, error } = await fetchRedisCategories();
  if (!error) redisData.value = data;
  redisLoading.value = false;
}

async function clearCategory(cat: NexusAI.RedisCategory) {
  clearingCategory.value = cat.key;
  const { data, error } = await clearRedisCategory(cat.key);
  if (!error) {
    window.$message?.success(`已清除 ${cat.name} 缓存（${data?.deletedCount ?? 0} 个键）`);
    await loadRedis();
  }
  clearingCategory.value = null;
}

// ==================== 磁盘缓存 ====================

const disk = ref<NexusAI.DiskCacheStats | null>(null);

async function loadDisk() {
  const { data, error } = await fetchCacheStats();
  if (!error) disk.value = data?.disk ?? null;
}

function fmtBytes(n?: number): string {
  const v = Number(n || 0);
  if (v >= 1 << 30) return `${(v / (1 << 30)).toFixed(1)} GiB`;
  if (v >= 1 << 20) return `${(v / (1 << 20)).toFixed(1)} MiB`;
  if (v >= 1 << 10) return `${(v / (1 << 10)).toFixed(1)} KiB`;
  return `${v} B`;
}

// ==================== 刷新与生命周期 ====================

const ttlCardRef = ref<InstanceType<typeof TtlAnalysisCard>>();

async function refreshAll() {
  await Promise.all([loadAffinity(), loadAlias(), loadRedis(), loadDisk()]);
  ttlCardRef.value?.reload();
}

onMounted(() => {
  refreshAll();
  countdownTimer = setInterval(() => {
    currentTime.value = Date.now();
  }, 1000);
});

onBeforeUnmount(() => {
  if (countdownTimer) clearInterval(countdownTimer);
  if (searchTimer) clearTimeout(searchTimer);
});
</script>

<template>
  <div class="flex flex-col gap-12px pb-12px">
    <!-- 标题 -->
    <div>
      <h2 class="text-xl font-bold">缓存监控</h2>
      <p class="mt-2px text-sm text-gray-400">管理缓存亲和性，提高 Prompt Caching 命中率</p>
    </div>

    <!-- 亲和系统状态 4 卡 -->
    <div class="grid grid-cols-2 gap-12px md:grid-cols-4">
      <NCard :bordered="false" size="small" class="card-wrapper">
        <div class="text-xs text-gray-400">活跃亲和性</div>
        <div class="mt-4px text-22px font-bold">{{ stats?.activeAffinities ?? 0 }}</div>
        <div class="mt-2px text-xs text-gray-400">TTL {{ stats?.ttlSeconds ?? 300 }}s · {{ stats?.storeMode ?? 'memory' }}</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper">
        <div class="text-xs text-gray-400">Provider 切换</div>
        <div class="mt-4px text-22px font-bold" :class="(stats?.providerSwitches ?? 0) > 0 ? 'text-error' : ''">
          {{ stats?.providerSwitches ?? 0 }}
        </div>
        <div class="mt-2px text-xs text-gray-400">Key 切换 {{ stats?.keySwitches ?? 0 }}</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper">
        <div class="text-xs text-gray-400">缓存失效</div>
        <div class="mt-4px text-22px font-bold" :class="(stats?.invalidations ?? 0) > 0 ? 'text-warning' : ''">
          {{ stats?.invalidations ?? 0 }}
        </div>
        <div class="mt-2px text-xs text-gray-400">因 Provider 不可用</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper">
        <div class="text-xs text-gray-400">预留比例</div>
        <div class="mt-4px text-22px font-bold">{{ reservationPct }}%</div>
        <div class="mt-2px text-xs text-gray-400">当前 {{ reservationPct }}%</div>
      </NCard>
    </div>

    <!-- 亲和性列表 -->
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="mb-8px flex flex-wrap items-center justify-between gap-8px">
        <span class="text-sm font-semibold">亲和性列表</span>
        <div class="flex items-center gap-8px">
          <NInput
            v-model:value="keyword"
            size="small"
            clearable
            placeholder="搜索项目或 Key"
            class="w-180px"
            @input="onKeywordChange"
            @clear="loadAffinity"
          >
            <template #prefix>
              <SvgIcon icon="carbon:search" class="text-gray-400" />
            </template>
          </NInput>
          <div class="hidden h-16px w-1px bg-gray-200 sm:block dark:bg-gray-700" />
          <NPopconfirm @positive-click="clearAll">
            {{ '警告：此操作会清除所有会话的缓存亲和性，确定继续吗？' }}
            <template #trigger>
              <NButton size="small" quaternary :loading="clearingAll" title="清除全部缓存">
                <SvgIcon icon="carbon:erase" class="text-14px text-gray-400 hover:text-error" />
              </NButton>
            </template>
          </NPopconfirm>
          <NButton size="small" quaternary :loading="loading" title="刷新" @click="refreshAll">
            <SvgIcon icon="carbon:renew" class="text-14px" />
          </NButton>
        </div>
      </div>
      <NDataTable
        size="small"
        :bordered="false"
        :columns="affinityColumns"
        :data="activeList"
        :loading="loading"
        :row-key="(r: NexusAI.AffinityItem) => r.cacheKey"
        :pagination="{ pageSize: 20 }"
        :scroll-x="980"
      >
        <template #empty>
          <NEmpty description="暂无缓存记录" />
        </template>
      </NDataTable>
    </NCard>

    <!-- 模型映射缓存 -->
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="mb-8px flex flex-wrap items-center justify-between gap-8px">
        <div class="flex items-center gap-8px">
          <SvgIcon icon="carbon:data-base" class="hidden text-16px text-gray-400 sm:block" />
          <span class="text-sm font-semibold">模型映射缓存</span>
        </div>
        <div class="flex items-center gap-8px">
          <NButton size="small" quaternary :loading="aliasLoading" title="刷新全部映射缓存" @click="refreshAlias">
            <SvgIcon icon="carbon:erase" class="text-14px text-gray-400 hover:text-error" />
          </NButton>
          <NButton size="small" quaternary :loading="aliasLoading" title="刷新" @click="loadAlias">
            <SvgIcon icon="carbon:renew" class="text-14px" />
          </NButton>
        </div>
      </div>
      <NDataTable
        size="small"
        :bordered="false"
        :columns="aliasColumns"
        :data="alias?.rules ?? []"
        :loading="aliasLoading"
        :row-key="(r: NexusAI.AliasRule) => r.pattern + r.modelKey"
        :scroll-x="720"
      >
        <template #empty>
          <NEmpty description="暂无模型解析缓存" />
        </template>
      </NDataTable>
    </NCard>

    <!-- Redis 缓存管理 -->
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="mb-8px flex flex-wrap items-center justify-between gap-8px">
        <div class="flex items-center gap-8px">
          <SvgIcon icon="carbon:vmdk-disk" class="hidden text-16px text-gray-400 sm:block" />
          <span class="text-sm font-semibold">Redis 缓存管理</span>
          <NTag v-if="redisData" size="small" :bordered="false">{{ redisData.totalKeys }} 个键</NTag>
        </div>
        <NButton size="small" quaternary :loading="redisLoading" title="刷新缓存分类" @click="loadRedis">
          <SvgIcon icon="carbon:renew" class="text-14px" />
        </NButton>
      </div>

      <template v-if="redisData?.available">
        <div v-if="redisWithKeys.length" class="divide-y divide-gray-100 dark:divide-gray-800">
          <div
            v-for="cat in redisWithKeys"
            :key="cat.key"
            class="flex items-center justify-between px-4px py-10px transition-colors hover:bg-gray-50 dark:hover:bg-gray-800"
          >
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-8px">
                <span class="text-sm font-medium">{{ cat.name }}</span>
                <NTag size="small" bordered>{{ cat.count }}</NTag>
              </div>
              <p class="mt-2px truncate text-xs text-gray-400">{{ cat.description }}</p>
            </div>
            <NPopconfirm v-if="cat.clearable" @positive-click="clearCategory(cat)">
              {{ `确定要清除 ${cat.name} 的所有缓存吗？共 ${cat.count} 个键。` }}
              <template #trigger>
                <NButton size="small" quaternary :loading="clearingCategory === cat.key" title="清除该分类缓存">
                  <SvgIcon icon="carbon:trash-can" class="text-14px text-gray-400 hover:text-error" />
                </NButton>
              </template>
            </NPopconfirm>
            <NTag v-else size="small" :bordered="false" type="warning">禁止清除</NTag>
          </div>
        </div>
        <div v-if="redisEmpty.length" class="mt-8px border-t border-gray-100 pt-8px dark:border-gray-800">
          <p class="text-xs text-gray-400">
            另有 {{ redisEmpty.length }} 个分类为空：{{ redisEmpty.map(c => c.name).join('、') }}
          </p>
        </div>
        <div v-if="!redisWithKeys.length && !redisEmpty.length" class="py-24px text-center">
          <SvgIcon icon="carbon:vmdk-disk" class="mx-auto mb-8px text-40px text-gray-300" />
          <p class="text-sm text-gray-400">所有缓存分类为空</p>
        </div>
      </template>
      <div v-else-if="redisData && !redisData.available" class="py-24px text-center text-sm text-gray-400">
        {{ redisData.message || 'Redis 未启用' }}
      </div>
      <div v-else-if="redisLoading" class="py-24px text-center text-sm text-gray-400">正在扫描 Redis 缓存...</div>
    </NCard>

    <!-- 磁盘缓存 -->
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="mb-8px flex items-center justify-between">
        <div class="flex items-center gap-8px">
          <SvgIcon icon="carbon:save" class="hidden text-16px text-gray-400 sm:block" />
          <span class="text-sm font-semibold">磁盘缓存</span>
          <NTag v-if="disk" size="small" :bordered="false">{{ disk.fileCount }} 个文件</NTag>
        </div>
        <NButton size="small" quaternary title="刷新" @click="loadDisk">
          <SvgIcon icon="carbon:renew" class="text-14px" />
        </NButton>
      </div>
      <div v-if="disk" class="grid grid-cols-2 gap-12px md:grid-cols-5">
        <div>
          <div class="text-xs text-gray-400">占用</div>
          <div class="text-base font-bold">{{ fmtBytes(disk.totalBytes) }}</div>
          <div class="text-xs text-gray-400">上限 {{ fmtBytes(disk.maxBytes) }}</div>
        </div>
        <div>
          <div class="text-xs text-gray-400">写入</div>
          <div class="text-base font-bold">{{ disk.writes }}</div>
        </div>
        <div>
          <div class="text-xs text-gray-400">命中</div>
          <div class="text-base font-bold">{{ disk.hits }}</div>
        </div>
        <div>
          <div class="text-xs text-gray-400">未命中</div>
          <div class="text-base font-bold">{{ disk.misses }}</div>
        </div>
        <div>
          <div class="text-xs text-gray-400">TTL</div>
          <div class="text-base font-bold">{{ disk.ttlSeconds }}s</div>
          <div class="truncate text-xs text-gray-400" :title="disk.dir">{{ disk.dir }}</div>
        </div>
      </div>
      <NEmpty v-else description="暂无磁盘缓存数据" size="small" />
    </NCard>

    <!-- TTL 分析 -->
    <TtlAnalysisCard ref="ttlCardRef" />
  </div>
</template>

<style scoped></style>
