-- ============================================================================
-- nexusai 请求日志 ClickHouse 分析存储 DDL（log.store 三选一可替换后端）
-- 适用：nexusai.log.store=clickhouse 时的目标表；MergeTree 按天分区、按 create_dt 排序。
-- 部署：在 ClickHouse 执行本 DDL，并在网关 classpath 加入 clickhouse-jdbc 驱动 + 配 nexusai.log.clickhouse.*
-- ============================================================================

CREATE TABLE IF NOT EXISTS nai_request_log
(
    id                UInt64 DEFAULT 0,
    request_id        String,
    trace_id          String DEFAULT '',
    project_code      LowCardinality(String) DEFAULT '',
    workspace_id      Nullable(UInt64),
    channel_id        Nullable(UInt64),
    key_id            Nullable(UInt64),

    model             String DEFAULT '',
    target_model      String DEFAULT '',
    modality          LowCardinality(String) DEFAULT '',
    stream_mode       UInt8 DEFAULT 0,
    api_format        LowCardinality(String) DEFAULT '',
    endpoint_api_format LowCardinality(String) DEFAULT '',

    prompt_tokens     Nullable(UInt32),
    completion_tokens Nullable(UInt32),
    cache_tokens      Nullable(UInt32),
    cache_creation_tokens Nullable(UInt32),
    total_tokens      Nullable(UInt32),
    cost_usd          Nullable(Decimal(18, 6)),

    status            Nullable(UInt8),
    http_status       Nullable(UInt16),
    error_code        String DEFAULT '',
    retry_count       Nullable(UInt16),
    fallback_count    Nullable(UInt16),
    usage_source      LowCardinality(String) DEFAULT '',

    ttft_ms           Nullable(UInt32),
    duration_ms       Nullable(UInt32),

    client_ip         String DEFAULT '',
    user_agent        String DEFAULT '',
    client_family     LowCardinality(String) DEFAULT '',
    prompt_hash       String DEFAULT '',

    create_dt         DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(create_dt)          -- 按天分区，便于 TTL/分区裁剪
ORDER BY (create_dt, project_code, model)      -- 时间 + 常用过滤列联合排序键（分位/时序/列裁剪高效）
TTL create_dt + INTERVAL 180 DAY            -- 数据保留 180 天（按需调整）
SETTINGS index_granularity = 8192;

-- 可选：物化视图做小时级预聚合（Dashboard 趋势秒级）——按需启用
-- CREATE MATERIALIZED VIEW IF NOT EXISTS nai_request_log_hourly
-- ENGINE = SummingMergeTree PARTITION BY toYYYYMMDD(hour) ORDER BY (hour, model) AS
-- SELECT toStartOfHour(create_dt) AS hour, model,
--        count() AS total, countIf(http_status >= 400) AS errors, avg(duration_ms) AS avg_ms
-- FROM nai_request_log GROUP BY hour, model;
