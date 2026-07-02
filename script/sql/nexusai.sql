-- nexusai 独立库 DDL（MVP 转发面技术表）
-- 库名 nexusai；nai_ 前缀；雪花 ID(BIGINT)；create_dt/update_dt 审计字段。
-- 商业表(钱包/账单/兑换码/工作区/套餐/Key 签发)全在 业务面，绝不共表。

CREATE DATABASE IF NOT EXISTS `nexusai` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE `nexusai`;

-- API Key 鉴权（元数据快照·控制面下发）。项目制：USD 额度池在 nai_project，本表 quota_usd 仅为可选 Key 子限额
CREATE TABLE IF NOT EXISTS `nai_token` (
  `id`            BIGINT       NOT NULL COMMENT '雪花ID',
  `key_hash`      VARCHAR(64)  NOT NULL COMMENT 'API Key SHA-256(明文 sk- 永不落库)',
  `name`          VARCHAR(100) DEFAULT NULL COMMENT 'Key 展示名(独立签发必填·外部同步可空)',
  `standalone`    TINYINT      DEFAULT 0 COMMENT '1=控制台独立签发 0=外部业务面同步',
  `project_code`  VARCHAR(64)  NOT NULL COMMENT '归属项目(必填·→nai_project.project_code·额度池/策略/用量归集按项目)',
  `workspace_id`       BIGINT       DEFAULT NULL COMMENT '归属项目内工作区(可选·→nai_workspace.id·非空=扣工作区钱包·空=B2B直扣项目池)',
  `group_code`    VARCHAR(64)  DEFAULT 'default',
  `model_limits`  TEXT         DEFAULT NULL COMMENT '模型白名单JSON(Model Permission·空=不限)',
  `allow_ips`     VARCHAR(512) DEFAULT NULL COMMENT '来源IP白名单(JSON/逗号·空=不限)',
  `allow_providers`   VARCHAR(512) DEFAULT NULL COMMENT '渠道/provider白名单JSON(空=不限)',
  `allow_api_formats` VARCHAR(256) DEFAULT NULL COMMENT '协议入口白名单JSON如["openai","anthropic"](空=不限)',
  `quota_usd`     DECIMAL(18,6) DEFAULT NULL COMMENT 'Key 子限额USD(可选·防单Key泄漏打爆项目池·NULL/≤0=不设仅受项目池)',
  `rpm_limit`     INT          DEFAULT 0 COMMENT '每分钟请求数上限(0=不限)',
  `tpm_limit`     INT          DEFAULT 0 COMMENT '每分钟 token 数上限(0=不限)',
  `concurrent_limit` INT       DEFAULT 0 COMMENT '并发上限(0=不限)',
  `byok_credential` VARCHAR(1024) DEFAULT NULL COMMENT 'BYOK 客户自带上游凭证(AES加密·有则替代池Key)',
  `expired_time`  BIGINT       DEFAULT 0 COMMENT '过期毫秒时间戳(0=永不过期)',
  `auto_delete_on_expiry` TINYINT DEFAULT 0 COMMENT '到期自动删除(1=删除·0=仅停用)',
  `feature_settings` TEXT      DEFAULT NULL COMMENT 'Key级特性JSON(chat_pii_redaction等)',
  `status`        TINYINT      DEFAULT 1,
  `create_dt`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_token_project_standalone` (`project_code`, `standalone`)
) ENGINE=InnoDB COMMENT='API Key(元数据快照+可选子限额)';

-- 项目（工作区+控制面集成边界）：接入方(业务面/独立运维/其它后端)凭 (project_code,token) 调 /admin/**·替代单静态 admin token
CREATE TABLE IF NOT EXISTS `nai_project` (
  `id`           BIGINT       NOT NULL COMMENT '雪花ID',
  `project_code` VARCHAR(64)  NOT NULL COMMENT '项目稳定标识(如 my-saas / standalone-ops)',
  `name`         VARCHAR(128) NOT NULL COMMENT '项目名称',
  `description`  VARCHAR(512) DEFAULT NULL COMMENT '项目描述',
  `token`        VARCHAR(128) NOT NULL COMMENT '通信认证令牌(M2M·NexusAI-Admin-Token 头·唯一)',
  `scope`        VARCHAR(16)  DEFAULT 'project' COMMENT '令牌作用域 platform=平台运营(全/admin) / project=仅租户自助端点(默认·keys/quota/workspaces/usage)',
  `allowed_models`          TEXT         DEFAULT NULL COMMENT '访问策略·允许的模型(JSON数组·空=不限)',
  `allowed_modalities`      VARCHAR(256) DEFAULT NULL COMMENT '访问策略·允许的能力(JSON数组·chat/embedding/image/rerank·空=不限)',
  `allowed_channel_ids`     TEXT         DEFAULT NULL COMMENT '访问策略·允许的渠道id(JSON数组·空=不限)',
  `allowed_channel_tags`    TEXT         DEFAULT NULL COMMENT '访问策略·允许的渠道标签(JSON数组·空=不限)',
  `channel_tags_match_mode` VARCHAR(16)  DEFAULT 'any' COMMENT '标签匹配模式 any/all/none',
  `quota_usd`     DECIMAL(18,6) DEFAULT 0 COMMENT '项目USD额度池(DB基线·运行时真值在Redis micro-USD·控制面充退)',
  `unlimited`     TINYINT    DEFAULT 0 COMMENT '不限额(内部自用项目·闸门不扣减)',
  `rpm_limit`     INT          DEFAULT 0 COMMENT '项目级每分钟请求数总闸(项目下全部Key共享·0=不限·RateLimitFilter执行)',
  `tpm_limit`     INT          DEFAULT 0 COMMENT '项目级每分钟token数总闸(0=不限·RateLimitFilter软闸门)',
  `status`       TINYINT      DEFAULT 1 COMMENT '1active 0archived',
  `create_dt`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_code` (`project_code`),
  UNIQUE KEY `uk_project_token` (`token`)
) ENGINE=InnoDB COMMENT='项目(工作区+控制面集成边界)';

