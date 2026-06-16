/** nexusai 网关控制台业务类型聚合（declare namespace NexusAI·ambient 全局·无需 import）。单文件聚合风格。 */

/** nexusai 控制台领域类型 · 渠道域（提供商/上游凭证/连通性测试·对接 /admin/channels|channel-keys）。 */
declare namespace NexusAI {
  interface Channel {
    id?: number;
    name?: string;
    /** 备注（提供商页行内编辑） */
    description?: string;
    /** 主站链接（名称旁外链） */
    website?: string;
    /** 上游 Access Key 明文（表单透传·后端加密 upsert 首条凭证·响应不回传） */
    credential?: string;
    protocol?: string;
    vendor?: string;
    authType?: string;
    baseUrl?: string;
    status?: number;
    /** 渠道标签（JSON 数组串·供项目访问策略圈渠道） */
    tags?: string;
    weight?: number;
    priority?: number;
    modelMapping?: string;
    headerOverride?: string;
    paramOverride?: string;
    setting?: string;
    /** 计费类型（billing_type）：monthly_quota / pay_as_you_go / free_tier */
    billingType?: string;
    /** 月卡周期额度 USD */
    quotaUsd?: number | string;
    /** 月卡重置周期（天） */
    quotaResetDays?: number;
    /** 月卡当前周期开始（毫秒） */
    quotaStartAt?: number | string;
    /** 渠道过期时间（毫秒·0=永不） */
    quotaExpiresAt?: number | string;
    /** 流式首字节超时（秒·0=全局默认） */
    firstByteTimeout?: number;
    /** 非流式请求总超时（秒·0=不限） */
    requestTimeout?: number;
    [key: string]: unknown;
  }

  /** 上游模型拉取结果（FetchUpstreamModels）。 */
  interface ChannelUpstreamModels {
    ok: boolean;
    message?: string;
    /** 上游 /models 返回的模型 id（已排序） */
    models?: string[];
    /** 本渠道已注册 ability 的模型集合 */
    registered?: string[];
  }

  interface ChannelKey {
    id?: number;
    channelId?: number;
    credential?: string;
    weight?: number;
    priority?: number;
    status?: number;
    maxConcurrency?: number;
    healthScore?: number;
    [key: string]: unknown;
  }

  interface ChannelTestResult {
    ok?: boolean;
    httpStatus?: number;
    latencyMs?: number;
    keyId?: number;
    message?: string;
  }

  /** 渠道余额（余额监控列·上游 OpenAI 兼容计费端点）。 */
  interface ChannelBalance {
    ok: boolean;
    totalUsd?: number;
    usedUsd?: number;
    availableUsd?: number;
    currency?: string;
    message?: string;
  }

  /** 渠道汇总行（提供商页·providers summary：配置 + 水位 + 近 24h 流量 + 运行时健康）。 */
  interface ChannelSummary {
    channel: Channel;
    totalKeys: number | string;
    activeKeys: number | string;
    schedulableKeys: number | string;
    /** 首条凭证指纹（***尾4位·无则 null） */
    maskedCredential?: string | null;
    totalModels: number | string;
    activeModels: number | string;
    requests24h: number | string;
    /** 0-1·无流量 null */
    successRate24h?: number | null;
    lastEventAt?: string | null;
    healthScore: number;
    channelSchedulable: boolean;
  }
}
/** nexusai 控制台领域类型 · 通用（框架无关分页契约·跨域复用）。 */
declare namespace NexusAI {
  // 对齐后端框架无关分页契约 com.nexusai.common.model.PageResult（与 RequestLogPage 同形状·隔离 MyBatis-Plus）
  interface Page<T> {
    records: T[];
    total: number;
    pageNum: number;
    pageSize: number;
  }
}
/** nexusai 控制台领域类型 · 控制面契约域（①KeySync ③QuotaOp·业务面 控制面↔网关）。 */
declare namespace NexusAI {
  /** 契约①Key 同步：业务面 下发 sk- Key 投影到网关 nai_token。 */
  interface KeySync {
    keyHash: string;
    projectCode?: string;
    /** 业务面用户标识（可空·非空=Key 挂项目内用户·不存在自动建） */
    externalWorkspaceId?: string;
    groupCode?: string;
    modelLimits?: string;
    /** Key 子限额 USD（项目制：额度池在项目·此为可选子限额） */
    quotaUsd?: number;
    status?: number;
  }

}
/** nexusai 控制台领域类型 · 模型域（模型目录 GlobalModel 形态·对接 /admin/models）。 */
declare namespace NexusAI {
  interface Model {
    id?: number | string;
    modelKey?: string;
    modelAlias?: string;
    description?: string;
    modality?: string;
    contextLength?: number;
    maxOutputTokens?: number;
    /** 1=活跃 0=停用（停用后数据面 403 model_disabled） */
    status?: number;
    /** 入站别名归一正则（JSON 数组字符串·model_mappings） */
    aliasPatterns?: string;
    /** 1=允许上游元数据同步覆盖（sync_official）0=本地锁定 */
    syncOfficial?: number;
    inputRate?: number;
    outputRate?: number;
    cachedInputRate?: number;
    perRequestFee?: number;
    perImageFee?: number;
    createDt?: string;
    [key: string]: unknown;
  }

