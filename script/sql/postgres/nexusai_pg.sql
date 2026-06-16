-- nexusai 独立库 DDL · PostgreSQL 方言版（与 MySQL 版 script/sql/nexusai.sql 同构）
-- 方言移植要点（vs MySQL）：
--   TINYINT→SMALLINT；DATETIME→TIMESTAMP；去掉 ON UPDATE CURRENT_TIMESTAMP（update_dt 由 MyBatis-Plus MetaObjectHandler 应用层填充）；
--   无反引号/ENGINE；非唯一索引拆成独立 CREATE INDEX；表注释用 COMMENT ON TABLE；雪花 ID 由应用生成（非 SERIAL）。
--   切到 PG 部署时还需：application.yml 设 nexusai.db.type=postgresql（分页方言）+ datasource 指向 PG + classpath 含 org.postgresql 驱动。
-- 建库（PG 不支持 CREATE DATABASE IF NOT EXISTS，按需手工执行）：
--   CREATE DATABASE nexusai WITH ENCODING 'UTF8';
--   \c nexusai

COMMENT ON TABLE nai_workspace IS '项目内工作区(B2C终端工作区钱包·按工作区隔离)';

-- API Key 鉴权 + USD 额度运行时真理源
CREATE TABLE IF NOT EXISTS nai_token (
  id                  BIGINT        NOT NULL,
  key_hash            VARCHAR(64)   NOT NULL,
  name                VARCHAR(100)  DEFAULT NULL,
  standalone          SMALLINT      DEFAULT 0,
  project_code        VARCHAR(64)   NOT NULL,
  workspace_id        BIGINT        DEFAULT NULL,
  group_code          VARCHAR(64)   DEFAULT 'default',
  model_limits        TEXT          DEFAULT NULL,
  allow_ips           VARCHAR(512)  DEFAULT NULL,
  allow_providers     VARCHAR(512)  DEFAULT NULL,
  allow_api_formats   VARCHAR(256)  DEFAULT NULL,
  quota_usd           DECIMAL(18,6) DEFAULT NULL,
  rpm_limit           INT           DEFAULT 0,
  tpm_limit           INT           DEFAULT 0,
  concurrent_limit    INT           DEFAULT 0,
  byok_credential     VARCHAR(1024) DEFAULT NULL,
  expired_time        BIGINT        DEFAULT 0,
  auto_delete_on_expiry SMALLINT    DEFAULT 0,
  feature_settings    TEXT          DEFAULT NULL,
  status              SMALLINT      DEFAULT 1,
  create_dt           TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt           TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_key_hash UNIQUE (key_hash)
);
COMMENT ON TABLE nai_token IS 'API Key + USD 额度';
CREATE INDEX IF NOT EXISTS idx_token_project_standalone ON nai_token (project_code, standalone);

