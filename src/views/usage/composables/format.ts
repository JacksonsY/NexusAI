/** 使用记录页共享格式化（index 与 modules 子组件共用·Long 全局序列化为字符串需 num() 归一）。 */

export function num(v: number | string | null | undefined): number {
  if (v == null || v === '') return 0;
  return typeof v === 'number' ? v : Number(v);
}

export function fmtCount(v: number | string | null | undefined): string {
  return num(v).toLocaleString('en-US');
}

/** 美元费用展示（后端回美元数值/字符串·单请求成本保留 6 位小数）。 */
export function fmtUsd(v: number | string | null | undefined): string {
  return `$${num(v).toFixed(6)}`;
}

/** token 量级缩写（一比一 Aether formatTokens：1.5K / 1.2M）。 */
export function fmtTokens(v: number | string | null | undefined): string {
  const n = num(v);
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}K`;
  return String(n);
}

export function fmtMs(v: number | string | null | undefined): string {
  const n = num(v);
  if (!n) return '-';
  return n >= 1000 ? `${(n / 1000).toFixed(2)}s` : `${n}ms`;
}

/** 缓存命中率（一比一 Aether formatHitRate：无数据显示 -）。 */
export function fmtHitRate(rate: number | null | undefined): string {
  if (rate == null || Number.isNaN(rate)) return '-';
  return `${rate.toFixed(1)}%`;
}

/** 客户端家族标识 → 展示名（一比一 Aether formatClientFamily）。 */
const CLIENT_FAMILY_LABELS: Record<string, string> = {
  codex: 'Codex',
  codex_vscode: 'Codex VS Code',
  claude_code: 'Claude Code',
  opencode: 'OpenCode',
  gemini_cli: 'Gemini CLI',
  openai_js_sdk: 'OpenAI JS SDK',
  openai_python_sdk: 'OpenAI Python SDK',
  anthropic_js_sdk: 'Anthropic JS SDK',
  anthropic_python_sdk: 'Anthropic Python SDK',
  qwen_code: 'Qwen Code',
  roo_code: 'Roo Code',
  kilocode: 'KiloCode',
  cherrystudio: 'Cherry Studio',
  openui: 'OpenUI',
  cursor: 'Cursor',
  windsurf: 'Windsurf',
  continue: 'Continue',
  cline: 'Cline',
  aider: 'Aider',
  langchain: 'LangChain',
  llamaindex: 'LlamaIndex',
  sdk: 'SDK',
  generic: 'generic'
};

export function formatClientFamily(value?: string | null): string {
  const v = (value || '').trim().toLowerCase();
  if (!v) return 'unknown';
  return CLIENT_FAMILY_LABELS[v] ?? v;
}