  /** 列表行 = 模型 + 三统计聚合（Long 经全局序列化为字符串·展示前 Number() 归一）。 */
  interface ModelRow {
    model: Model;
    channelCount: number | string;
    activeChannelCount: number | string;
    usageCount: number | string;
    lastUsedAt?: string | null;
  }

  /** 模型的关联渠道行（详情抽屉/关联对账）。 */
  interface ModelChannelLink {
    channelId: number | string;
    channelName: string;
    protocol?: string;
    baseUrl?: string;
    channelStatus?: number;
    abilityEnabled?: boolean;
    priority?: number;
    weight?: number;
    targetModel?: string;
  }

  /** 链路预览 ·Key 行（运行时健康/熔断态）。 */
  interface ModelRoutingKey {
    keyId: number | string;
    masked: string;
    status?: number;
    priority?: number;
    weight?: number;
    healthScore?: number;
    latencyEwmaMs?: number | string;
    schedulable?: boolean;
    blockedUntil?: number | string;
  }

  /** 链路预览 · 渠道节点。 */
  interface ModelRoutingChannel {
    channelId: number | string;
    name: string;
    baseUrl?: string;
    priority?: number;
    weight?: number;
    channelStatus?: number;
    abilityEnabled?: boolean;
    targetModel?: string;
    healthScore?: number;
    schedulable?: boolean;
    keys: ModelRoutingKey[];
  }

  /** 链路预览 · 协议家族组（api_format 组）。 */
  interface ModelRoutingGroup {
    protocol: string;
    totalChannels: number | string;
    activeChannels: number | string;
    totalKeys: number | string;
    activeKeys: number | string;
    channels: ModelRoutingChannel[];
  }

  interface ModelRouting {
    schedulingMode: string;
    priorityMode: string;
    now?: number | string;
    groups: ModelRoutingGroup[];
  }

  /** models.dev 目录（原始结构·后端代理透传）。 */
  interface ModelsDevModel {
    id?: string;
    name?: string;
    family?: string;
    reasoning?: boolean;
    tool_call?: boolean;
    attachment?: boolean;
    deprecated?: boolean;
    release_date?: string;
    input?: string[];
    output?: string[];
    cost?: { input?: number; output?: number; cache_read?: number };
    limit?: { context?: number; output?: number };
  }

  interface ModelsDevProvider {
    id?: string;
    name?: string;
    official?: boolean;
    models?: Record<string, ModelsDevModel>;
  }

  type ModelsDevCatalog = Record<string, ModelsDevProvider>;

  /** 同步预览 · 上游元数据投影（models.dev 择优条目）。 */
  interface ModelSyncMeta {
    modelKey: string;
    modelAlias?: string;
    providerId?: string;
    inputRate?: number;
    outputRate?: number;
    cachedInputRate?: number;
    contextLength?: number;
    maxOutputTokens?: number;
    modality?: string;
  }

  /** 同步预览 · 字段冲突。 */
  interface ModelSyncConflict {
    modelKey: string;
    providerId?: string;
    fields: { field: string; local?: unknown; upstream?: unknown }[];
  }

  interface ModelSyncPreview {
    missing: ModelSyncMeta[];
    conflicts: ModelSyncConflict[];
  }
}
/** nexusai 控制台领域类型 · 可观测域（仪表盘聚合/状态码分位/告警/故障转移/存量·对接 /admin/observe）。 */
declare namespace NexusAI {
  interface ObserveStats {
    hours?: number;
    total?: number;
    success?: number;
    successRate?: number;
    byStatus?: { status?: number; cnt?: number }[];
    avgDurationMs?: number;
    maxDurationMs?: number;
    p50DurationMs?: number;
    p95DurationMs?: number;
    p99DurationMs?: number;
    [key: string]: unknown;
  }

  interface ObserveAlert {
    level?: string;
    message?: string;
  }

  /** 渠道成功率排行（近 7 天·Dashboard）。 */
  interface ChannelSuccessRate {
    channelId?: number;
    channelName?: string;
    successCount?: number;
    failedCount?: number;
    successRate?: number;
  }

  /** Dashboard 运维总览聚合（去钱化）。tokenStats 键为 `${period}${Input|Output|Cached}`（period=today/week/month/all）。 */
  interface DashboardStats {
    totalRequests?: number;
    successRateAllTime?: number;
    requestStats?: {
      requestsToday?: number;
      requestsThisWeek?: number;
      requestsLastWeek?: number;
      requestsThisMonth?: number;
    };
    successStats?: {
      rateThisWeek?: number;
      rateLastWeek?: number;
      successThisWeek?: number;
      failedThisWeek?: number;
    };
    tokenStats?: Record<string, number>;
    channelSuccessRates?: ChannelSuccessRate[];
  }

  /** Dashboard 维度分析行（渠道/模型/上游凭证通用·去钱化）：key=channelId/keyId 串或 model 名（展示名由前端映射）。 */
  interface DimensionStat {
    key?: string;
    total?: number;
    success?: number;
    inputTokens?: number;
    outputTokens?: number;
    cachedTokens?: number;
    totalTokens?: number;
  }

  /** Dashboard 每日请求时序点（去钱化·面积图·请求量/Token 双轴）。 */
  interface DailyPoint {
    date?: string;
    total?: number;
    success?: number;
    inputTokens?: number;
    outputTokens?: number;
    cachedTokens?: number;
    totalTokens?: number;
  }

