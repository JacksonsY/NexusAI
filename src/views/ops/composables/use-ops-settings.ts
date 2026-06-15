/** 运维监控页面本地偏好（sub2api OpsSettingsDialog 对位·localStorage 持久化）。 */
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
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return { ...defaultOpsSettings(), ...JSON.parse(raw) };
  } catch {
    // 损坏即重置
  }
  return defaultOpsSettings();
}

export function saveOpsSettings(settings: OpsPageSettings) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
}
