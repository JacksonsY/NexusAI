/** The storage namespace */
declare namespace StorageType {
  interface Session {
    /** The theme color */
    themeColor: string;
    // /**
    // * the theme settings
    // */
    // themeSettings: App.Theme.ThemeSetting;
  }

  interface Local {
    /** The i18n language */
    lang: App.I18n.LangType;
    /** The token */
    token: string;
    /** Fixed sider with mix-menu */
    mixSiderFixed: CommonType.YesOrNo;
    /** The theme color */
    themeColor: string;
    /** The dark mode */
    darkMode: boolean;
    /** The theme settings */
    themeSettings: App.Theme.ThemeSetting;
    /**
     * The override theme flags
     *
     * The value is the build time of the project
     */
    overrideThemeFlag: string;
    /** The global tabs */
    globalTabs: App.Global.Tab[];
    /** The backup theme setting before is mobile */
    backupThemeSettingBeforeIsMobile: {
      layout: UnionKey.ThemeLayoutMode;
      siderCollapse: boolean;
    };
    /** The last login user id */
    lastLoginUserId: string;
    /** nexusai · Usage 页每页条数 */
    'usage-page-size': number;
    /** nexusai · Usage 页可见列 key 列表 */
    'usage-visible-columns-v2': string[];
    /** nexusai · Usage 页隐藏 unknown 记录开关 */
    'usage-hide-unknown-records': boolean;
    /** nexusai · 运维监控页本地偏好（结构对齐 OpsPageSettings） */
    'nexusai-ops-settings': {
      showTokenStats: boolean;
      showAlertEvents: boolean;
      showConcurrency: boolean;
      autoRefreshSeconds: number;
    };
  }
}