  /** Dashboard 性能最快榜行（fastest：吞吐 tokens/s + 置信度）。 */
  interface PerfStat {
    key?: string;
    throughput?: number;
    tokensCount?: number;
    avgTtftMs?: number;
    avgDurationMs?: number;
    requestCount?: number;
    confidence?: 'high' | 'medium' | 'low';
  }

  /** Dashboard 性能时序日点（model/channelPerformanceStats：逐日×维度吞吐/TTFT/请求量）。 */
  interface PerfDailyPoint {
    date?: string;
    key?: string;
    throughput?: number;
    ttftMs?: number;
    requestCount?: number;
  }

  interface FailoverEvent {
    id?: number;
    requestId?: string;
    attempt?: number;
    fromChannelId?: number;
    fromKeyId?: number;
    errorCode?: string;
    reason?: string;
    outcome?: string;
    createDt?: string;
    [key: string]: unknown;
  }

  interface ObserveArchive {
    store?: string;
    count?: number;
    oldest?: string;
    newest?: string;
    [key: string]: unknown;
  }
}
/** nexusai 控制台领域类型 · 健康监控域（协议端点/模型/渠道三维度时间线·对接 /admin/observe/health）。 */
declare namespace NexusAI {
  /** 健康监控公共字段：计数四件套 + 状态页式时间线（healthy/warning/unhealthy/unknown）。Long 经全局序列化为字符串。 */
  interface HealthMonitorBase {
    totalAttempts: number | string;
    successCount: number | string;
    failedCount: number | string;
    /** 无请求时为 1.0 */
    successRate: number;
    lastEventAt?: string | null;
    timeline: string[];
    timeRangeStart?: string | null;
    timeRangeEnd?: string | null;
  }

  /** 协议端点健康（按渠道协议族聚合·100 段时间线）。 */
  interface ProtocolHealth extends HealthMonitorBase {
    protocol: string;
    /** 启用渠道数（配置面计数） */
    channelCount: number;
    /** 启用上游凭证数（配置面计数) */
    keyCount: number | string;
  }

  interface ProtocolHealthResponse {
    generatedAt: string;
    formats: ProtocolHealth[];
  }

  /** 模型健康（60 段时间线·模型 Tab 与渠道下挂明细共形）。 */
  interface ModelHealth extends HealthMonitorBase {
    model: string;
    avgLatencyMs?: number | string | null;
    avgFirstByteMs?: number | string | null;
    /** 该模型近窗跑过的渠道数 */
    channelCount: number | string;
  }

  interface ModelHealthResponse {
    generatedAt: string;
    models: ModelHealth[];
  }

  /** 渠道健康（60 段时间线 + 下挂模型明细·故障排前）。 */
  interface ChannelHealth extends HealthMonitorBase {
    channelId: number | string;
    channelName: string;
    protocol: string;
    /** 1=启用 0=停用（停用但近窗有流量的也展示） */
    status?: number | null;
    avgLatencyMs?: number | string | null;
    avgFirstByteMs?: number | string | null;
    modelCount: number;
    models: ModelHealth[];
  }

  interface ChannelHealthResponse {
    generatedAt: string;
    channels: ChannelHealth[];
  }

  /** 性能详情逐桶点（perf_metrics bucketPoint·48 段·空段仅 requestCount=0）。 */
  interface PerfSeriesPoint {
    ts: string;
    requestCount: number | string;
    successRate?: number;
    avgLatencyMs?: number | string | null;
    avgTtftMs?: number | string | null;
    avgTps?: number;
  }

  /** 性能详情汇总（ModelSummary 同构）。 */
  interface PerfSeriesSummary {
    totalAttempts: number | string;
    successCount: number | string;
    failedCount: number | string;
    successRate: number;
    avgLatencyMs?: number | string | null;
    avgTtftMs?: number | string | null;
    avgTps: number;
  }

  /** 性能详情分解行（dim=model→分渠道·dim=channel→分模型·per-group 表同构）。 */
  interface PerfBreakdownRow {
    key: string;
    name: string;
    protocol?: string;
    totalAttempts: number | string;
    successCount: number | string;
    failedCount: number | string;
    successRate: number;
    avgTps: number;
    avgTtftMs?: number | string | null;
    avgLatencyMs?: number | string | null;
    timeline: string[];
    timeRangeStart?: string | null;
    timeRangeEnd?: string | null;
  }

  interface PerfSeriesResponse {
    generatedAt: string;
    dim: string;
    key: string;
    summary: PerfSeriesSummary;
    series: PerfSeriesPoint[];
    breakdown: PerfBreakdownRow[];
  }
}
/** nexusai 控制台领域类型 · 性能分析域（实时运行状态/渠道性能/分位时序/错误分布·对接 /admin/observe/perf）。 */
declare namespace NexusAI {
  /** 实时运行状态（10s 轮询）。 */
  interface PerfLive {
    generatedAt: string;
    healthScore: number;
    status: 'healthy' | 'degraded' | 'critical' | string;
    errorsLastHour: number | string;
    errorsLast24h: number | string;
    local: { inFlight: number | string; peak: number | string; total: number | string; rejected: number | string };
    slots: { occupied: number | string; schedulableKeys: number | string; totalKeys: number };
    cluster: { onlineNodes: number | string; totalNodes: number };
    fallbackTotal: number | string;
    fallbacks: { reason: string; total: number | string }[];
    recentErrors: {
      requestId?: string;
      errorCode?: string | null;
      httpStatus?: number;
      model?: string;
      channelName?: string | null;
      createDt?: string;
    }[];
    circuits: { kind: string; name?: string; channelName?: string | null; healthScore?: number }[];
    alerts: { level?: string; message?: string }[];
  }

