/** 运维监控页面本地偏好（sub2api OpsSettingsDialog 对位·走 localStg 持久化·自动带 NEXUSAI_ 前缀）。 */
import { localStg } from '@/utils/storage';

export interface OpsPageSettings {
  showTokenStats: boolean;
  showAlertEvents: boolean;
  showConcurrency: boolean;
  autoRefreshSeconds: number;
}

const STORAGE_KEY = 'nexusai-ops-settings';

export function defaultOpsSettings(): OpsPageSettings {
  return { showTokenStats: true, showAlertEvents: true, showConcurrency: true, autoRefreshSeconds: 30 };
}

export function loadOpsSettings(): OpsPageSettings {
  // localStg.get 内部已 JSON 解析 + 损坏值返回 null（无需再 try/catch）
  const saved = localStg.get(STORAGE_KEY);
  return saved ? { ...defaultOpsSettings(), ...saved } : defaultOpsSettings();
}

export function saveOpsSettings(settings: OpsPageSettings) {
  localStg.set(STORAGE_KEY, settings);
}