-- 项目（工作区+控制面集成边界）：接入方凭 (project_code,token) 调 /admin/**·替代单静态 admin token
CREATE TABLE IF NOT EXISTS nai_project (
  id           BIGINT       NOT NULL,
  project_code VARCHAR(64)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  description  VARCHAR(512) DEFAULT NULL,
  token        VARCHAR(128) NOT NULL,
  scope        VARCHAR(16)  DEFAULT 'project',
  allowed_models          TEXT         DEFAULT NULL,
  allowed_modalities      VARCHAR(256) DEFAULT NULL,
  allowed_channel_ids     TEXT         DEFAULT NULL,
  allowed_channel_tags    TEXT         DEFAULT NULL,
  channel_tags_match_mode VARCHAR(16)  DEFAULT 'any',
  quota_usd    DECIMAL(18,6) DEFAULT 0,
  unlimited    SMALLINT     DEFAULT 0,
  rpm_limit    INT          DEFAULT 0,
  tpm_limit    INT          DEFAULT 0,
  status       SMALLINT     DEFAULT 1,
  create_dt    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE (project_code),
  UNIQUE (token)
);

-- 项目内工作区（三层身份链·B2C 终端工作区钱包）
CREATE TABLE IF NOT EXISTS nai_workspace (
  id            BIGINT       NOT NULL PRIMARY KEY,
  project_code  VARCHAR(64)  NOT NULL,
  external_id   VARCHAR(256) NOT NULL,
  display_name  VARCHAR(128) DEFAULT NULL,
  quota_usd     DECIMAL(18,6) DEFAULT 0,
  unlimited     SMALLINT     DEFAULT 0,
  rpm_limit     INT          DEFAULT 0,
  tpm_limit     INT          DEFAULT 0,
  status        SMALLINT     DEFAULT 1,
  create_dt     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (project_code, external_id)
);
COMMENT ON TABLE nai_project IS '项目(工作区+控制面集成边界)';

-- 网关实例节点（多实例集群视图·守护线程心跳 upsert·update_dt=末次心跳）
-- 调度策略分组
CREATE TABLE IF NOT EXISTS nai_routing_group (
  id                BIGINT       NOT NULL,
  name              VARCHAR(128) NOT NULL,
  description       VARCHAR(512) DEFAULT NULL,
  enabled           SMALLINT     DEFAULT 1,
  is_system_default SMALLINT     DEFAULT 0,
  config_json       TEXT         DEFAULT NULL,
  version           INT          DEFAULT 1,
  create_dt         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE nai_routing_group IS '调度策略分组';

CREATE TABLE IF NOT EXISTS nai_node (
  id         BIGINT       NOT NULL,
  node_id    VARCHAR(128) NOT NULL,
  host       VARCHAR(64)  DEFAULT NULL,
  port       INT          DEFAULT NULL,
  version    VARCHAR(64)  DEFAULT NULL,
  started_at BIGINT       DEFAULT NULL,
  create_dt  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE (node_id)
);
COMMENT ON TABLE nai_node IS '网关实例节点(集群心跳)';

-- 渠道（上游入口·配置化三档；凭证在 nai_channel_key）
CREATE TABLE IF NOT EXISTS nai_channel (
  id                       BIGINT        NOT NULL,
  name                     VARCHAR(100)  NOT NULL,
  description              VARCHAR(255)  DEFAULT NULL,
  website                  VARCHAR(255)  DEFAULT NULL,
  protocol                 VARCHAR(32)   DEFAULT 'openai',
  vendor                   VARCHAR(64)   DEFAULT NULL,
  auth_type                VARCHAR(32)   DEFAULT 'bearer',
  base_url                 VARCHAR(255)  NOT NULL,
  status                   SMALLINT      DEFAULT 1,
  weight                   INT           DEFAULT 0,
  priority                 INT           DEFAULT 0,
  auto_ban                 SMALLINT      DEFAULT 1,
  tags                    TEXT         DEFAULT NULL,
  model_mapping            TEXT          DEFAULT NULL,
  param_override           TEXT          DEFAULT NULL,
  header_override          TEXT          DEFAULT NULL,
  status_code_mapping      VARCHAR(1024) DEFAULT NULL,
  setting                  TEXT          DEFAULT NULL,
  endpoints                TEXT          DEFAULT NULL,
  billing_type             VARCHAR(32)   DEFAULT 'pay_as_you_go',
  quota_usd                NUMERIC(18,6) DEFAULT NULL,
  quota_reset_days         INT           DEFAULT NULL,
  quota_start_at           BIGINT        DEFAULT 0,
  quota_expires_at         BIGINT        DEFAULT 0,
  first_byte_timeout       INT           DEFAULT 0,
  request_timeout          INT           DEFAULT 0,
  rate_limit_reset_at      BIGINT        DEFAULT 0,
  overload_until           BIGINT        DEFAULT 0,
  temp_unschedulable_until BIGINT        DEFAULT 0,
  health_score             INT           DEFAULT 100,
  success_count            BIGINT        DEFAULT 0,
  error_count              BIGINT        DEFAULT 0,
  create_dt                TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt                TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE nai_channel IS '渠道';

-- 渠道密钥池（Key 池）：渠道内多 Key 凭证(AES-256-GCM)
CREATE TABLE IF NOT EXISTS nai_channel_key (
  id                       BIGINT    NOT NULL,
  channel_id               BIGINT    NOT NULL,
  credential               TEXT      NOT NULL,
  weight                   INT       DEFAULT 0,
  priority                 INT       DEFAULT 0,
  status                   SMALLINT  DEFAULT 1,
  rpm_learned              INT       DEFAULT 0,
  max_concurrency          INT       DEFAULT 0,
  rate_limit_reset_at      BIGINT    DEFAULT 0,
  overload_until           BIGINT    DEFAULT 0,
  temp_unschedulable_until BIGINT    DEFAULT 0,
  health_score             INT       DEFAULT 100,
  success_count            BIGINT    DEFAULT 0,
  error_count              BIGINT    DEFAULT 0,
  create_dt                TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_dt                TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE nai_channel_key IS '渠道密钥池(Key 池)';
CREATE INDEX IF NOT EXISTS idx_channel ON nai_channel_key (channel_id);

-- 路由索引(abilities)
CREATE TABLE IF NOT EXISTS nai_ability (
  group_code VARCHAR(64)  NOT NULL,
  model      VARCHAR(128) NOT NULL,
  channel_id BIGINT       NOT NULL,
  priority   INT          DEFAULT 0,
  weight     INT          DEFAULT 0,
  enabled    SMALLINT     DEFAULT 1,
  modality   VARCHAR(32)  DEFAULT 'CHAT',
  create_dt  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (group_code, model, channel_id)
);
COMMENT ON TABLE nai_ability IS '路由索引';

-- 模型元数据(USD 价格由控制面下发)
CREATE TABLE IF NOT EXISTS nai_model (
  id              BIGINT        NOT NULL,
  model_key       VARCHAR(128)  NOT NULL,
  model_alias     VARCHAR(128)  DEFAULT NULL,
  description     VARCHAR(500)  DEFAULT NULL,
  modality        VARCHAR(32)   DEFAULT 'CHAT',
  context_length  INT           DEFAULT 0,
  max_output_tokens INT         DEFAULT NULL,
  capabilities    TEXT          DEFAULT NULL,
  enable_groups   VARCHAR(255)  DEFAULT NULL,
  status          SMALLINT      NOT NULL DEFAULT 1,
  sync_official   SMALLINT      NOT NULL DEFAULT 1,
  alias_patterns  VARCHAR(1000) DEFAULT NULL,
  cost_basis      DECIMAL(20,8) DEFAULT 0,
  input_rate        DECIMAL(20,8) DEFAULT NULL,
  output_rate       DECIMAL(20,8) DEFAULT NULL,
  cached_input_rate DECIMAL(20,8) DEFAULT NULL,
  write_cached_rate DECIMAL(20,8) DEFAULT NULL,
  per_request_fee   DECIMAL(20,8) DEFAULT NULL,
  per_image_fee     DECIMAL(20,8) DEFAULT NULL,
  create_dt       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_model_key UNIQUE (model_key)
);
COMMENT ON TABLE nai_model IS '模型元数据';

-- UsageEvent outbox(用量回传兜底账本)
CREATE TABLE IF NOT EXISTS nai_usage_log (
  id                BIGINT        NOT NULL,
  request_id        VARCHAR(64)   NOT NULL,
  project_code     VARCHAR(64)  DEFAULT NULL,
  workspace_id          BIGINT       DEFAULT NULL,
  channel_id        BIGINT        DEFAULT NULL,
  key_id            BIGINT        DEFAULT NULL,
  model             VARCHAR(128)  DEFAULT NULL,
  prompt_tokens     INT           DEFAULT 0,
  completion_tokens INT           DEFAULT 0,
  cache_tokens      INT           DEFAULT 0,
  cache_creation_tokens INT        DEFAULT 0,
  total_tokens      INT           DEFAULT 0,
  cost_basis        DECIMAL(20,8) DEFAULT 0,
  cost_usd          DECIMAL(18,6) DEFAULT 0,
  status            SMALLINT      DEFAULT 1,
  delivered         SMALLINT      DEFAULT 0,
  create_dt         TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt         TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE nai_usage_log IS '用量事件outbox';
CREATE INDEX IF NOT EXISTS idx_usage_request ON nai_usage_log (request_id);
CREATE INDEX IF NOT EXISTS idx_usage_delivered ON nai_usage_log (delivered);

-- 故障转移/重试事件审计（换渠道/换 Key 重试与重试耗尽逐条留痕）
CREATE TABLE IF NOT EXISTS nai_failover_event (
  id              BIGINT       NOT NULL,
  request_id      VARCHAR(64)  NOT NULL,
  attempt         INT          NOT NULL,
  from_channel_id BIGINT       DEFAULT NULL,
  from_key_id     BIGINT       DEFAULT NULL,
  error_code      VARCHAR(64)  DEFAULT NULL,
  reason          VARCHAR(255) DEFAULT NULL,
  outcome         VARCHAR(32)  NOT NULL,
  create_dt       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE nai_failover_event IS '故障转移/重试事件审计';
CREATE INDEX IF NOT EXISTS idx_failover_request ON nai_failover_event (request_id);
CREATE INDEX IF NOT EXISTS idx_failover_create_dt ON nai_failover_event (create_dt);

-- 请求日志（网关最大表；log-mysql / log-pg 共用此表；store=clickhouse 时另建于 ClickHouse）
CREATE TABLE IF NOT EXISTS nai_request_log (
  id                BIGINT        NOT NULL,
  request_id        VARCHAR(64)   NOT NULL,
  trace_id          VARCHAR(64)   DEFAULT NULL,
  project_code      VARCHAR(64)   DEFAULT NULL,
  workspace_id      BIGINT        DEFAULT NULL,
  channel_id        BIGINT        DEFAULT NULL,
  key_id            BIGINT        DEFAULT NULL,
  model             VARCHAR(128)  DEFAULT NULL,
  target_model      VARCHAR(128)  DEFAULT NULL,
  modality          VARCHAR(32)   DEFAULT NULL,
  stream_mode       SMALLINT      DEFAULT 0,
  api_format        VARCHAR(32)   DEFAULT NULL,
  endpoint_api_format VARCHAR(32) DEFAULT NULL,
  prompt_tokens     INT           DEFAULT 0,
  completion_tokens INT           DEFAULT 0,
  cache_tokens      INT           DEFAULT 0,
  cache_creation_tokens INT        DEFAULT 0,
  total_tokens      INT           DEFAULT 0,
  cost_usd          DECIMAL(18,6) DEFAULT 0,
  status            SMALLINT      DEFAULT 1,
  http_status       INT           DEFAULT NULL,
  error_code        VARCHAR(64)   DEFAULT NULL,
  retry_count       INT           DEFAULT 0,
  fallback_count    INT           DEFAULT 0,
  usage_source      VARCHAR(16)   DEFAULT NULL,
  ttft_ms           BIGINT        DEFAULT NULL,
  duration_ms       BIGINT        DEFAULT NULL,
  client_ip         VARCHAR(64)   DEFAULT NULL,
  user_agent        VARCHAR(512)  DEFAULT NULL,
  client_family     VARCHAR(32)   DEFAULT NULL,
  prompt_hash       VARCHAR(64)   DEFAULT NULL,
  create_dt         TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt         TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE nai_request_log IS '请求日志(网关最大表)';
CREATE INDEX IF NOT EXISTS idx_reqlog_request ON nai_request_log (request_id);
CREATE INDEX IF NOT EXISTS idx_reqlog_project_model ON nai_request_log (project_code, model);
CREATE INDEX IF NOT EXISTS idx_reqlog_create_dt ON nai_request_log (create_dt);
CREATE INDEX IF NOT EXISTS idx_reqlog_channel_dt ON nai_request_log (channel_id, create_dt);
CREATE INDEX IF NOT EXISTS idx_reqlog_status_dt ON nai_request_log (http_status, create_dt);

-- 幂等记录（防重复提交·按幂等键短保留）
CREATE TABLE IF NOT EXISTS nai_idempotent (
  id             BIGINT        NOT NULL,
  idempotent_key VARCHAR(128)  NOT NULL,
  request_id     VARCHAR(64)   DEFAULT NULL,
  status         SMALLINT      DEFAULT 1,
  create_dt      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_idem UNIQUE (idempotent_key)
);
COMMENT ON TABLE nai_idempotent IS '幂等记录';

-- 结算补偿 outbox（QuotaLedger.settle 失败后的持久重放队列）
CREATE TABLE IF NOT EXISTS nai_settlement_outbox (
  id               BIGINT       NOT NULL,
  request_id       VARCHAR(64)  NOT NULL,
  token_id         BIGINT       DEFAULT NULL,
  project_code     VARCHAR(64)  DEFAULT NULL,
  refund_micro_usd BIGINT       NOT NULL,
  status           SMALLINT     DEFAULT 0,
  attempts         INT          DEFAULT 0,
  last_error       VARCHAR(512) DEFAULT NULL,
  next_retry_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  create_dt        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_dt        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_settlement_request UNIQUE (request_id)
);
COMMENT ON TABLE nai_settlement_outbox IS '结算补偿outbox';
CREATE INDEX IF NOT EXISTS idx_settlement_retry ON nai_settlement_outbox (status, next_retry_at);

-- 异步任务（MJ/视频 submit→poll→done/failed）
CREATE TABLE IF NOT EXISTS nai_task (
  id               BIGINT        NOT NULL,
  task_id          VARCHAR(64)   NOT NULL,
  project_code     VARCHAR(64)  DEFAULT NULL,
  upstream_task_id VARCHAR(128)  DEFAULT NULL,
  token_id         BIGINT        DEFAULT NULL,
  channel_id       BIGINT        DEFAULT NULL,
  key_id           BIGINT        DEFAULT NULL,
  modality         VARCHAR(32)   DEFAULT NULL,
  action           VARCHAR(64)   DEFAULT NULL,
  model            VARCHAR(128)  DEFAULT NULL,
  status           VARCHAR(32)   DEFAULT 'SUBMITTED',
  progress         VARCHAR(16)   DEFAULT NULL,
  frozen_usd       DECIMAL(18,6) DEFAULT 0,
  result_url       VARCHAR(1024) DEFAULT NULL,
  fail_reason      VARCHAR(512)  DEFAULT NULL,
  submit_body      TEXT          DEFAULT NULL,
  poll_lease_until TIMESTAMP     DEFAULT NULL,
  create_dt        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  update_dt        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_task UNIQUE (task_id)
);
COMMENT ON TABLE nai_task IS '异步任务(MJ/视频)';
CREATE INDEX IF NOT EXISTS idx_task_status ON nai_task (status);
CREATE INDEX IF NOT EXISTS idx_task_status_lease ON nai_task (status, poll_lease_until);
-- 注：claim 模式 FOR UPDATE SKIP LOCKED 需 PostgreSQL 9.5+（PG 原生支持，语法同 MySQL 8）。

-- 请求体分离（nexusai.log.body.store=rdb 时用·可单独部署·BYTEA 存 gzip 正文）
CREATE TABLE IF NOT EXISTS nai_request_body (
  request_id VARCHAR(64) NOT NULL,
  req_body   BYTEA       DEFAULT NULL,
  resp_body  BYTEA       DEFAULT NULL,
  create_dt  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (request_id)
);
COMMENT ON TABLE nai_request_body IS '请求体分离(独立库·log-body-dsn)';

-- 出口代理节点
CREATE TABLE IF NOT EXISTS nai_proxy_node (
  id bigint PRIMARY KEY,
  name varchar(255) NOT NULL,
  proxy_url varchar(500) NOT NULL,
  username varchar(255),
  password_enc varchar(1000),
  region varchar(100),
  status varchar(16) NOT NULL DEFAULT 'offline',
  total_requests bigint NOT NULL DEFAULT 0,
  failed_requests bigint NOT NULL DEFAULT 0,
  avg_latency_ms double precision,
  last_probe_at timestamp,
  last_ok_at timestamp,
  create_dt timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_dt timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS nai_proxy_node_event (
  id bigserial PRIMARY KEY,
  node_id bigint NOT NULL,
  event_type varchar(32) NOT NULL,
  detail varchar(500),
  create_dt timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_dt timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pne_node ON nai_proxy_node_event (node_id, create_dt);

CREATE TABLE IF NOT EXISTS nai_proxy_node_metric (
  node_id bigint NOT NULL,
  bucket_start timestamp NOT NULL,
  samples int NOT NULL DEFAULT 0,
  ok_samples int NOT NULL DEFAULT 0,
  latency_sum_ms bigint NOT NULL DEFAULT 0,
  latency_max_ms int NOT NULL DEFAULT 0,
  errors int NOT NULL DEFAULT 0,
  PRIMARY KEY (node_id, bucket_start)
);

CREATE TABLE IF NOT EXISTS nai_system_config (
  id bigserial PRIMARY KEY,
  cfg_key varchar(255) NOT NULL UNIQUE,
  cfg_value text,
  description varchar(500),
  create_dt timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_dt timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 集群节点注册表强化
ALTER TABLE nai_node
  ADD COLUMN IF NOT EXISTS role varchar(16) DEFAULT 'worker',
  ADD COLUMN IF NOT EXISTS active_requests int DEFAULT 0,
  ADD COLUMN IF NOT EXISTS cpu_percent double precision,
  ADD COLUMN IF NOT EXISTS heap_used_mb int,
  ADD COLUMN IF NOT EXISTS heap_max_mb int,
  ADD COLUMN IF NOT EXISTS labels varchar(500);

-- 控制面审计日志（2026-06-11）
CREATE TABLE IF NOT EXISTS nai_audit_log (
  id BIGSERIAL PRIMARY KEY,
  actor VARCHAR(64),
  event_type VARCHAR(64) NOT NULL,
  description VARCHAR(512),
  path VARCHAR(255),
  method VARCHAR(16),
  ip VARCHAR(64),
  status_code INT,
  error_message VARCHAR(512),
  create_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_type_dt ON nai_audit_log (event_type, create_dt);
CREATE INDEX IF NOT EXISTS idx_audit_dt ON nai_audit_log (create_dt);

-- 运维告警规则（2026-06-11）
CREATE TABLE IF NOT EXISTS nai_alert_rule (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL UNIQUE,
  description VARCHAR(512),
  enabled_flag SMALLINT NOT NULL DEFAULT 1,
  metric_type VARCHAR(64) NOT NULL,
  operator VARCHAR(8) NOT NULL,
  threshold DOUBLE PRECISION NOT NULL,
  window_minutes INT NOT NULL DEFAULT 5,
  sustained_minutes INT NOT NULL DEFAULT 1,
  cooldown_minutes INT NOT NULL DEFAULT 10,
  severity VARCHAR(8) NOT NULL DEFAULT 'P2',
  notify_flag SMALLINT NOT NULL DEFAULT 1,
  last_triggered_dt TIMESTAMP,
  create_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 运维告警事件（2026-06-11）
CREATE TABLE IF NOT EXISTS nai_alert_event (
  id BIGSERIAL PRIMARY KEY,
  rule_id BIGINT NOT NULL,
  rule_name VARCHAR(128),
  severity VARCHAR(8) NOT NULL DEFAULT 'P2',
  status VARCHAR(20) NOT NULL DEFAULT 'firing',
  title VARCHAR(255),
  description VARCHAR(1024),
  metric_value DOUBLE PRECISION,
  threshold_value DOUBLE PRECISION,
  fired_dt TIMESTAMP,
  resolved_dt TIMESTAMP,
  notified_flag SMALLINT NOT NULL DEFAULT 0,
  create_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_alert_event_rule_status ON nai_alert_event (rule_id, status);
CREATE INDEX IF NOT EXISTS idx_alert_event_dt ON nai_alert_event (create_dt);