  /** 渠道性能行（summary 与每渠道行共形·ProviderPerformanceItem）。 */
  interface PerfProviderRow {
    channelId?: string;
    channelName?: string;
    requestCount: number | string;
    successCount: number | string;
    errorCount: number | string;
    /** 百分数（0-100）·无样本 null */
    successRate?: number | null;
    avgOutputTps?: number | null;
    avgFirstByteMs?: number | string | null;
    avgResponseMs?: number | string | null;
    p90ResponseMs?: number | string | null;
    p99ResponseMs?: number | string | null;
    p90FirstByteMs?: number | string | null;
    p99FirstByteMs?: number | string | null;
    slowRequestCount: number | string;
    responseSamples: number | string;
    firstByteSamples: number | string;
  }

  /** 渠道性能时间线点（多渠道曲线）。 */
  interface PerfProviderTimelinePoint {
    ts: string;
    channelId: string;
    channelName: string;
    requestCount: number | string;
    successRate?: number;
    avgOutputTps?: number | null;
    avgFirstByteMs?: number | string | null;
  }

  interface PerfProvidersResponse {
    generatedAt: string;
    summary: PerfProviderRow;
    providers: PerfProviderRow[];
    timeline: PerfProviderTimelinePoint[];
  }

  /** 按日延迟分位行（响应 + 首字 P50/P90/P99）。 */
  interface PercentileDayRow {
    date: string;
    p50DurationMs?: number | string | null;
    p90DurationMs?: number | string | null;
    p99DurationMs?: number | string | null;
    p50TtftMs?: number | string | null;
    p90TtftMs?: number | string | null;
    p99TtftMs?: number | string | null;
  }

  interface PerfErrorsResponse {
    generatedAt: string;
    distribution: { category: string; count: number | string }[];
    trend: { date: string; total: number | string }[];
  }
}
/** nexusai 控制台领域类型 · 运维监控域（缓存命中/集群节点/模块注册表·对接 /admin/cache|nodes|modules）。 */
declare namespace NexusAI {
  interface CacheEntry {
    name: string;
    size: number;
    hitCount: number;
    missCount: number;
    hitRate: number;
    evictionCount: number;
    requestCount: number;
  }

  interface DiskCacheStats {
    dir: string;
    fileCount: number;
    totalBytes: number;
    maxBytes: number;
    writes: number;
    hits: number;
    misses: number;
    ttlSeconds: number;
  }

  interface CacheStats {
    caches: CacheEntry[];
    redis: { connected: boolean; keyCount?: number; error?: string };
    disk?: DiskCacheStats;
  }

  /** 会话亲和统计（缓存监控 4 统计卡） */
  interface AffinityStats {
    activeAffinities: number;
    ttlSeconds: number;
    stickyHits: number;
    keySwitches: number;
    providerSwitches: number;
    invalidations: number;
    anchorMinScoreRatio: number;
    storeMode: string;
  }

  /** 会话亲和条目（缓存监控·亲和性列表） */
  interface AffinityItem {
    cacheKey: string;
    affinityKey: string;
    channelId: number;
    channelName: string | null;
    keyId: number;
    projectCode: string | null;
    projectName: string | null;
    model: string | null;
    apiFormat: string | null;
    clientFamily: string | null;
    keySource: string | null;
    cachedTokens: number;
    createdAt: number;
    expireAt: number;
    requestCount: number;
  }

  interface AffinityResponse {
    stats: AffinityStats;
    items: AffinityItem[];
  }

  /** 模型别名快照（缓存监控·模型别名缓存） */
  interface AliasRule {
    pattern: string;
    modelKey: string;
    modelAlias: string | null;
    status: number | null;
    providers: string[];
  }

  interface AliasSnapshot {
    modelCount: number;
    ruleCount: number;
    loadedAt: number;
    refreshMs: number;
    rules: AliasRule[];
  }

  /** Redis 键分类（缓存监控·Redis 缓存管理） */
  interface RedisCategory {
    key: string;
    name: string;
    pattern: string;
    description: string;
    clearable: boolean;
    count: number;
  }

  interface RedisCategoriesResponse {
    available: boolean;
    message?: string;
    categories: RedisCategory[];
    totalKeys: number;
  }

  /** 缓存命中分析（缓存监控·TTL 分析头部概览） */
  interface CacheHitAnalysis {
    analysisPeriodHours: number;
    totalRequests: number;
    requestsWithCacheHit: number;
    requestCacheHitRate: number;
    totalPromptTokens: number;
    totalCacheReadTokens: number;
    tokenCacheHitRate: number;
    totalCacheCreationTokens: number;
    cacheCreationCostUsd: number;
    cacheReadCostUsd: number;
    estimatedSavingsUsd: number;
  }

