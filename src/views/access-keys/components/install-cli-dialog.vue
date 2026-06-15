<script setup lang="ts">
import { computed, ref } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  target: NexusAI.AccessKey | null;
}
defineProps<Props>();
const show = defineModel<boolean>('show', { default: false });

const cli = ref<'claude' | 'codex' | 'gemini'>('claude');
const system = ref<'macos' | 'linux' | 'windows'>('macos');

const cliOptions = [
  { label: 'Claude Code', value: 'claude' },
  { label: 'Codex CLI', value: 'codex' },
  { label: 'Gemini CLI', value: 'gemini' }
];
const systemOptions = [
  { label: 'macOS', value: 'macos' },
  { label: 'Linux', value: 'linux' },
  { label: 'Windows (PowerShell)', value: 'windows' }
];

const baseUrl = (import.meta.env.VITE_SERVICE_BASE_URL as string) || `${window.location.protocol}//${window.location.hostname}:8600`;

const command = computed(() => {
  const isWin = system.value === 'windows';
  const set = (k: string, v: string) => (isWin ? `$env:${k}="${v}"` : `export ${k}="${v}"`);
  const sep = isWin ? '\n' : ' \\\n  ';
  const placeholder = '<创建/轮转时复制的 sk- Key>';
  const lines: string[] = [];
  if (cli.value === 'claude') {
    lines.push(set('ANTHROPIC_BASE_URL', baseUrl));
    lines.push(set('ANTHROPIC_API_KEY', placeholder));
    return `${lines.join(sep)}${sep}claude`;
  }
  if (cli.value === 'codex') {
    lines.push(set('OPENAI_BASE_URL', `${baseUrl}/v1`));
    lines.push(set('OPENAI_API_KEY', placeholder));
    return `${lines.join(sep)}${sep}codex`;
  }
  lines.push(set('GEMINI_BASE_URL', baseUrl));
  lines.push(set('GEMINI_API_KEY', placeholder));
  return `${lines.join(sep)}${sep}gemini`;
});

async function copy() {
  await navigator.clipboard?.writeText(command.value);
  window.$message?.success('命令已复制');
}
</script>

<template>
  <NModal
    v-model:show="show"
    preset="card"
    title="一键安装并配置 CLI"
    class="w-620px max-w-[94vw]"
    :bordered="false"
    size="small"
  >
    <div class="flex flex-col gap-12px">
      <div class="flex gap-12px">
        <div class="flex-1">
          <div class="mb-4px text-12px op-60">CLI 工具</div>
          <NSelect v-model:value="cli" :options="cliOptions" size="small" />
        </div>
        <div class="flex-1">
          <div class="mb-4px text-12px op-60">操作系统</div>
          <NSelect v-model:value="system" :options="systemOptions" size="small" />
        </div>
      </div>

      <NAlert type="warning" :bordered="false" class="text-12px">
        nexusai 明文 Key 不落库，命令中的 sk- 请替换为<b>创建 / 轮转时一次性复制</b>的明文。当前密钥：{{
          target?.name ?? '-'
        }}
      </NAlert>

      <div>
        <div class="mb-4px flex items-center justify-between text-12px op-60">
          <span>配置命令</span>
          <span class="font-mono">{{ baseUrl }}</span>
        </div>
        <NInput
          :value="command"
          readonly
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 8 }"
          class="font-mono !text-12px"
        />
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-8px">
        <NButton @click="copy">
          <template #icon><SvgIcon icon="lucide:copy" /></template>
          复制命令
        </NButton>
        <NButton type="primary" @click="show = false">关闭</NButton>
      </div>
    </template>
  </NModal>
</template>
