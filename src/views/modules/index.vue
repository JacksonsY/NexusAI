<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchModuleOrder, fetchModuleStatus, saveModuleOrder, setModuleEnabled } from '@/service/api';
import ModuleSettingsDrawer from './modules/module-settings-drawer.vue';

/**
 * 模块管理（一比一 Aether ModuleManagement）：搜索栏 → 内置工具卡（跳转）→
 * 扩展模块卡片网格（状态指示点/开关/配置按钮/拖拽排序/恢复默认）。
 */
defineOptions({ name: 'Modules' });

const router = useRouter();

const loading = ref(false);
const searchQuery = ref('');
const modules = ref<NexusAI.ModuleStatus[]>([]);
const moduleOrder = ref<string[]>([]);
const orderSaving = ref(false);
const toggling = ref<Record<string, boolean>>({});
const draggedName = ref<string | null>(null);
const settingsDrawerShow = ref(false);
const settingsModuleName = ref<string | null>(null);

function openSettings(name: string) {
  // 通知服务模块配置入口 = 独立页（Aether modules/important-notification → /admin/notification-service 重定向对位）
  if (name === 'important_notification') {
    router.push('/notification-service');
    return;
  }
  settingsModuleName.value = name;
  settingsDrawerShow.value = true;
}
const dragOverName = ref<string | null>(null);

/** 内置工具（一比一 Aether BUILTIN_TOOLS：邮件配置/通知服务/IP 安全/审计日志） */
const BUILTIN_TOOLS = [
  { name: '邮件配置', description: '配置 SMTP 邮件服务，用于发送运维告警邮件（通知服务的邮件通道）', icon: 'carbon:email', href: '/email-settings' },
  { name: '通知服务', description: '管理通知项、模板和推送服务策略（邮件 / Server酱 / Bark）', icon: 'carbon:notification', href: '/notification-service' },
  { name: 'IP 安全', description: '管理 IP 黑白名单，控制系统访问权限（relay 与控制台入口统一拦截）', icon: 'carbon:security', href: '/ip-security' },
  { name: '审计日志', description: '查看控制面操作日志，追踪安全事件与变更记录', icon: 'carbon:warning-alt', href: '/audit-logs' }
];

const filteredBuiltinTools = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return BUILTIN_TOOLS;
  return BUILTIN_TOOLS.filter(t => t.name.toLowerCase().includes(q) || t.description.toLowerCase().includes(q));
});

function categoryIcon(category: string) {
  const icons: Record<string, string> = {
    performance: 'carbon:meter',
    security: 'carbon:security',
    integration: 'carbon:plug',
    monitoring: 'carbon:dashboard',
    auth: 'carbon:user-multiple'
  };
  return icons[category] || 'carbon:cube';
}

const defaultOrdered = computed(() =>
  [...modules.value].sort((a, b) => a.order - b.order || a.displayName.localeCompare(b.displayName, 'zh-Hans'))
);

const allModules = computed(() => {
  if (moduleOrder.value.length === 0) return defaultOrdered.value;
  const byName = new Map(defaultOrdered.value.map(m => [m.name, m]));
  const seen = new Set<string>();
  const ordered: NexusAI.ModuleStatus[] = [];
  for (const name of moduleOrder.value) {
    const m = byName.get(name);
    if (m && !seen.has(name)) {
      seen.add(name);
      ordered.push(m);
    }
  }
  for (const m of defaultOrdered.value) {
    if (!seen.has(m.name)) ordered.push(m);
  }
  return ordered;
});

const hasCustomOrder = computed(() => moduleOrder.value.length > 0);
const canReorder = computed(() => !orderSaving.value && allModules.value.length > 1);

const filteredModules = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return allModules.value;
  return allModules.value.filter(
    m => m.name.toLowerCase().includes(q) || m.displayName.toLowerCase().includes(q) || m.description.toLowerCase().includes(q)
  );
});

async function loadModules() {
  loading.value = true;
  const [statusRes, orderRes] = await Promise.all([fetchModuleStatus(), fetchModuleOrder()]);
  if (!statusRes.error) modules.value = statusRes.data ?? [];
  if (!orderRes.error) {
    const names = new Set((statusRes.data ?? []).map(m => m.name));
    moduleOrder.value = (orderRes.data ?? []).filter(n => names.has(n));
  }
  loading.value = false;
}