  /** 项目维度 TTL 分析行 */
  interface TtlAnalysisProject {
    projectCode: string;
    projectName: string | null;
    requestCount: number;
    intervalDistribution: {
      within5min: number;
      within15min: number;
      within30min: number;
      within60min: number;
      over60min: number;
    };
    percentiles: { p50: number; p75: number; p90: number };
    avgIntervalMinutes: number;
    minIntervalMinutes: number;
    maxIntervalMinutes: number;
    recommendedTtlMinutes: number;
  }

  interface TtlAnalysisResponse {
    analysisPeriodHours: number;
    totalProjectsAnalyzed: number;
    ttlDistribution: Record<string, number>;
    projects: TtlAnalysisProject[];
  }

  interface Node {
    nodeId: string;
    host: string;
    port: number;
    version: string;
    startedAt: number | string;
    lastHeartbeat: string | null;
    status: 'online' | 'stale' | 'offline' | string;
    role: 'leader' | 'worker' | string;
    activeRequests?: number | null;
    cpuPercent?: number | null;
    heapUsedMb?: number | null;
    heapMaxMb?: number | null;
    labels?: string | null;
    registeredAt?: string | null;
  }

  interface ClusterSummary {
    total: number;
    online: number;
    leader: string | null;
    totalActiveRequests: number | string;
    circuitStateStore: 'redis' | 'memory' | string;
  }

  interface ClusterView {
    items: Node[];
    summary: ClusterSummary;
  }

  interface Modules {
    providers: { provider: string; apiFormat: string }[];
    transformers: { transformer: string; format: string }[];
    taskAdapters: string[];
    features: Record<string, boolean>;
  }
}
/** nexusai 控制台领域类型 · 项目域（工作区 + M2M 集成令牌·Project·对接 /admin/projects）。 */
declare namespace NexusAI {
  interface Project {
    /** 雪花 id（后端 Long 已序列化为字符串防 JS 精度丢失） */
    id?: number | string;
    projectCode?: string;
    name?: string;
    description?: string;
    /** 创建/轮转返回明文；列表为脱敏 ****abcd */
    token?: string;
    /** 访问策略（Profiles·JSON 数组串·空=不限）：允许的模型 */
    allowedModels?: string;
    /** 允许的能力（chat/embedding/image/rerank） */
    allowedModalities?: string;
    /** 允许的渠道 id */
    allowedChannelIds?: string;
    /** 允许的渠道标签 */
    allowedChannelTags?: string;
    /** 标签匹配模式 any/all/none */
    channelTagsMatchMode?: string;
    /** 项目 USD 额度池（DB 基线·后端序列化为美元字符串·展示前 Number()） */
    quotaUsd?: number | string;
    /** 不限额（内部自用项目·闸门不扣减） */
    unlimited?: boolean;
    /** 1=active 0=archived */
    status?: number;
    [key: string]: unknown;
  }

  /** 实时余额（契约③ balance 端点·项目池或工作区钱包·unlimited 时 balance 为 null） */
  interface QuotaBalance {
    projectCode?: string;
    externalWorkspaceId?: string;
    workspaceId?: number | string;
    unlimited?: boolean;
    /** 美元数值/字符串（如 "12.340000"）·unlimited 时为 null */
    balance?: number | string | null;
  }

  /** 项目内工作区（三层身份链·B2C 终端工作区钱包·nai_workspace） */
  interface Workspace {
    id?: number | string;
    projectCode?: string;
    /** 业务面工作区标识（项目内唯一·幂等键） */
    externalId?: string;
    displayName?: string;
    /** 工作区钱包 USD（DB 基线·后端序列化为美元字符串·展示前 Number()） */
    quotaUsd?: number | string;
    unlimited?: boolean;
    status?: number;
    createDt?: string;
    [key: string]: unknown;
  }
}
/** nexusai 控制台领域类型 · 调度策略域（能力路由表 nai_ability·租户+分组+模型→渠道·对接 /admin/routing）。 */
declare namespace NexusAI {
  interface Ability {
    groupCode?: string;
    model?: string;
    channelId?: number;
    priority?: number;
    weight?: number;
    enabled?: boolean;
    modality?: string;
    [key: string]: unknown;
  }
}
/** nexusai 控制台领域类型 · 运行时域（渠道密钥实时态：打分/熔断/并发槽/自适应RPM·对接 /admin/runtime）。 */
declare namespace NexusAI {
  interface RuntimeKey {
    keyId?: number;
    channelId?: number;
    weight?: number;
    healthScore?: number;
    latencyEwmaMs?: number;
    rpmLearned?: number;
    successCount?: number;
    errorCount?: number;
    schedulable?: boolean;
    score?: number | null;
    occupied?: number;
    [key: string]: unknown;
  }

  interface RuntimeChannel {
    channelId?: number;
    name?: string;
    protocol?: string;
    healthScore?: number;
    latencyEwmaMs?: number;
    successCount?: number;
    errorCount?: number;
    schedulable?: boolean;
    [key: string]: unknown;
  }
}
/** nexusai 控制台领域类型 · 异步任务域（多模态任务 + 幂等记录·进程内 TaskPoller·对接 /admin/tasks）。 */
declare namespace NexusAI {
  interface Task {
    id?: number;
    taskId?: string;
    upstreamTaskId?: string;
    projectCode?: string;
    channelId?: number;
    keyId?: number;
    modality?: string;
    action?: string;
    model?: string;
    status?: string;
    progress?: string;
    frozenUsd?: number;
    resultUrl?: string;
    failReason?: string;
    submitBody?: string;
    createDt?: string;
    updateDt?: string;
    [key: string]: unknown;
  }