-- 控制台运维账号（人类登录主体·账密真理源·借鉴 snail-ai sai_user / snail-job sj_system_user）：
-- role=2 超管(全 /admin) / role=1 项目运维(仅被授权项目的自助端点·授权见 nai_admin_user_project)。
-- 口令 PBKDF2 加盐哈希(pbkdf2$iter$salt$hash)·明文永不落库。首次启动表空时由 AdminUserSeeder 播种默认超管 admin/admin123。
CREATE TABLE IF NOT EXISTS `nai_admin_user` (
  `id`        BIGINT       NOT NULL COMMENT '雪花ID',
  `username`  VARCHAR(64)  NOT NULL COMMENT '登录账号',
  `password`  VARCHAR(128) NOT NULL COMMENT '口令哈希(PBKDF2·pbkdf2$iter$salt$hash)',
  `role`      TINYINT      NOT NULL DEFAULT 1 COMMENT '角色 1=项目运维 2=超管',
  `status`    TINYINT      DEFAULT 1 COMMENT '1启用 0停用',
  `create_dt` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB COMMENT='控制台运维账号';

-- 运维账号×项目授权（项目运维身份的行级授权·借鉴 snail-job sj_system_user_permission 的 namespace 授权·project=namespace）。
-- 一行=该账号被授权管理该 project_code；超管不登记(全项目)。
CREATE TABLE IF NOT EXISTS `nai_admin_user_project` (
  `id`            BIGINT      NOT NULL COMMENT '雪花ID',
  `admin_user_id` BIGINT      NOT NULL COMMENT '关联 nai_admin_user.id',
  `project_code`  VARCHAR(64) NOT NULL COMMENT '被授权的项目码(关联 nai_project.project_code)',
  `create_dt`     DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_dt`     DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_user_project` (`admin_user_id`, `project_code`)
) ENGINE=InnoDB COMMENT='运维账号×项目授权';

-- 项目内工作区（三层身份链·按工作区隔离的额度钱包）：
-- B2C 中转站形态的终端工作区钱包——project=站点/分销商(容器)·workspace=散户工作区(钱包主闸)·token 挂 workspace；
-- B2B 套壳应用形态不建 workspace（token 直挂项目池）。external_id=业务面工作区标识(幂等键·业务面 userId 等)
CREATE TABLE IF NOT EXISTS `nai_workspace` (
  `id`            BIGINT       NOT NULL COMMENT '雪花ID(网关内部工作区标识·UsageEvent/日志按此聚合)',
  `project_code`  VARCHAR(64)  NOT NULL COMMENT '归属项目(容器·→nai_project.project_code)',
  `external_id`   VARCHAR(256) NOT NULL COMMENT '业务面工作区标识(项目内唯一·幂等键)',
  `display_name`  VARCHAR(128) DEFAULT NULL COMMENT '展示名(可选)',
  `quota_usd`     DECIMAL(18,6) DEFAULT 0 COMMENT '工作区USD钱包(主闸·DB基线·运行时真值在Redis micro-USD·控制面user维度充退)',
  `unlimited`     TINYINT      DEFAULT 0 COMMENT '不限额工作区(内部·闸门不扣减)',
  `rpm_limit`     INT          DEFAULT 0 COMMENT '工作区级每分钟请求数闸(该工作区全部Key共享·0=不限)',
  `tpm_limit`     INT          DEFAULT 0 COMMENT '工作区级每分钟token数闸(0=不限)',
  `status`        TINYINT      DEFAULT 1 COMMENT '1正常 0停用',
  `create_dt`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_external` (`project_code`, `external_id`)
) ENGINE=InnoDB COMMENT='项目内工作区(B2C终端工作区钱包·按工作区隔离)';

-- 调度策略分组：系统默认策略驱动选路·config_json={defaultPolicy,modelPolicies}
CREATE TABLE IF NOT EXISTS `nai_routing_group` (
  `id`                BIGINT       NOT NULL COMMENT '雪花ID',
  `name`              VARCHAR(128) NOT NULL COMMENT '策略名称',
  `description`       VARCHAR(512) DEFAULT NULL COMMENT '策略描述',
  `enabled`           TINYINT      DEFAULT 1 COMMENT '是否启用',
  `is_system_default` TINYINT      DEFAULT 0 COMMENT '系统默认(全局唯一·运行时选路依据)',
  `config_json`       TEXT         DEFAULT NULL COMMENT '策略配置JSON(defaultPolicy+modelPolicies)',
  `version`           INT          DEFAULT 1 COMMENT '版本号(每次保存+1)',
  `create_dt`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='调度策略分组';

-- 网关实例节点（多实例集群视图·守护线程心跳 upsert·update_dt=末次心跳）
CREATE TABLE IF NOT EXISTS `nai_node` (
  `id`         BIGINT       NOT NULL COMMENT '雪花ID',
  `node_id`    VARCHAR(128) NOT NULL COMMENT '节点唯一标识(host:port)',
  `host`       VARCHAR(64)  DEFAULT NULL,
  `port`       INT          DEFAULT NULL,
  `version`    VARCHAR(64)  DEFAULT NULL COMMENT 'JVM/构建版本',
  `started_at` BIGINT       DEFAULT NULL COMMENT '进程启动时间(epoch ms)',
  `role`            VARCHAR(16)  DEFAULT 'worker' COMMENT 'leader/worker',
  `active_requests` INT          DEFAULT 0 COMMENT '在途透传请求数',
  `cpu_percent`     DOUBLE       DEFAULT NULL COMMENT '进程CPU %',
  `heap_used_mb`    INT          DEFAULT NULL,
  `heap_max_mb`     INT          DEFAULT NULL,
  `labels`          VARCHAR(500) DEFAULT NULL COMMENT '部署标签 JSON',
  `create_dt`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '末次心跳',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_id` (`node_id`)
) ENGINE=InnoDB COMMENT='网关实例节点(集群心跳·运行时快照随心跳上报)';

-- 渠道（上游入口·配置化三档；凭证在 nai_channel_key）
CREATE TABLE IF NOT EXISTS `nai_channel` (
  `id`                       BIGINT       NOT NULL COMMENT '雪花ID',
  `name`                     VARCHAR(100) NOT NULL,
  `description`              VARCHAR(255) DEFAULT NULL COMMENT '备注(提供商页行内编辑)',
  `website`                  VARCHAR(255) DEFAULT NULL COMMENT '主站链接',
  `protocol`                 VARCHAR(32)  DEFAULT 'openai' COMMENT '协议家族(选代码适配器)',
  `vendor`                   VARCHAR(64)  DEFAULT NULL,
  `auth_type`                VARCHAR(32)  DEFAULT 'bearer',
  `base_url`                 VARCHAR(255) NOT NULL,
  `status`                   TINYINT      DEFAULT 1 COMMENT '1启用 0停用 2归档',
  `weight`                   INT          DEFAULT 0,
  `priority`                 INT          DEFAULT 0,
  `auto_ban`                 TINYINT      DEFAULT 1,
  `tags`                     TEXT         DEFAULT NULL COMMENT '渠道标签(JSON数组·供项目访问策略圈渠道)',
  `model_mapping`            TEXT         DEFAULT NULL COMMENT '档②模型重映射JSON',
  `param_override`           TEXT         DEFAULT NULL,
  `header_override`          TEXT         DEFAULT NULL,
  `status_code_mapping`      VARCHAR(1024) DEFAULT NULL,
  `setting`                  TEXT         DEFAULT NULL,
  `endpoints`                TEXT         DEFAULT NULL COMMENT 'JSON·api_format/path',
  `billing_type`             VARCHAR(32)  DEFAULT 'pay_as_you_go' COMMENT '计费类型 monthly_quota/pay_as_you_go/free_tier',
  `quota_usd`                DECIMAL(18,6) DEFAULT NULL COMMENT '月卡周期额度USD',
  `quota_reset_days`         INT          DEFAULT NULL COMMENT '月卡重置周期(天)',
  `quota_start_at`           BIGINT       DEFAULT 0 COMMENT '月卡当前周期开始(毫秒)',
  `quota_expires_at`         BIGINT       DEFAULT 0 COMMENT '渠道过期时间(毫秒·0=永不)',
  `first_byte_timeout`       INT          DEFAULT 0 COMMENT '流式首字节超时(秒·0=全局默认)',
  `request_timeout`          INT          DEFAULT 0 COMMENT '非流式请求总超时(秒·0=不限)',
  `rate_limit_reset_at`      BIGINT       DEFAULT 0 COMMENT '渠道级熔断态·运行时真身在router内存·当前不落库(控制台创建/编辑写重置基线·保留列备将来flusher回刷)',
  `overload_until`           BIGINT       DEFAULT 0 COMMENT '同上·运行时熔断态·当前不落库',
  `temp_unschedulable_until` BIGINT       DEFAULT 0 COMMENT '同上·运行时熔断态·当前不落库',
  `health_score`             INT          DEFAULT 100 COMMENT '同上·运行时健康分·当前不落库',
  `success_count`            BIGINT       DEFAULT 0 COMMENT '同上·运行时成功计数·当前不落库',
  `error_count`              BIGINT       DEFAULT 0 COMMENT '同上·运行时错误计数·当前不落库',
  `create_dt`                DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`                DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='渠道';

-- 渠道密钥池（Key 池）：渠道内多 Key 凭证(AES-256-GCM)
CREATE TABLE IF NOT EXISTS `nai_channel_key` (
  `id`                       BIGINT   NOT NULL COMMENT '雪花ID',
  `channel_id`               BIGINT   NOT NULL,
  `credential`               TEXT     NOT NULL COMMENT '上游凭证AES-256-GCM密文',
  `weight`                   INT      DEFAULT 0,
  `priority`                 INT      DEFAULT 0,
  `status`                   TINYINT  DEFAULT 1,
  `rpm_learned`              INT      DEFAULT 0 COMMENT '自适应学习到的真实RPM上限·运行时在router内存·当前不落库(保留列备将来flusher)',
  `max_concurrency`          INT      DEFAULT 0 COMMENT '并发槽上限(持久配置·KeyPoolSelector运行时读取)',
  `rate_limit_reset_at`      BIGINT   DEFAULT 0 COMMENT 'Key级熔断态·运行时真身在router内存·当前不落库(控制台创建/编辑写重置基线·保留列备将来flusher回刷)',
  `overload_until`           BIGINT   DEFAULT 0 COMMENT '同上·运行时熔断态·当前不落库',
  `temp_unschedulable_until` BIGINT   DEFAULT 0 COMMENT '同上·运行时熔断态·当前不落库',
  `health_score`             INT      DEFAULT 100 COMMENT '同上·运行时健康分·当前不落库',
  `success_count`            BIGINT   DEFAULT 0 COMMENT '同上·运行时成功计数·当前不落库',
  `error_count`              BIGINT   DEFAULT 0 COMMENT '同上·运行时错误计数·当前不落库',
  `create_dt`                DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_dt`                DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_channel_status` (`channel_id`, `status`)
) ENGINE=InnoDB COMMENT='渠道密钥池(Key 池)';

-- 路由索引(abilities)
CREATE TABLE IF NOT EXISTS `nai_ability` (
  `group_code` VARCHAR(64)  NOT NULL,
  `model`      VARCHAR(128) NOT NULL,
  `channel_id` BIGINT       NOT NULL,
  `priority`   INT          DEFAULT 0,
  `weight`     INT          DEFAULT 0,
  `enabled`    TINYINT      DEFAULT 1,
  `modality`   VARCHAR(32)  DEFAULT 'CHAT',
  `create_dt`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`group_code`,`model`,`channel_id`)
) ENGINE=InnoDB COMMENT='路由索引';

-- 模型元数据(USD 价格由控制面下发)
CREATE TABLE IF NOT EXISTS `nai_model` (
  `id`             BIGINT        NOT NULL COMMENT '雪花ID',
  `model_key`      VARCHAR(128)  NOT NULL,
  `model_alias`    VARCHAR(128)  DEFAULT NULL,
  `description`    VARCHAR(500)  DEFAULT NULL,
  `modality`       VARCHAR(32)   DEFAULT 'CHAT',
  `context_length` INT           DEFAULT 0,
  `max_output_tokens` INT        DEFAULT NULL,
  `capabilities`   TEXT          DEFAULT NULL,
  `enable_groups`  VARCHAR(255)  DEFAULT NULL,
  `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '1=活跃 0=停用',
  `sync_official`  TINYINT       NOT NULL DEFAULT 1 COMMENT '1=允许上游元数据同步覆盖 0=本地锁定',
  `alias_patterns` VARCHAR(1000) DEFAULT NULL COMMENT '入站别名归一正则(JSON数组)',
  `cost_basis`     DECIMAL(20,8) DEFAULT 0,
  `input_rate`        DECIMAL(20,8) DEFAULT NULL COMMENT '非缓存prompt USD/百万token($/M·同models.dev口径)',
  `output_rate`       DECIMAL(20,8) DEFAULT NULL COMMENT 'completion USD/百万token($/M)',
  `cached_input_rate` DECIMAL(20,8) DEFAULT NULL COMMENT '缓存prompt USD/百万token($/M)',
  `write_cached_rate` DECIMAL(20,8) DEFAULT NULL COMMENT '缓存创建 USD/百万token($/M·Anthropic约input×1.25·NULL回退input)',
  `per_request_fee`   DECIMAL(20,8) DEFAULT NULL COMMENT '每次请求固定',
  `per_image_fee`     DECIMAL(20,8) DEFAULT NULL COMMENT '每张图固定',
  `create_dt`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_dt`      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_key` (`model_key`)
) ENGINE=InnoDB COMMENT='模型元数据';

-- UsageEvent outbox(用量回传兜底账本)
CREATE TABLE IF NOT EXISTS `nai_usage_log` (
  `id`                BIGINT        NOT NULL COMMENT '雪花ID',
  `request_id`        VARCHAR(64)   NOT NULL,
  `project_code`   VARCHAR(64)  DEFAULT NULL COMMENT '归属项目',
  `workspace_id`           BIGINT        DEFAULT NULL COMMENT '项目内工作区(nai_workspace.id·B2C账单按工作区聚合)',
  `channel_id`        BIGINT        DEFAULT NULL,
  `key_id`            BIGINT        DEFAULT NULL,
  `model`             VARCHAR(128)  DEFAULT NULL,
  `prompt_tokens`     INT           DEFAULT 0,
  `completion_tokens` INT           DEFAULT 0,
  `cache_tokens`      INT           DEFAULT 0,
  `cache_creation_tokens` INT       DEFAULT 0,
  `total_tokens`      INT           DEFAULT 0,
  `cost_basis`        DECIMAL(20,8) DEFAULT 0 COMMENT '上游成本基准USD',
  `cost_usd`          DECIMAL(18,6) DEFAULT 0 COMMENT '本次向客户扣减USD(=售价·与cost_basis之差为毛利)',
  `status`            TINYINT       DEFAULT 1,
  `create_dt`         DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_dt`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_request` (`request_id`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_project_id` (`project_code`, `id`)
) ENGINE=InnoDB COMMENT='用量事件outbox';

-- 故障转移/重试事件审计（换渠道/换 Key 重试与重试耗尽逐条留痕·热路径外 fire-and-forget 落库）
CREATE TABLE IF NOT EXISTS `nai_failover_event` (
  `id`              BIGINT       NOT NULL COMMENT '雪花ID',
  `request_id`      VARCHAR(64)  NOT NULL COMMENT '请求ID(串起同一请求的多次尝试)',
  `attempt`         INT          NOT NULL COMMENT '第几次尝试(1起)',
  `from_channel_id` BIGINT       DEFAULT NULL COMMENT '被排除/失败的渠道',
  `from_key_id`     BIGINT       DEFAULT NULL COMMENT '被排除/失败的Key',
  `error_code`      VARCHAR(64)  DEFAULT NULL COMMENT '错误码/上游状态(如500/UPSTREAM_TIMEOUT/TRANSIENT)',
  `reason`          VARCHAR(255) DEFAULT NULL COMMENT '转移原因(upstream-5xx/upstream-429/skip-retry/transient-xxx)',
  `outcome`         VARCHAR(32)  NOT NULL COMMENT 'retry(换号继续)/exhausted(重试耗尽终态)',
  `create_dt`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_dt`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_request` (`request_id`),
  KEY `idx_create_dt` (`create_dt`)
) ENGINE=InnoDB COMMENT='故障转移/重试事件审计';

-- 请求日志（网关最大表·请求级可观测/审计；与 nai_usage_log 同源分流；默认不存 prompt 只存 sha256）
CREATE TABLE IF NOT EXISTS `nai_request_log` (
  `id`                BIGINT        NOT NULL COMMENT '雪花ID',
  `request_id`        VARCHAR(64)   NOT NULL,
  `trace_id`          VARCHAR(64)   DEFAULT NULL,
  `project_code`      VARCHAR(64)   DEFAULT NULL COMMENT '归属项目(nai_project.project_code)',
  `workspace_id`           BIGINT        DEFAULT NULL COMMENT '项目内工作区(nai_workspace.id)',
  `channel_id`        BIGINT        DEFAULT NULL,
  `key_id`            BIGINT        DEFAULT NULL,
  `model`             VARCHAR(128)  DEFAULT NULL COMMENT '请求模型(客户端原始)',
  `target_model`      VARCHAR(128)  DEFAULT NULL COMMENT '上游真实模型(mapping后)',
  `modality`          VARCHAR(32)   DEFAULT NULL COMMENT 'chat/embedding/image/rerank',
  `stream_mode`       TINYINT       DEFAULT 0   COMMENT '是否流式',
  `api_format`        VARCHAR(32)   DEFAULT NULL COMMENT '入站API格式(openai:chat/claude:messages)',
  `endpoint_api_format` VARCHAR(32) DEFAULT NULL COMMENT '上游端点API格式(渠道协议族)',
  `prompt_tokens`     INT           DEFAULT 0,
  `completion_tokens` INT           DEFAULT 0,
  `cache_tokens`      INT           DEFAULT 0,
  `cache_creation_tokens` INT       DEFAULT 0,
  `total_tokens`      INT           DEFAULT 0,
  `cost_usd`          DECIMAL(18,6) DEFAULT 0 COMMENT '本次向客户扣减USD',
  `status`            TINYINT       DEFAULT 1   COMMENT '1成功 0失败',
  `http_status`       INT           DEFAULT NULL,
  `error_code`        VARCHAR(64)   DEFAULT NULL,
  `retry_count`       INT           DEFAULT 0   COMMENT '同渠道换Key重试次数',
  `fallback_count`    INT           DEFAULT 0   COMMENT '跨渠道故障转移次数',
  `usage_source`      VARCHAR(16)   DEFAULT NULL COMMENT '结算来源 parsed/estimated/client-abort/...',
  `ttft_ms`           BIGINT        DEFAULT NULL COMMENT '首字节耗时',
  `duration_ms`       BIGINT        DEFAULT NULL COMMENT '总耗时',
  `client_ip`         VARCHAR(64)   DEFAULT NULL,
  `user_agent`        VARCHAR(512)  DEFAULT NULL COMMENT '客户端User-Agent',
  `client_family`     VARCHAR(32)   DEFAULT NULL COMMENT 'UA客户端家族(claude_code/codex/...)',
  `prompt_hash`       VARCHAR(64)   DEFAULT NULL COMMENT '请求体sha256(非正文)',
  `create_dt`         DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_dt`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_request` (`request_id`),
  KEY `idx_project_model` (`project_code`, `model`),
  KEY `idx_project_dt` (`project_code`, `create_dt`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_channel_dt` (`channel_id`, `create_dt`),
  KEY `idx_status_dt` (`http_status`, `create_dt`)
) ENGINE=InnoDB COMMENT='请求日志(网关最大表)';

-- 幂等防重全部走 Redis（IdempotencyFilter·SETNX+TTL·跨实例）；不落库（旧 nai_idempotent 死表已删）。

-- 结算补偿 outbox（QuotaLedger.settle 失败后的持久重放队列）
CREATE TABLE IF NOT EXISTS `nai_settlement_outbox` (
  `id`               BIGINT        NOT NULL COMMENT '雪花ID',
  `request_id`       VARCHAR(64)   NOT NULL,
  `token_id`         BIGINT        DEFAULT NULL,
  `project_code`     VARCHAR(64)   DEFAULT NULL,
  `refund_micro_usd` BIGINT        NOT NULL COMMENT '>0退回 / <0补扣',
  `status`           TINYINT       DEFAULT 0 COMMENT '0=pending,1=done,2=abandoned,3=processing',
  `attempts`         INT           DEFAULT 0,
  `last_error`       VARCHAR(512)  DEFAULT NULL,
  `next_retry_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `create_dt`        DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_dt`        DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_request` (`request_id`),
  KEY `idx_settlement_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB COMMENT='结算补偿outbox';

-- 异步任务（MJ/视频 submit→poll→done/failed·冻结/结算/退款由业务面）
CREATE TABLE IF NOT EXISTS `nai_task` (
  `id`             BIGINT        NOT NULL COMMENT '雪花ID',
  `task_id`        VARCHAR(64)   NOT NULL COMMENT '网关任务ID',
  `project_code`     VARCHAR(64)  DEFAULT NULL COMMENT '归属项目',
  `upstream_task_id` VARCHAR(128) DEFAULT NULL COMMENT '上游任务ID',
  `token_id`       BIGINT        DEFAULT NULL,
  `channel_id`     BIGINT        DEFAULT NULL,
  `key_id`         BIGINT        DEFAULT NULL,
  `modality`       VARCHAR(32)   DEFAULT NULL COMMENT 'midjourney/video',
  `action`         VARCHAR(64)   DEFAULT NULL,
  `model`          VARCHAR(128)  DEFAULT NULL,
  `status`         VARCHAR(32)   DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/IN_PROGRESS/SUCCESS/FAILED',
  `progress`       VARCHAR(16)   DEFAULT NULL,
  `frozen_usd`     DECIMAL(18,6) DEFAULT 0 COMMENT '预扣冻结USD(终态结算/退款依据)',
  `result_url`     VARCHAR(1024) DEFAULT NULL,
  `fail_reason`    VARCHAR(512)  DEFAULT NULL,
  `submit_body`    TEXT          DEFAULT NULL,
  `poll_lease_until` DATETIME    DEFAULT NULL COMMENT '行认领租约到期(claim模式FOR UPDATE SKIP LOCKED去重用·但该列所有模式必备:PO字段进默认SELECT全列)',
  `create_dt`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_dt`      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task` (`task_id`),
  KEY `idx_status` (`status`),
  KEY `idx_status_lease` (`status`, `poll_lease_until`)
) ENGINE=InnoDB COMMENT='异步任务(MJ/视频)';
-- 请求体分离（nexusai.log.body.store=rdb 时用·可单独部署到第三个库/实例·与 nai_request_log 用 request_id 关联）
CREATE TABLE IF NOT EXISTS `nai_request_body` (
  `request_id` VARCHAR(64) NOT NULL COMMENT '关联 nai_request_log.request_id',
  `req_body`   MEDIUMBLOB   DEFAULT NULL COMMENT '请求正文(gzip)',
  `resp_body`  MEDIUMBLOB   DEFAULT NULL COMMENT '响应正文(gzip·流式通常不存)',
  `create_dt`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`request_id`)
) ENGINE=InnoDB COMMENT='请求体分离(独立库)';

-- 存量库升级（⚠️所有模式都必须执行·含默认 leader：pollLeaseUntil 进 PO 默认 SELECT 全列，缺列则任务轮询/查询/列表全报错）：
-- ALTER TABLE `nai_task`
--   ADD COLUMN `poll_lease_until` DATETIME DEFAULT NULL COMMENT '行认领租约到期' AFTER `submit_body`,
--   ADD KEY `idx_status_lease` (`status`, `poll_lease_until`);
-- 另：claim 模式的 FOR UPDATE SKIP LOCKED 需 MySQL 8.0.1+ / MariaDB 10.6+（旧版会语法报错）。

-- 出口代理节点（手动 HTTP/SOCKS5 出站代理）
CREATE TABLE IF NOT EXISTS `nai_proxy_node` (
  `id` bigint NOT NULL,
  `name` varchar(255) NOT NULL COMMENT '节点名称',
  `proxy_url` varchar(500) NOT NULL COMMENT 'http://host:port 或 socks5://host:port',
  `username` varchar(255) DEFAULT NULL,
  `password_enc` varchar(1000) DEFAULT NULL COMMENT '代理密码 AES-256-GCM 密文',
  `region` varchar(100) DEFAULT NULL COMMENT '区域',
  `status` varchar(16) NOT NULL DEFAULT 'offline' COMMENT 'online/offline',
  `total_requests` bigint NOT NULL DEFAULT 0 COMMENT '经此代理透传请求数',
  `failed_requests` bigint NOT NULL DEFAULT 0,
  `avg_latency_ms` double DEFAULT NULL COMMENT '探测延迟 EMA',
  `last_probe_at` datetime DEFAULT NULL,
  `last_ok_at` datetime DEFAULT NULL,
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出口代理节点';

-- 代理节点连接事件
CREATE TABLE IF NOT EXISTS `nai_proxy_node_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `node_id` bigint NOT NULL,
  `event_type` varchar(32) NOT NULL COMMENT 'connected/disconnected/error',
  `detail` varchar(500) DEFAULT NULL,
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pne_node` (`node_id`,`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理节点连接事件';

-- 代理节点采样指标（1h 桶）
CREATE TABLE IF NOT EXISTS `nai_proxy_node_metric` (
  `node_id` bigint NOT NULL,
  `bucket_start` datetime NOT NULL,
  `samples` int NOT NULL DEFAULT 0,
  `ok_samples` int NOT NULL DEFAULT 0,
  `latency_sum_ms` bigint NOT NULL DEFAULT 0,
  `latency_max_ms` int NOT NULL DEFAULT 0,
  `errors` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`node_id`,`bucket_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理节点采样指标（1h 桶）';

-- 系统配置 KV（含模块开关 module.{name}.enabled）
CREATE TABLE IF NOT EXISTS `nai_system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cfg_key` varchar(255) NOT NULL,
  `cfg_value` text COMMENT 'JSON 字面量',
  `description` varchar(500) DEFAULT NULL,
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sc_key` (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置 KV';

-- 控制面审计日志（2026-06-11·内置工具「审计日志」）
CREATE TABLE IF NOT EXISTS `nai_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor` varchar(64) DEFAULT NULL COMMENT '操作者 admin/project:{code}',
  `event_type` varchar(64) NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  `path` varchar(255) DEFAULT NULL,
  `method` varchar(16) DEFAULT NULL,
  `ip` varchar(64) DEFAULT NULL,
  `status_code` int DEFAULT NULL,
  `error_message` varchar(512) DEFAULT NULL,
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_type_dt` (`event_type`, `create_dt`),
  KEY `idx_audit_dt` (`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='控制面审计日志';

-- 运维告警规则（2026-06-11·运维监控页）
CREATE TABLE IF NOT EXISTS `nai_alert_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  `enabled_flag` tinyint NOT NULL DEFAULT 1,
  `metric_type` varchar(64) NOT NULL COMMENT 'error_rate/qps/p99_latency_ms/...',
  `operator` varchar(8) NOT NULL COMMENT '> >= < <= == !=',
  `threshold` double NOT NULL,
  `window_minutes` int NOT NULL DEFAULT 5 COMMENT '评估窗口',
  `sustained_minutes` int NOT NULL DEFAULT 1 COMMENT '持续时长',
  `cooldown_minutes` int NOT NULL DEFAULT 10 COMMENT '冷却期',
  `severity` varchar(8) NOT NULL DEFAULT 'P2',
  `notify_flag` tinyint NOT NULL DEFAULT 1 COMMENT '触发走通知服务',
  `last_triggered_dt` datetime DEFAULT NULL,
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_rule_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维告警规则';

-- 运维告警事件（2026-06-11）
CREATE TABLE IF NOT EXISTS `nai_alert_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NOT NULL,
  `rule_name` varchar(128) DEFAULT NULL COMMENT '规则名快照',
  `severity` varchar(8) NOT NULL DEFAULT 'P2',
  `status` varchar(20) NOT NULL DEFAULT 'firing' COMMENT 'firing/resolved/manual_resolved',
  `title` varchar(255) DEFAULT NULL,
  `description` varchar(1024) DEFAULT NULL,
  `metric_value` double DEFAULT NULL,
  `threshold_value` double DEFAULT NULL,
  `fired_dt` datetime DEFAULT NULL,
  `resolved_dt` datetime DEFAULT NULL,
  `notified_flag` tinyint NOT NULL DEFAULT 0,
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_alert_event_rule_status` (`rule_id`, `status`),
  KEY `idx_alert_event_dt` (`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维告警事件';

-- =====================================================================
-- 附录·时序表分区运维参考（部署/扩容期手动启用·MVP 单机不预置）
-- =====================================================================
-- 适用：写入按时间增长、保留期固定、清理靠"丢弃整段"的表。优于 DELETE 百万行——
-- DROP PARTITION 仅改元数据（毫秒级）；查询带 create_dt 范围即触发 partition pruning（EXPLAIN PARTITIONS 验证）。
--
-- 取舍：
--   · nai_request_log —— 已有应用层 nexusai.log.sharding（按时间滚动分表）。二选一，勿与原生分区叠用。
--     若改用原生分区：关闭 LOG_SHARDING，按下方模板改造（PK 须含分区列）。
--   · nai_failover_event / nai_audit_log / nai_alert_event / nai_proxy_node_event / nai_usage_log
--     —— 无 sharding，量起来后建议直接套用原生 RANGE 月分区。
--
-- 约束：MySQL 分区要求每个唯一键/主键都包含分区列 → PK 由 (id) 改为 (id, create_dt)。
-- 雪花 id 全局唯一，应用层仍以 id 作逻辑主键（MyBatis-Plus @TableId 不变），DB 复合 PK 仅为分区合法性。
-- 维护：必须周期性预建下月分区（否则落入 pMax 兜底分区即退化为普通表）；过期分区 DROP。
--
-- 模板（以 nai_audit_log 为例·改造既有表）：
--   ALTER TABLE `nai_audit_log` DROP PRIMARY KEY, ADD PRIMARY KEY (`id`, `create_dt`);
--   ALTER TABLE `nai_audit_log` PARTITION BY RANGE (TO_DAYS(`create_dt`)) (
--     PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
--     PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
--     PARTITION pMax    VALUES LESS THAN MAXVALUE
--   );
-- 每月预建（示例·投产用调度/运维脚本执行）：
--   ALTER TABLE `nai_audit_log` REORGANIZE PARTITION pMax INTO (
--     PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
--     PARTITION pMax    VALUES LESS THAN MAXVALUE
--   );
-- 过期清理（保留 N 月后）：
--   ALTER TABLE `nai_audit_log` DROP PARTITION p202606;
-- 单分区控制在 ~5000 万行内（按写入量选 day/week/month 粒度）。