async function toggleModule(name: string, enabled: boolean) {
  toggling.value[name] = true;
  const { error } = await setModuleEnabled(name, enabled);
  toggling.value[name] = false;
  if (error) return;
  window.$message?.success(enabled ? '模块已启用' : '模块已禁用');
  await loadModules();
}

async function persistOrder(next: string[]) {
  if (orderSaving.value) return;
  const prev = [...moduleOrder.value];
  moduleOrder.value = next;
  orderSaving.value = true;
  const { error } = await saveModuleOrder(next);
  orderSaving.value = false;
  if (error) {
    moduleOrder.value = prev;
    return;
  }
  window.$message?.success(next.length === 0 ? '已恢复默认顺序' : '模块顺序已保存');
}

function resetOrder() {
  persistOrder([]);
}

function isInteractiveTarget(target: EventTarget | null) {
  return target instanceof HTMLElement && target.closest('button, a, input, [role="switch"]') !== null;
}

function onDragStart(name: string, event: DragEvent) {
  if (!canReorder.value || isInteractiveTarget(event.target)) {
    event.preventDefault();
    return;
  }
  draggedName.value = name;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', name);
  }
}

function onDragEnd() {
  draggedName.value = null;
  dragOverName.value = null;
}

function onDragOver(name: string) {
  if (!canReorder.value || !draggedName.value || draggedName.value === name) {
    dragOverName.value = null;
    return;
  }
  dragOverName.value = name;
}

function onDrop(targetName: string) {
  const dragged = draggedName.value;
  onDragEnd();
  if (!canReorder.value || !dragged || dragged === targetName) return;
  const names = allModules.value.map(m => m.name);
  const from = names.indexOf(dragged);
  const to = names.indexOf(targetName);
  if (from === -1 || to === -1) return;
  const next = [...names];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  persistOrder(next);
}