  /** 任务状态聚合（/admin/tasks/stats·顶部统计卡）。 */
  interface TaskStats {
    /** 计数为 Long·全局序列化为字符串（雪花 id 防精度策略）。 */
    total?: number | string;
    running?: number | string;
    succeeded?: number | string;
    failed?: number | string;
    byStatus?: Record<string, number | string>;
  }
}
/** nexusai 控制台领域类型 · 独立密钥域（standalone Access Key·AccessKeys·对接 /admin/access-keys）。 */
declare namespace NexusAI {
  interface AccessKey {
    id?: number;
    /** 控制台展示名；独立签发 Key 必填，同步 Key 可空 */
    name?: string;
    keyHash?: string;
    /** 1=控制台独立签发 0=外部业务面契约同步 */
    standalone?: number;
    projectCode?: string;
    /** 归属项目内工作区（可选·非空=扣工作区钱包·空=B2B 直挂项目池） */
    workspaceId?: number | string;
    groupCode?: string;
    /** 模型白名单 JSON（空=不限） */
    modelLimits?: string;
    /** 来源 IP 白名单（JSON/逗号·空=不限） */
    allowIps?: string;
    /** 渠道/provider 白名单 JSON（空=不限） */
    allowProviders?: string;
    /** 协议入口白名单 JSON 如 ["openai","anthropic"]（空=不限） */
    allowApiFormats?: string;
    /** Key 子限额 USD（可选·NULL/≤0=不设仅受项目池·后端序列化为美元字符串） */
    quotaUsd?: number | string;
    rpmLimit?: number;
    tpmLimit?: number;
    /** 并发上限（0=不限） */
    concurrentLimit?: number;
    /** 毫秒时间戳；0/空=永不过期 */
    expiredTime?: number | string;
    /** 到期自动删除（1=删除·0=仅停用） */
    autoDeleteOnExpiry?: number;
    /** Key 级特性 JSON（含 chat_pii_redaction:{enabled,injectModelInstruction}） */
    featureSettings?: string;
    status?: number;
    createDt?: string;
    updateDt?: string;
    [key: string]: unknown;
  }

  interface AccessKeyCreateRequest {
    name: string;
    projectCode: string;
    workspaceId?: number | null;
    groupCode?: string;
    modelLimits?: string;
    allowIps?: string;
    allowProviders?: string;
    allowApiFormats?: string;
    quotaUsd?: number | null;
    rpmLimit?: number | null;
    tpmLimit?: number | null;
    concurrentLimit?: number | null;
    expiredTime?: number | null;
    autoDeleteOnExpiry?: number;
    featureSettings?: string;
  }

  interface AccessKeyUpdateRequest extends AccessKeyCreateRequest {
    status?: number;
  }

  interface AccessKeyCreateResponse {
    id?: number | string;
    key?: string;
    keyHash?: string;
    name?: string;
  }

  interface AccessKeyRotateResponse {
    id?: number | string;
    key?: string;
    keyHash?: string;
  }
}
/** nexusai 控制台领域类型 · 使用记录域（用量明细 + 请求日志·贯穿钻取·对接 /admin/usage|observe/request）。 */
declare namespace NexusAI {
  interface UsageLog {
    id?: number;
    requestId?: string;
    projectCode?: string;
    channelId?: number;
    keyId?: number;
    model?: string;
    promptTokens?: number;
    completionTokens?: number;
    totalTokens?: number;
    costUsd?: number;
    status?: number;
    createDt?: string;
    [key: string]: unknown;
  }

  interface RequestLog {
    id?: number | string;
    requestId?: string;
    traceId?: string;
    projectCode?: string;
    channelId?: number | string;
    keyId?: number | string;
    model?: string;
    targetModel?: string;
    modality?: string;
    streamMode?: boolean;
    /** 入站 API 格式（openai:chat/claude:messages/...·api_format） */
    apiFormat?: string;
    /** 上游端点 API 格式（与 apiFormat 不同即发生格式转换） */
    endpointApiFormat?: string;
    promptTokens?: number;
    completionTokens?: number;
    cacheTokens?: number;
    totalTokens?: number;
    costUsd?: number | string;
    status?: number;
    httpStatus?: number;
    errorCode?: string;
    /** 同渠道换 Key 重试次数（>0 = 发生重试） */
    retryCount?: number;
    /** 跨渠道故障转移次数（>0 = 发生转移） */
    fallbackCount?: number;
    /** usage 结算来源（client-abort = 已取消） */
    usageSource?: string;
    ttftMs?: number | string;
    durationMs?: number | string;
    clientIp?: string;
    /** 客户端 User-Agent */
    userAgent?: string;
    /** UA 推导客户端家族（claude_code/codex/cursor/...） */
    clientFamily?: string;
    promptHash?: string;
    createDt?: string;
    /** 进行中状态（仅活跃合并行有值：pending/streaming·非落库字段） */
    liveStatus?: 'pending' | 'streaming';
    /** 进行中已耗时 ms（仅活跃合并行有值） */
    elapsedMs?: number;
    [key: string]: unknown;
  }

