/**
 * 模型管理共享格式化（口径ModelManagement）。
 * 后端 Long 全局序列化为字符串（防 JS 精度丢失），计数字段入口一律 Number() 归一。
 */

export function num(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

/** 调用次数缩写（formatUsageCount：1.2k / 3.4M）。 */
export function fmtUsageCount(value?: number | string | null): string {
  const n = num(value);
  if (n === null || n === 0) return '0';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** 价格列多行文本（In/Out + 按次 + 按图·全空回 []）。 */
export function priceLine(model: NexusAI.Model): string[] {
  const lines: string[] = [];
  const input = num(model.inputRate);
  const output = num(model.outputRate);
  if (input !== null || output !== null) {
    lines.push(`In: ${input?.toFixed(2) ?? '-'} / Out: ${output?.toFixed(2) ?? '-'}`);
  }
  const perReq = num(model.perRequestFee);
  if (perReq) lines.push(`按次: $${perReq.toFixed(3)}/次`);
  const perImg = num(model.perImageFee);
  if (perImg) lines.push(`按图: $${perImg.toFixed(3)}/张`);
  return lines;
}

/** 上下文长度缩写（128000 → 128K / 1000000 → 1M）。 */
export function fmtTokens(value?: number | string | null): string {
  const n = num(value);
  if (n === null || n === 0) return '-';
  if (n >= 1_000_000) return `${n / 1_000_000}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}K`;
  return String(n);
}

/** 解析 aliasPatterns JSON 数组（容错回 []）。 */
export function parseAliasPatterns(json?: string | null): string[] {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr.filter(x => typeof x === 'string') : [];
  } catch {
    return [];
  }
}