onMounted(loadModules);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="flex flex-wrap items-center justify-between gap-12px">
        <div>
          <h3 class="text-base font-semibold">模块管理</h3>
          <p class="mt-2px text-xs text-gray-400">管理系统功能模块的启用状态</p>
        </div>
        <NButton size="small" :loading="loading" @click="loadModules">
          <template #icon>
            <SvgIcon icon="carbon:renew" />
          </template>
          刷新
        </NButton>
      </div>

      <!-- 搜索栏 -->
      <NInput v-model:value="searchQuery" placeholder="搜索模块名称或描述..." clearable class="mt-16px">
        <template #prefix>
          <SvgIcon icon="carbon:search" class="text-gray-400" />
        </template>
      </NInput>

      <!-- 内置工具 -->
      <div v-if="filteredBuiltinTools.length > 0" class="mt-24px">
        <h4 class="mb-12px text-xs text-gray-400 font-semibold tracking-wider uppercase">内置工具</h4>
        <div class="grid grid-cols-1 gap-16px md:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="tool in filteredBuiltinTools"
            :key="tool.name"
            class="group cursor-pointer border border-gray-200 rounded-12px bg-white p-20px transition-all duration-200 dark:border-gray-700 dark:bg-gray-800 hover:border-primary/30 hover:shadow-lg"
            @click="router.push(tool.href)"
          >
            <div class="mb-8px flex items-start gap-12px">
              <div class="h-40px w-40px shrink-0 flex items-center justify-center rounded-10px bg-primary/15 text-primary">
                <SvgIcon :icon="tool.icon" class="text-18px" />
              </div>
              <h4 class="flex-1 truncate pt-8px text-base font-semibold">{{ tool.name }}</h4>
            </div>
            <p class="line-clamp-2 min-h-40px text-sm text-gray-400 leading-relaxed">{{ tool.description }}</p>
            <div class="mt-16px flex items-center justify-end border-t border-gray-100 pt-12px dark:border-gray-700">
              <NButton size="tiny" @click.stop="router.push(tool.href)">
                <template #icon>
                  <SvgIcon icon="carbon:settings" />
                </template>
                管理
              </NButton>
            </div>
          </div>
        </div>
      </div>

      <!-- 扩展模块 -->
      <div class="mt-24px flex items-center justify-between gap-12px">
        <h4 class="text-xs text-gray-400 font-semibold tracking-wider uppercase">扩展模块</h4>
        <NButton v-if="hasCustomOrder" size="tiny" :disabled="loading || orderSaving" @click="resetOrder">
          <template #icon>
            <SvgIcon icon="carbon:reset" />
          </template>
          恢复默认
        </NButton>
      </div>

      <div class="mt-12px grid grid-cols-1 gap-16px md:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="module in filteredModules"
          :key="module.name"
          class="group relative border rounded-12px p-20px transition-all duration-200 hover:shadow-lg"
          :class="[
            !module.available
              ? 'bg-gray-50 border-gray-200 dark:bg-gray-900 dark:border-gray-700'
              : module.active
                ? 'border-primary/40 bg-gradient-to-br from-primary/5 to-primary/10 shadow-sm'
                : 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800 hover:border-primary/30',
            draggedName === module.name ? 'opacity-70 ring-2 ring-primary/30' : '',
            dragOverName === module.name ? 'ring-2 ring-primary/40 border-primary/50' : '',
            canReorder ? 'cursor-grab active:cursor-grabbing' : ''
          ]"
          :draggable="canReorder"
          :title="orderSaving ? '正在保存排序' : '拖拽卡片调整顺序'"
          @dragstart="onDragStart(module.name, $event)"
          @dragend="onDragEnd"
          @dragover.prevent="onDragOver(module.name)"
          @dragleave="dragOverName === module.name && (dragOverName = null)"
          @drop.prevent="onDrop(module.name)"
        >
          <!-- 状态指示器 -->
          <div class="absolute right-16px top-16px">
            <div
              class="h-10px w-10px rounded-full ring-2 ring-offset-2"
              :class="{
                'bg-green-500 ring-green-500/30': module.active,
                'bg-amber-500 ring-amber-500/30': module.available && module.enabled && !module.active,
                'bg-gray-300 ring-gray-300/30': module.available && !module.enabled,
                'bg-red-400 ring-red-400/30': !module.available
              }"
            />
          </div>

          <!-- 图标和名称 -->
          <div class="mb-8px flex items-start gap-12px">
            <div
              class="h-40px w-40px shrink-0 flex items-center justify-center rounded-10px transition-colors"
              :class="module.active ? 'bg-primary/15 text-primary' : 'bg-gray-100 text-gray-400 dark:bg-gray-700'"
            >
              <SvgIcon :icon="categoryIcon(module.category)" class="text-18px" />
            </div>
            <h4 class="flex-1 truncate pr-24px pt-8px text-base font-semibold">{{ module.displayName }}</h4>
          </div>

          <!-- 描述 -->
          <p class="line-clamp-2 min-h-40px text-sm text-gray-400 leading-relaxed">{{ module.description }}</p>

          <!-- 不可用提示 -->
          <div
            v-if="!module.available"
            class="mt-12px rounded-8px bg-orange-100 px-12px py-8px text-xs text-orange-700 dark:bg-orange-950 dark:text-orange-400"
          >
            模块不可用，{{ module.configError || '请检查启动配置和依赖' }}
          </div>

          <!-- 操作区域 -->
          <div class="mt-16px flex items-center justify-between border-t border-gray-100 pt-12px dark:border-gray-700">
            <div class="flex items-center gap-12px">
              <NSwitch
                :value="module.enabled"
                size="small"
                :disabled="!module.available || !module.configValidated || toggling[module.name]"
                :loading="toggling[module.name]"
                @update:value="(val: boolean) => toggleModule(module.name, val)"
              />
              <div class="flex flex-col">
                <span class="text-sm" :class="module.enabled ? '' : 'text-gray-400'">
                  {{ module.enabled ? '启用' : '禁用' }}
                </span>
                <span v-if="module.available && !module.configValidated" class="text-xs text-gray-400">
                  {{ module.configError || '请先完成配置' }}
                </span>
              </div>
            </div>
            <NButton size="tiny" @click="openSettings(module.name)">
              <template #icon>
                <SvgIcon icon="carbon:settings" />
              </template>
              配置
            </NButton>
          </div>
        </div>
      </div>

      <!-- 搜索无结果 -->
      <div v-if="filteredModules.length === 0 && filteredBuiltinTools.length === 0 && searchQuery && !loading" class="py-64px text-center">
        <SvgIcon icon="carbon:search" class="mx-auto mb-12px text-40px text-gray-300" />
        <p class="text-gray-400">没有找到匹配的模块</p>
      </div>
    </NCard>

    <ModuleSettingsDrawer v-model:show="settingsDrawerShow" :module-name="settingsModuleName" />
  </div>
</template>

<style scoped></style>