  /** 用量维度聚合行（用量分析面板·aggregation/stats） */
  interface UsageAggItem {
    key?: string;
    label?: string;
    requests?: number | string;
    success?: number | string;
    errorCount?: number | string;
    successRate?: number;
    promptTokens?: number | string;
    completionTokens?: number | string;
    cacheTokens?: number | string;
    totalTokens?: number | string;
    costUsd?: number | string;
    avgDurationMs?: number | string | null;
    cacheHitRate?: number;
  }

  /** 活跃度热图（/usage/heatmap·365 天） */
  interface UsageHeatmap {
    startDate?: string;
    endDate?: string;
    totalDays?: number | string;
    activeDays?: number | string;
    maxRequests?: number | string;
    days?: { date: string; requests: number | string; totalTokens: number | string; costUsd: number | string }[];
  }

  /** 请求间隔时间线（interval-timeline·按用户分组散点） */
  interface IntervalTimeline {
    analysisPeriodHours?: number;
    totalPoints?: number;
    points?: { x: string; y: number; model?: string; projectCode?: string }[];
    projects?: Record<string, string>;
    models?: string[];
  }

  /** 进行中请求（/usage/active·进程内注册表快照） */
  interface ActiveRequest {
    requestId?: string;
    status?: 'pending' | 'streaming';
    projectCode?: string;
    channelId?: number | string;
    keyId?: number | string;
    model?: string;
    targetModel?: string;
    modality?: string;
    streamMode?: boolean;
    apiFormat?: string;
    endpointApiFormat?: string;
    clientIp?: string;
    userAgent?: string;
    clientFamily?: string;
    httpStatus?: number;
    ttftMs?: number | string;
    elapsedMs?: number | string;
    createDt?: string;
  }

  /** 调度策略单元（优先级模式 × 调度策略） */
  interface RoutingPolicy {
    priorityMode?: 'provider' | 'key';
    schedulingMode?: 'cache_affinity' | 'load_balance' | 'fixed_order';
  }

  /** 调度策略配置 JSON（nai_routing_group.config_json） */
  interface RoutingGroupConfig {
    defaultPolicy?: RoutingPolicy;
    modelPolicies?: (RoutingPolicy & { model?: string })[];
  }

  /** 调度策略分组（routing_groups） */
  interface RoutingGroup {
    /** 雪花 id（后端序列化为字符串防精度丢失） */
    id?: number | string;
    name?: string;
    description?: string;
    enabled?: boolean;
    isSystemDefault?: boolean;
    configJson?: string;
    version?: number;
    createDt?: string;
    updateDt?: string;
  }

  // ===== 代理节点 / 模块管理 / 系统设置 =====

  /** 出口代理节点 */
  interface ProxyNode {
    id: string;
    name: string;
    proxyUrl: string;
    username?: string | null;
    hasPassword?: boolean;
    region?: string | null;
    status: 'online' | 'offline' | string;
    totalRequests?: number | string;
    failedRequests?: number | string;
    avgLatencyMs?: number | null;
    lastProbeAt?: string | null;
    lastOkAt?: string | null;
    createDt?: string | null;
  }

  interface ProxyNodeDetail extends ProxyNode {
    password?: string | null;
  }

  interface ProxyNodeUpsert {
    name?: string;
    proxyUrl: string;
    username?: string;
    password?: string;
    region?: string;
  }

  interface ProxyTestResult {
    success: boolean;
    latencyMs?: number;
    exitIp?: string;
    error?: string;
    probeUrl?: string;
    timeoutSecs?: number;
  }

  interface ProxyNodeMetricBucket {
    bucketStart: string;
    samples: number;
    uptimeRatio: number | null;
    latencyAvgMs: number | null;
    latencyMaxMs: number;
    errors: number;
  }

  interface ProxyNodeMetrics {
    items: ProxyNodeMetricBucket[];
    summary: {
      samples: number;
      okSamples: number;
      uptimeRatio: number | null;
      latencyAvgMs: number | null;
      latencyMaxMs: number;
      errors: number;
    };
  }

  interface ProxyNodeEvent {
    id: string;
    eventType: 'connected' | 'disconnected' | 'error' | string;
    detail?: string | null;
    createDt?: string | null;
  }

  /** 功能模块状态（available/enabled/active 三态） */
  interface ModuleStatus {
    name: string;
    displayName: string;
    description: string;
    category: string;
    available: boolean;
    enabled: boolean;
    active: boolean;
    configValidated: boolean;
    configError?: string | null;
    adminRoute?: string | null;
    order: number;
  }

  /** 模块参数（schema 驱动设置抽屉·hot=保存后 5s 热生效 / readonly=启动配置只读） */
  interface ModuleParam {
    key: string;
    label: string;
    type: 'number' | 'bool' | 'readonly' | string;
    defaultValue?: unknown;
    value?: unknown;
    unit?: string | null;
    hint?: string | null;
    hot: boolean;
    min?: number;
    max?: number;
    /** secret 类型：是否已配置（值永不回显） */
    configured?: boolean;
  }

