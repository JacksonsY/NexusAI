<script setup lang="tsx">
import { computed, onMounted, ref } from 'vue';
import { NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { fetchModels } from '@/service/api';

defineOptions({ name: 'Protocols' });

/**
 * 协议中心（只读能力矩阵·IA 第 11 域）：声明 nexusai 当前对各协议家族的支持矩阵——
 * 渠道=配置、协议家族=代码，此页是「代码侧」能力的权威说明。数据为静态事实（与后端 provider/transformer 实现一致），
 * 不可在此编辑（渠道间差异在「渠道」页编辑 protocol/modelMapping/paramOverride/headerOverride 字段；新协议家族需写 Provider/ChatTransformer SPI）。
 */
interface ProtocolRow {
  family: string;
  /** 作为上游（出站）是否原生支持 */
  outbound: 'native' | 'compatible' | 'no';
  /** 作为客户端入站端点是否支持 */
  inbound: 'native' | 'via-openai' | 'no';
  stream: boolean;
  toolCall: boolean;
  /** Canonical 跨协议互转角色 */
  canonical: string;
  /** 凭证信封 */
  envelope: string;
}

const matrix: ProtocolRow[] = [
  { family: 'OpenAI', outbound: 'native', inbound: 'native', stream: true, toolCall: true, canonical: '中枢/直采', envelope: 'Bearer' },
  { family: 'Anthropic', outbound: 'native', inbound: 'via-openai', stream: true, toolCall: true, canonical: '请求/响应/流式', envelope: 'x-api-key + version' },
  { family: 'Gemini', outbound: 'native', inbound: 'via-openai', stream: true, toolCall: true, canonical: '请求/响应/流式', envelope: '?key=' },
  { family: 'Azure OpenAI', outbound: 'native', inbound: 'native', stream: true, toolCall: true, canonical: 'OpenAI 族', envelope: 'api-key + deployment' },
  { family: 'AWS Bedrock', outbound: 'native', inbound: 'no', stream: true, toolCall: true, canonical: '—', envelope: 'SigV4 签名' },
  { family: 'Google Vertex', outbound: 'native', inbound: 'no', stream: true, toolCall: true, canonical: '—', envelope: 'OAuth(SA Token)' },
  { family: 'DeepSeek', outbound: 'compatible', inbound: 'native', stream: true, toolCall: true, canonical: 'OpenAI 族', envelope: 'Bearer' },
  { family: 'Qwen', outbound: 'compatible', inbound: 'native', stream: true, toolCall: true, canonical: 'OpenAI 族', envelope: 'Bearer' }
];

const modelCount = ref<number | null>(null);

const outboundTag = (v: ProtocolRow['outbound']) =>
  v === 'native' ? <NTag size="small" type="success">原生</NTag>
    : v === 'compatible' ? <NTag size="small" type="info">OpenAI 兼容</NTag>
      : <NTag size="small">—</NTag>;

const inboundTag = (v: ProtocolRow['inbound']) =>
  v === 'native' ? <NTag size="small" type="success">原生端点</NTag>
    : v === 'via-openai' ? <NTag size="small" type="warning">OpenAI 兼容入站</NTag>
      : <NTag size="small">仅出站</NTag>;

const boolTag = (v: boolean) =>
  v ? <NTag size="small" type="success">✓</NTag> : <NTag size="small">—</NTag>;

const columns: DataTableColumns<ProtocolRow> = [
  { title: '协议家族', key: 'family', width: 140 },
  { title: '出站(上游)', key: 'outbound', width: 130, render: r => outboundTag(r.outbound) },
  { title: '入站(客户端)', key: 'inbound', width: 150, render: r => inboundTag(r.inbound) },
  { title: '流式', key: 'stream', width: 70, render: r => boolTag(r.stream) },
  { title: 'tool-call', key: 'toolCall', width: 90, render: r => boolTag(r.toolCall) },
  { title: 'Canonical 互转', key: 'canonical', width: 150 },
  { title: '凭证信封', key: 'envelope' }
];

const nativeCount = computed(() => matrix.filter(r => r.outbound === 'native').length);

onMounted(async () => {
  try {
    const { data } = await fetchModels();
    modelCount.value = Array.isArray(data) ? data.length : (data?.length ?? 0);
  } catch {
    modelCount.value = null;
  }
});
</script>

<template>
  <NSpace vertical :size="16">
    <NGrid :cols="4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <NGridItem span="4 s:2 m:1">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <NStatistic label="支持协议家族">{{ matrix.length }}</NStatistic>
        </NCard>
      </NGridItem>
      <NGridItem span="4 s:2 m:1">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <NStatistic label="原生适配">{{ nativeCount }}</NStatistic>
        </NCard>
      </NGridItem>
      <NGridItem span="4 s:2 m:2">
        <NCard :bordered="false" size="small" class="card-wrapper">
          <NStatistic label="已注册模型(可路由)">{{ modelCount ?? '—' }}</NStatistic>
        </NCard>
      </NGridItem>
    </NGrid>

    <NCard title="协议能力矩阵" :bordered="false" size="small" class="card-wrapper">
      <NText depth="3" class="mb-2 block text-12px">
        渠道=配置 · 协议家族=代码。本页为「代码侧」能力的只读权威说明（与后端 provider/transformer 实现一致）。
        入站统一以 OpenAI 兼容端点（/v1/chat/completions 等）承接；Anthropic/Gemini 经 Canonical 互转路由到原生上游。
        新增渠道/模型在「渠道」「模型」页；渠道间差异（modelMapping/paramOverride/headerOverride）直接在「渠道」页编辑。
      </NText>
      <NDataTable
        :columns="columns"
        :data="matrix"
        :bordered="false"
        size="small"
        :row-key="r => r.family"
        :scroll-x="900"
      />
    </NCard>
  </NSpace>
</template>
