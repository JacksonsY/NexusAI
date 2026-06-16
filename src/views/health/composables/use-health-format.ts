/**
 * 健康监控共享判定/格式化（三卡片共用·口径catalog_helpers + 卡片组件）。
 * 后端 Long 全局序列化为字符串（防 JS 精度丢失），计数/毫秒字段入口一律 Number() 归一。
 */

type TagType = 'default' | 'success' | 'warning' | 'error';

interface HealthLike {
  totalAttempts: number | string;
  successRate: number;
}

function num(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

/** 健康标签（卡片 Badge）：无请求 / ≥0.95 正常 / ≥0.8 波动 / 其余 异常。 */
export function healthLabel(item: HealthLike) {
  if ((num(item.totalAttempts) ?? 0) <= 0) return '暂无请求';
  if (item.successRate >= 0.95) return '正常';
  if (item.successRate >= 0.8) return '波动';
  return '异常';
}

/** 健康标签的 NTag type（绿/黄/红，与标签同阈值）。 */
export function healthTagType(item: HealthLike): TagType {
  if ((num(item.totalAttempts) ?? 0) <= 0) return 'default';
  if (item.successRate >= 0.95) return 'success';
  if (item.successRate >= 0.8) return 'warning';
  return 'error';
}

/** 可用率文字色（≥0.95 绿 / ≥0.8 琥珀 / 其余红）。 */
export function successRateClass(rate: number) {
  if (rate >= 0.95) return 'text-green-600 dark:text-green-400';
  if (rate >= 0.8) return 'text-amber-600 dark:text-amber-400';
  return 'text-red-600 dark:text-red-400';
}

/** 毫秒人话：<1s → ms，<60s → s，其余 min（formatMs）。 */
export function formatMs(value?: number | string | null) {
  const n = num(value);
  if (n === null) return '-';
  const abs = Math.abs(n);
  if (abs < 1000) return `${Math.round(n)} ms`;
  if (abs < 60_000) return `${formatDurationNumber(n / 1000)} s`;
  return `${formatDurationNumber(n / 60_000)} min`;
}

function formatDurationNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: Math.abs(value) < 10 ? 2 : 1 }).format(value);
}

/** 百分比（两位小数）。 */
export function formatPercent(value?: number | null) {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return `${(value * 100).toFixed(2)}%`;
}

/** 紧凑数字（1.2万 风格·zh-CN compact）。 */
export function formatCompact(value: number | string) {
  return new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 1 }).format(num(value) ?? 0);
}