  interface ModuleSettings {
    name: string;
    displayName: string;
    adminRoute?: string | null;
    params: ModuleParam[];
    metrics: { label: string; value: number | string }[];
  }

  interface SystemVersion {
    version: string;
    springBoot: string;
    java: string;
    uptimeMs: number;
    startedAt: number;
  }

  interface ConfigImportStats {
    [entity: string]: { created: number; updated: number; skipped: number };
  }

  /** 邮件配置（内置工具·SMTP） */
  interface EmailConfig {
    host: string;
    port: number;
    user: string;
    passwordSet: boolean;
    fromEmail: string;
    fromName: string;
    encryption: 'ssl' | 'tls' | 'none';
    sentCount: number;
    failedCount: number;
  }

  /** IP 黑名单条目（ttlSeconds -1=永久） */
  interface IpBlacklistEntry {
    ip: string;
    reason: string;
    ttlSeconds: number;
  }

  /** 审计日志条目 */
  interface AuditLog {
    id: string;
    actor: string;
    eventType: string;
    description: string;
    path: string | null;
    method: string | null;
    ip: string | null;
    statusCode: number | null;
    errorMessage: string | null;
    createDt: string;
  }

  /** 通知项（key/名称/通道/模板） */
  interface NotificationItem {
    key: string;
    name: string;
    enabled: boolean;
    channel: 'global' | 'all' | 'email' | 'server_chan' | 'bark';
    titleTemplate: string;
    bodyTemplate: string;
    system: boolean;
  }

  /** 通知服务配置 */
  interface NotificationConfig {
    enabled: boolean;
    emailEnabled: boolean;
    emailRecipients: string;
    defaultChannel: 'all' | 'email' | 'server_chan' | 'bark';
    items: NotificationItem[];
    readiness: {
      emailReady: boolean;
      smtpConfigured: boolean;
      serverChanReady: boolean;
      barkReady: boolean;
    };
    sentCount: number;
    failedCount: number;
  }

  interface NotificationTestResult {
    channel: string;
    success: boolean;
    message: string;
  }

  // ===== 运维监控 Ops Monitor =====
  interface OpsOverview {
    generatedAt: string;
    rangeMinutes: number;
    requestCount: number;
    successCount: number;
    errorCount: number;
    successRate: number;
    errorRate: number;
    tokenConsumed: number;
    duration: { avg: number | null; p90: number | null; p99: number | null };
    ttft: { avg: number | null; p90: number | null; p99: number | null };
    traffic: {
      qps: { current: number; peak: number; avg: number };
      tps: { current: number; peak: number; avg: number };
    };
    concurrency: { inFlight: number; peak: number; rejected: number };
    system: {
      cpuUsagePercent: number | null;
      heapUsedMb: number;
      heapMaxMb: number;
      memoryUsagePercent: number;
      threadCount: number;
      dbOk: boolean;
      redisOk: boolean;
      onlineNodes: number;
      totalNodes: number;
      dbPool?: { active: number; idle: number; total: number; max: number; awaiting: number };
      jobs?: { name: string; lastRunDt: string; ok: boolean }[];
    };
    topChannels: { channelId: number; total: number; errors: number }[];
    thresholds: OpsThresholds;
    healthScore: number;
    diagnosis: { level: string; message: string }[];
    firingAlerts: number;
  }

  /** 实时流量窗口（sub2api realtime traffic summary 对位） */
  interface OpsRealtimeTraffic {
    windowSeconds: number;
    qps: { current: number; peak: number; avg: number };
    tps: { current: number; peak: number; avg: number };
  }

  /** 告警评估设置（开关 + 评估间隔秒） */
  interface OpsAlertSettings {
    enabled: boolean;
    intervalSeconds: number;
  }

  interface OpsTrendPoint {
    ts: string;
    requestCount: number;
    errorCount: number;
    upstreamErrorCount: number;
    limitedErrorCount: number;
    qps: number;
    tps: number;
    failoverCount: number;
    failoverRate: number;
  }

  interface OpsTrend {
    generatedAt: string;
    segmentSeconds: number;
    points: OpsTrendPoint[];
  }

  interface OpsLatencyBucket {
    bucket: string;
    count: number;
  }

  interface OpsErrorCategory {
    category: string;
    count: number;
  }

  interface OpsThresholds {
    slaPercentMin: number;
    errorRatePercentMax: number;
    p99LatencyMsMax: number;
    ttftP99MsMax: number;
  }

  interface AlertRule {
    id: number;
    name: string;
    description: string | null;
    enabledFlag: number;
    metricType: string;
    operator: string;
    threshold: number;
    windowMinutes: number;
    sustainedMinutes: number;
    cooldownMinutes: number;
    severity: string;
    notifyFlag: number;
    lastTriggeredDt: string | null;
    createDt?: string;
  }

  interface AlertRuleTestResult {
    metricType: string;
    windowMinutes: number;
    value: number | null;
    breached: boolean;
  }

  interface AlertEvent {
    id: number;
    ruleId: number;
    ruleName: string | null;
    severity: string;
    status: string;
    title: string | null;
    description: string | null;
    metricValue: number | null;
    thresholdValue: number | null;
    firedDt: string | null;
    resolvedDt: string | null;
    notifiedFlag: number;
    createDt?: string;
  }
}
