-- =====================================================================
-- nexusai 演示数据（合并版·一站式灌库）
-- 第零部分 基座：渠道 / 密钥 / 模型目录 / 路由（INSERT IGNORE·不覆盖现有真实配置·供 fresh DB 自洽）
-- 第一部分 项目制：3 个项目 + 工作区 + 关联 sk- Key（仅 SHA-256 落库·明文见注释·本地联调用）
-- 第二部分 请求日志：500+ 模型请求记录（多模型 / 多模态 / 多状态）+ 每条可查看的请求/响应正文
-- 第三部分 控制台运维：异步任务 / 调度策略 / 故障转移链 / 代理节点 / 集群节点 / 审计 / 告警
-- 幂等：各段先清 demo 段再插（demo 前缀 / 固定 id 段 / INSERT IGNORE / ON DUPLICATE 可重复执行）。
-- 用法：mysql -h127.0.0.1 -uroot -p nexusai < nexusai-demo.sql
-- ⚠️ 开发纪律：每开发一个新界面 / 新表，必须同步补演示数据 SQL 到本文件并灌入本地 MySQL。
-- =====================================================================
SET NAMES utf8mb4;

-- =====================================================================
-- 第零部分：渠道 / 密钥 / 模型目录 / 路由 基座
-- INSERT IGNORE / ON DUPLICATE：不覆盖库内已有真实配置；fresh DB 则补出自洽基座。
-- 渠道密钥 credential 为占位（demo 渠道不真实可调·仅供渠道/选号/请求详情页渲染）。
-- =====================================================================
INSERT IGNORE INTO nai_channel (id, name, description, protocol, vendor, auth_type, base_url, status, weight, priority, tags) VALUES
(1, 'OpenAI 官方直连', '官方 OpenAI 端点', 'openai',    'openai',     'bearer', 'https://api.openai.com',          1, 100, 100, '["官方","高速"]'),
(2, 'Anthropic 企业渠道', '官方 Anthropic 端点', 'anthropic', 'anthropic', 'bearer', 'https://api.anthropic.com',       1,  80,  90, '["官方"]'),
(3, '聚合中转·备用', '第三方聚合端点·兜底', 'openai',    'aggregator', 'bearer', 'https://demo-aggregator.example.com/v1', 1, 50, 50, '["聚合","备用"]');

INSERT IGNORE INTO nai_channel_key (id, channel_id, credential, weight, priority, status, max_concurrency) VALUES
(10, 1, 'demo:not-callable-placeholder', 100, 100, 1, 0),
(11, 1, 'demo:not-callable-placeholder', 100, 100, 1, 0),
(12, 2, 'demo:not-callable-placeholder',  80,  90, 1, 0),
(13, 2, 'demo:not-callable-placeholder',  80,  90, 1, 0),
(14, 3, 'demo:not-callable-placeholder',  50,  50, 1, 0),
(15, 3, 'demo:not-callable-placeholder',  50,  50, 1, 0);

-- 模型目录（5 模态 × 多厂商·结构化价·token 费率单位 USD/百万token($/M·同 models.dev/控制台口径)，图/次按绝对 USD）：upsert 既丰富又不丢现有
INSERT INTO nai_model
  (id, model_key, model_alias, description, modality, context_length, max_output_tokens, capabilities, status, sync_official,
   input_rate, output_rate, cached_input_rate, write_cached_rate, per_request_fee, per_image_fee) VALUES
(960000000000000001, 'gpt-4o',                 'GPT-4o',            'OpenAI 旗舰多模态', 'CHAT', 128000, 16384, '["text","vision","tools"]',           1, 1, 2.5, 10, 1.25, NULL, NULL, NULL),
(960000000000000002, 'gpt-4o-mini',            'GPT-4o mini',      'OpenAI 轻量', 'CHAT', 128000, 16384, '["text","vision","tools"]',           1, 1, 0.15, 0.6, 0.075, NULL, NULL, NULL),
(960000000000000003, 'gpt-4.1',                'GPT-4.1',          'OpenAI 长上下文', 'CHAT', 1047576, 32768, '["text","vision","tools"]',         1, 1, 2, 8, 0.5, NULL, NULL, NULL),
(960000000000000004, 'gpt-4.1-mini',           'GPT-4.1 mini',     'OpenAI 长上下文轻量', 'CHAT', 1047576, 32768, '["text","tools"]',              1, 1, 0.4, 1.6, 0.1, NULL, NULL, NULL),
(960000000000000005, 'o3',                     'o3',               'OpenAI 推理', 'CHAT', 200000, 100000, '["text","reasoning","tools"]',       1, 1, 2, 8, 0.5, NULL, NULL, NULL),
(960000000000000006, 'o4-mini',                'o4-mini',          'OpenAI 推理轻量', 'CHAT', 200000, 100000, '["text","reasoning","tools"]',   1, 1, 1.1, 4.4, 0.275, NULL, NULL, NULL),
(960000000000000007, 'gpt-5.5',                'GPT-5.5',          'OpenAI 新旗舰', 'CHAT', 256000, 64000, '["text","vision","tools","reasoning"]', 1, 1, 3, 12, 0.6, NULL, NULL, NULL),
(960000000000000008, 'claude-3-5-sonnet',      'Claude 3.5 Sonnet','Anthropic 主力', 'CHAT', 200000, 8192, '["text","vision","tools"]',         1, 1, 3, 15, 0.3, 3.75, NULL, NULL),
(960000000000000009, 'claude-3-5-haiku',       'Claude 3.5 Haiku', 'Anthropic 轻量', 'CHAT', 200000, 8192, '["text","tools"]',                  1, 1, 0.8, 4, 0.08, 1, NULL, NULL),
(960000000000000010, 'claude-3-opus',          'Claude 3 Opus',    'Anthropic 高端', 'CHAT', 200000, 4096, '["text","vision","tools"]',         1, 1, 15, 75, 1.5, NULL, NULL, NULL),
(960000000000000011, 'claude-sonnet-4',        'Claude Sonnet 4',  'Anthropic 新一代', 'CHAT', 200000, 64000, '["text","vision","tools","reasoning"]', 1, 1, 3, 15, 0.3, 3.75, NULL, NULL),
(960000000000000012, 'gemini-1.5-pro',         'Gemini 1.5 Pro',   'Google 超长上下文', 'CHAT', 2000000, 8192, '["text","vision","tools"]',       1, 1, 1.25, 5, 0.3125, NULL, NULL, NULL),
(960000000000000013, 'gemini-1.5-flash',       'Gemini 1.5 Flash', 'Google 高速', 'CHAT', 1000000, 8192, '["text","vision","tools"]',           1, 1, 0.075, 0.3, 0.01875, NULL, NULL, NULL),
(960000000000000014, 'gemini-2.0-flash',       'Gemini 2.0 Flash', 'Google 新一代高速', 'CHAT', 1000000, 8192, '["text","vision","tools"]',      1, 1, 0.1, 0.4, 0.025, NULL, NULL, NULL),
(960000000000000015, 'deepseek-chat',          'DeepSeek Chat',    'DeepSeek 通用', 'CHAT', 64000, 8192, '["text","tools"]',                     1, 1, 0.27, 1.1, 0.07, NULL, NULL, NULL),
(960000000000000016, 'deepseek-reasoner',      'DeepSeek Reasoner','DeepSeek 推理', 'CHAT', 64000, 8192, '["text","reasoning"]',                 1, 1, 0.55, 2.19, 0.14, NULL, NULL, NULL),
(960000000000000017, 'qwen-max',               'Qwen Max',         '阿里通义旗舰', 'CHAT', 32768, 8192, '["text","tools"]',                      1, 1, 1.6, 6.4, NULL, NULL, NULL, NULL),
(960000000000000018, 'qwen-plus',              'Qwen Plus',        '阿里通义均衡', 'CHAT', 131072, 8192, '["text","tools"]',                     1, 1, 0.8, 2, NULL, NULL, NULL, NULL),
(960000000000000019, 'grok-2',                 'Grok 2',           'xAI 通用', 'CHAT', 131072, 8192, '["text","tools"]',                          1, 1, 2, 10, NULL, NULL, NULL, NULL),
(960000000000000020, 'mistral-large',          'Mistral Large',    'Mistral 旗舰', 'CHAT', 131072, 8192, '["text","tools"]',                     1, 1, 2, 6, NULL, NULL, NULL, NULL),
(960000000000000021, 'moonshot-v1-128k',       'Moonshot v1 128k', '月之暗面长上下文', 'CHAT', 128000, 4096, '["text"]',                         0, 1, 1.7, 1.7, NULL, NULL, NULL, NULL),
(960000000000000022, 'glm-4-plus',             'GLM-4-Plus',       '智谱旗舰', 'CHAT', 128000, 4096, '["text","tools"]',                          1, 1, 0.07, 0.07, NULL, NULL, NULL, NULL),
(960000000000000023, 'text-embedding-3-small', 'Embedding 3 Small','OpenAI 向量·小', 'EMBEDDING', 8191, NULL, '["embedding"]',                  1, 1, 0.02, NULL, NULL, NULL, NULL, NULL),
(960000000000000024, 'text-embedding-3-large', 'Embedding 3 Large','OpenAI 向量·大', 'EMBEDDING', 8191, NULL, '["embedding"]',                  1, 1, 0.13, NULL, NULL, NULL, NULL, NULL),
(960000000000000025, 'text-embedding-ada-002', 'Ada 002',          'OpenAI 向量·旧', 'EMBEDDING', 8191, NULL, '["embedding"]',                   0, 1, 0.1, NULL, NULL, NULL, NULL, NULL),
(960000000000000026, 'dall-e-3',               'DALL·E 3',         'OpenAI 图像生成', 'IMAGE', 0, NULL, '["image"]',                              1, 1, NULL, NULL, NULL, NULL, NULL, 0.04),
(960000000000000027, 'gpt-image-1',            'GPT Image 1',      'OpenAI 新图像', 'IMAGE', 0, NULL, '["image"]',                                1, 1, NULL, NULL, NULL, NULL, NULL, 0.02),
(960000000000000028, 'whisper-1',              'Whisper',          'OpenAI 语音转写', 'AUDIO', 0, NULL, '["audio"]',                              1, 1, NULL, NULL, NULL, NULL, 0.006, NULL),
(960000000000000029, 'tts-1',                  'TTS-1',            'OpenAI 语音合成', 'AUDIO', 0, NULL, '["audio"]',                              1, 1, NULL, NULL, NULL, NULL, 0.015, NULL),
(960000000000000030, 'rerank-3',               'Rerank 3',         '文档重排序', 'RERANK', 0, NULL, '["rerank"]',                                 1, 1, 0.1, NULL, NULL, NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE
  model_alias=VALUES(model_alias), description=VALUES(description), modality=VALUES(modality),
  context_length=VALUES(context_length), max_output_tokens=VALUES(max_output_tokens), capabilities=VALUES(capabilities),
  input_rate=VALUES(input_rate), output_rate=VALUES(output_rate), cached_input_rate=VALUES(cached_input_rate),
  write_cached_rate=VALUES(write_cached_rate), per_request_fee=VALUES(per_request_fee), per_image_fee=VALUES(per_image_fee);

-- 路由索引（group=default·把主力模型挂到对应渠道·priority 高者优先·weight 加权随机）
INSERT IGNORE INTO nai_ability (group_code, model, channel_id, priority, weight, enabled, modality) VALUES
('default', 'gpt-4o',            1, 100, 100, 1, 'CHAT'),
('default', 'gpt-4o',            3,  50,  50, 1, 'CHAT'),
('default', 'gpt-4o-mini',       1, 100, 100, 1, 'CHAT'),
('default', 'gpt-4.1',           1, 100, 100, 1, 'CHAT'),
('default', 'claude-3-5-sonnet', 2, 100, 100, 1, 'CHAT'),
('default', 'claude-3-5-sonnet', 3,  40,  40, 1, 'CHAT'),
('default', 'claude-sonnet-4',   2, 100, 100, 1, 'CHAT'),
('default', 'gemini-1.5-pro',    3, 100, 100, 1, 'CHAT'),
('default', 'deepseek-chat',     3, 100, 100, 1, 'CHAT'),
('default', 'text-embedding-3-small', 1, 100, 100, 1, 'EMBEDDING'),
('default', 'dall-e-3',          1, 100, 100, 1, 'IMAGE');

-- =====================================================================
-- 第一部分：项目 / 工作区 / Key 基座
-- =====================================================================
-- 仅清演示项目（按本脚本固定雪花 id 作用域）·绝不无条件清空 nai_project（误在含真实数据的库执行会清空全部项目）
DELETE FROM nai_project WHERE id IN (2064547591905660930, 2064547591905660931, 2064547591905660932);

-- 项目（id 故意用 19 位雪花量级·project_code/token 唯一·quota_usd=项目 USD 额度池 DB 基线）
INSERT INTO nai_project
  (id, project_code, name, description, token, scope,
   allowed_models, allowed_modalities, allowed_channel_ids, allowed_channel_tags, channel_tags_match_mode,
   quota_usd, unlimited, status)
VALUES
  (2064547591905660930, 'my-saas', '示例商城后台',
   '示例商城业务面接入演示（控制面 HTTP + 用量回传）',
   'nai_proj_demo_biz_8f3kQ2pLxV9mTzWn', 'platform',
   '["gpt-4o","gpt-4o-mini","deepseek-chat"]', '["chat","embedding"]', NULL, NULL, 'any',
   50.000000, 0, 1),
  (2064547591905660931, 'mobile-app', '移动端 App',
   '小程序/APP 直连接入·仅允许走官方标签渠道·内部不限额项目',
   'nai_proj_demo_mobile_Jr5xHb2cYd7eAq1u', 'project',
   NULL, '["chat"]', NULL, '["官方"]', 'any',
   0, 1, 1),
  (2064547591905660932, 'data-platform', '数据分析平台',
   '旧数据平台项目（已下线归档·凭证已吊销）',
   'nai_proj_demo_data_Kv9zNc4wXf6gBh3m', 'project',
   NULL, NULL, NULL, NULL, 'any',
   0, 0, 0);

-- 渠道标签（供项目"允许的渠道标签"策略匹配）
UPDATE nai_channel SET tags = '["官方","高速"]' WHERE id = 1;
UPDATE nai_channel SET tags = '["官方"]'        WHERE id = 2;
UPDATE nai_channel SET tags = '["聚合","备用"]' WHERE id = 3;

-- 挂项目的演示 sk-（key_hash = SHA-256(明文)·明文不落库；额度走项目池，900001 另带 Key 子限额 $10 演示）：
--   sk-demo-biz-001  → my-saas（受模型/能力策略约束·子限额 $10）
--   sk-demo-mobile-001 → mobile-app（仅官方标签渠道·无子限额）
DELETE FROM nai_token WHERE id IN (900001, 900002);
INSERT INTO nai_token (id, key_hash, project_code, group_code, quota_usd, status)
VALUES
  (900001, SHA2('sk-demo-biz-001', 256),  'my-saas', 'default', 10.000000, 1),
  (900002, SHA2('sk-demo-mobile-001', 256), 'mobile-app', 'default', NULL, 1);

-- 项目内工作区（三层身份链·B2C 中转站形态演示：mobile-app=站点(项目池不限额)·散户 USD 钱包为主闸）
DELETE FROM nai_workspace WHERE id IN (950001, 950002);
INSERT INTO nai_workspace (id, project_code, external_id, display_name, quota_usd, unlimited, status)
VALUES
  (950001, 'mobile-app', 'ext-1001', '演示散户·小明', 5.000000, 0, 1),
  (950002, 'mobile-app', 'ext-1002', '演示散户·小红', 0, 0, 1);

-- 挂工作区的演示 sk-（B2C：扣小明的钱包·明文 sk-demo-enduser-001 仅本地联调）
DELETE FROM nai_token WHERE id = 900003;
INSERT INTO nai_token (id, key_hash, project_code, workspace_id, group_code, quota_usd, status)
VALUES (900003, SHA2('sk-demo-enduser-001', 256), 'mobile-app', 950001, 'default', NULL, 1);

-- 控制台运维账号（双身份演示）：默认超管 admin 由 AdminUserSeeder 首启播种(不在此重复)；
-- 这里仅演示一个「项目运维」账号 demo-ops（口令 ops123·PBKDF2 哈希·仅本地联调），授权 my-saas + mobile-app 两项目。
DELETE FROM nai_admin_user_project WHERE admin_user_id = 960001;
DELETE FROM nai_admin_user WHERE id = 960001;
INSERT INTO nai_admin_user (id, username, password, role, status)
VALUES (960001, 'demo-ops', 'pbkdf2$120000$ABEiM0RVZneImaq7zN3u/w==$qu4t7P7mug7YqaATovPJz8GYLLOLBE7zKezg5ieLxQM=', 1, 1);
INSERT INTO nai_admin_user_project (id, admin_user_id, project_code)
VALUES (960101, 960001, 'my-saas'),
       (960102, 960001, 'mobile-app');

-- 独立密钥全功能演示（展示 /keys 页丰富列：白名单四件套/限流/并发/过期·明文 sk-demo-standalone-001）
DELETE FROM nai_token WHERE id = 900004;
INSERT INTO nai_token (id, key_hash, name, standalone, project_code, group_code, model_limits, allow_ips, allow_api_formats, quota_usd, rpm_limit, tpm_limit, concurrent_limit, expired_time, auto_delete_on_expiry, status)
VALUES (900004, SHA2('sk-demo-standalone-001', 256), '演示·全功能独立密钥', 1, 'my-saas', 'default', '["gpt-4o","claude-3-5-sonnet"]', '203.0.113.0/24', '["openai","anthropic"]', 5.000000, 60, 120000, 5, 1798761600000, 0, 1);

-- =====================================================================
-- 第二部分：模型请求日志 + 请求体（驱动 用量分析 / 仪表盘 / 健康 / 性能 / 请求详情抽屉）
-- 生成 500 条 req-demo-t% 请求记录（多模型 / 多状态 / 含重试·故障转移·缓存命中·流式），
-- 并为每条补可查看的请求/响应正文（store=rdb·正文查看器目检；正文以明文存，读取端兼容非 gzip）。
-- =====================================================================
DELETE FROM nai_request_log  WHERE request_id LIKE 'req-demo-%';
DELETE FROM nai_request_body WHERE request_id LIKE 'req-demo-%';

INSERT INTO nai_request_log
  (id, request_id, trace_id, project_code, workspace_id, channel_id, key_id, model, target_model, modality,
   stream_mode, api_format, endpoint_api_format, prompt_tokens, completion_tokens, cache_tokens, cache_creation_tokens,
   total_tokens, cost_usd, status, http_status, error_code, retry_count, fallback_count, usage_source,
   ttft_ms, duration_ms, client_ip, user_agent, client_family, prompt_hash, create_dt, update_dt)
SELECT
  970000000000000000 + seq,
  CONCAT('req-demo-t', LPAD(seq, 6, '0')),
  CONCAT('trace-', SUBSTRING(MD5(seq), 1, 16)),
  CASE WHEN MOD(seq,10) < 5 THEN 'my-saas' WHEN MOD(seq,10) < 9 THEN 'mobile-app' ELSE 'data-platform' END,
  CASE WHEN MOD(seq,10) BETWEEN 5 AND 8 THEN ELT(MOD(seq,2)+1, 950001, 950002) END,
  ELT(MOD(seq,3)+1, 1, 2, 3),
  ELT(MOD(seq,6)+1, 10, 11, 12, 13, 14, 15),
  ELT(MOD(seq,10)+1, 'gpt-4o','claude-3-5-sonnet','gemini-1.5-pro','deepseek-chat','gpt-4o-mini','claude-sonnet-4','gemini-2.0-flash','qwen-max','o4-mini','grok-2'),
  ELT(MOD(seq,10)+1, 'gpt-4o','claude-3-5-sonnet','gemini-1.5-pro','deepseek-chat','gpt-4o-mini','claude-sonnet-4','gemini-2.0-flash','qwen-max','o4-mini','grok-2'),
  'chat',
  MOD(seq,2),
  ELT(MOD(seq,10)+1, 'openai:chat','claude:messages','gemini:generateContent','openai:chat','openai:chat','claude:messages','gemini:generateContent','openai:chat','openai:chat','openai:chat'),
  ELT(MOD(seq,10)+1, 'openai:chat','claude:messages','gemini:generateContent','openai:chat','openai:chat','claude:messages','gemini:generateContent','openai:chat','openai:chat','openai:chat'),
  pt, ct,
  CASE WHEN MOD(seq,10)+1 IN (2,6) AND MOD(seq,3) = 0 THEN FLOOR(pt * 0.4) ELSE 0 END,
  0,
  pt + ct,
  ROUND(pt * ELT(MOD(seq,10)+1, 0.0000025,0.000003,0.00000125,0.00000027,0.00000015,0.000003,0.0000001,0.0000016,0.0000011,0.000002)
      + ct * ELT(MOD(seq,10)+1, 0.00001,0.000015,0.000005,0.0000011,0.0000006,0.000015,0.0000004,0.0000064,0.0000044,0.00001), 6),
  CASE WHEN MOD(seq,20) < 17 THEN 1 ELSE 0 END,
  CASE WHEN MOD(seq,20) < 17 THEN 200 WHEN MOD(seq,20) = 17 THEN 429 WHEN MOD(seq,20) = 18 THEN 500 ELSE 503 END,
  CASE WHEN MOD(seq,20) = 17 THEN 'rate_limit_exceeded' WHEN MOD(seq,20) = 18 THEN 'upstream_error'
       WHEN MOD(seq,20) = 19 THEN 'no_channel_available' END,
  CASE WHEN MOD(seq,7) = 0 THEN 1 + MOD(seq,2) ELSE 0 END,
  CASE WHEN MOD(seq,11) = 0 THEN 1 ELSE 0 END,
  CASE WHEN MOD(seq,20) >= 17 THEN 'failed'
       WHEN MOD(seq,2) = 1 AND MOD(seq,23) = 0 THEN 'client-abort'
       WHEN MOD(seq,13) = 0 THEN 'estimated' ELSE 'parsed' END,
  CASE WHEN MOD(seq,2) = 1 THEN 80 + MOD(seq*7, 1200) END,
  300 + MOD(seq*53, 24000),
  ELT(MOD(seq,4)+1, '203.0.113.10', '198.51.100.20', '192.0.2.30', '203.0.113.50'),
  ELT(MOD(seq,4)+1, 'claude-cli/1.0.83 (external)', 'codex_cli_rs/0.5.0', 'python-httpx/0.27.0', 'Mozilla/5.0 (Macintosh)'),
  ELT(MOD(seq,4)+1, 'claude_code', 'codex', 'python', 'browser'),
  SHA2(CONCAT('req-demo-', seq), 256),
  NOW() - INTERVAL MOD(seq*43, 43200) MINUTE,
  NOW() - INTERVAL MOD(seq*43, 43200) MINUTE
FROM (
  SELECT (t1.n + t2.n*10 + t3.n*100) + 1 AS seq,
         200 + MOD((t1.n + t2.n*10 + t3.n*100 + 1) * 31, 6000) AS pt,
         30  + MOD((t1.n + t2.n*10 + t3.n*100 + 1) * 17, 1800) AS ct
  FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
       (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
       (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) t3
) seqs;

-- 每条请求的可查看正文（请求详情抽屉 JSON 树/对话视图）：req+resp 明文（读取端兼容非 gzip）。
-- 跳过 t000273/t000276（下方控制台段另插更丰富的 tool-call/多模态正文示例）。
INSERT INTO nai_request_body (request_id, req_body, resp_body, create_dt)
SELECT l.request_id,
  CONCAT('{"model":"', l.model, '","stream":', IF(l.stream_mode=1,'true','false'),
         ',"messages":[{"role":"system","content":"You are a concise assistant."},{"role":"user","content":"',
         ELT(MOD(l.id,5)+1,
             '用一句话解释什么是 AI 中转网关。',
             '把这句话翻译成英文：你好，世界。',
             '写一个 Python 快速排序函数。',
             '总结这段会议纪要的三个要点。',
             '推荐三本机器学习入门书。'),
         '"}],"temperature":0.7}'),
  CASE WHEN l.status = 1 THEN
    CONCAT('{"id":"chatcmpl-', SUBSTRING(MD5(l.request_id),1,12), '","object":"chat.completion","model":"', l.model,
           '","choices":[{"index":0,"message":{"role":"assistant","content":"',
           ELT(MOD(l.id,5)+1,
               'AI 中转网关是统一转发、计量并按用量计费的中间层。',
               'Hello, world.',
               'def quicksort(a): return a if len(a)<2 else quicksort([x for x in a[1:] if x<a[0]])+[a[0]]+quicksort([x for x in a[1:] if x>=a[0]])',
               '要点：1) 明确目标 2) 分配责任 3) 设定截止日期。',
               '《机器学习》《深度学习》《统计学习方法》。'),
           '"},"finish_reason":"stop"}],"usage":{"prompt_tokens":', l.prompt_tokens,
           ',"completion_tokens":', l.completion_tokens, ',"total_tokens":', l.total_tokens, '}}')
  ELSE
    CONCAT('{"error":{"message":"', COALESCE(l.error_code,'error'), '","type":"', COALESCE(l.error_code,'error'),
           '","code":"', COALESCE(l.error_code,'error'), '"}}')
  END,
  l.create_dt
FROM nai_request_log l
WHERE l.request_id LIKE 'req-demo-t%'
  AND l.request_id NOT IN ('req-demo-t000273', 'req-demo-t000276');

-- 非聊天模态请求（embedding / image / audio / rerank·120 行·驱动用量按模态/协议分布与成本形态多样）
INSERT INTO nai_request_log
  (id, request_id, trace_id, project_code, workspace_id, channel_id, key_id, model, target_model, modality,
   stream_mode, api_format, endpoint_api_format, prompt_tokens, completion_tokens, cache_tokens, cache_creation_tokens,
   total_tokens, cost_usd, status, http_status, error_code, retry_count, fallback_count, usage_source,
   ttft_ms, duration_ms, client_ip, user_agent, client_family, prompt_hash, create_dt, update_dt)
SELECT
  971000000000000000 + seq,
  CONCAT('req-demo-m', LPAD(seq, 5, '0')),
  CONCAT('trace-', SUBSTRING(MD5(seq + 9000), 1, 16)),
  CASE WHEN MOD(seq,10) < 6 THEN 'my-saas' WHEN MOD(seq,10) < 9 THEN 'mobile-app' ELSE 'data-platform' END,
  CASE WHEN MOD(seq,10) BETWEEN 6 AND 8 THEN ELT(MOD(seq,2)+1, 950001, 950002) END,
  ELT(MOD(seq,3)+1, 1, 2, 3),
  ELT(MOD(seq,6)+1, 10, 11, 12, 13, 14, 15),
  ELT(MOD(seq,4)+1, 'text-embedding-3-small', 'dall-e-3', 'whisper-1', 'rerank-3'),
  ELT(MOD(seq,4)+1, 'text-embedding-3-small', 'dall-e-3', 'whisper-1', 'rerank-3'),
  ELT(MOD(seq,4)+1, 'embedding', 'image', 'audio', 'rerank'),
  0,
  ELT(MOD(seq,4)+1, 'openai:embeddings', 'openai:images', 'openai:audio', 'openai:rerank'),
  ELT(MOD(seq,4)+1, 'openai:embeddings', 'openai:images', 'openai:audio', 'openai:rerank'),
  ELT(MOD(seq,4)+1, pt, 0, 0, pt),
  0, 0, 0,
  ELT(MOD(seq,4)+1, pt, 0, 0, pt),
  ROUND(ELT(MOD(seq,4)+1, pt * 0.00000002, 0.04, 0.006, pt * 0.0000001), 6),
  CASE WHEN MOD(seq,15) < 13 THEN 1 ELSE 0 END,
  CASE WHEN MOD(seq,15) < 13 THEN 200 WHEN MOD(seq,15) = 13 THEN 429 ELSE 400 END,
  CASE WHEN MOD(seq,15) = 13 THEN 'rate_limit_exceeded' WHEN MOD(seq,15) = 14 THEN 'invalid_request_error' END,
  0, 0,
  CASE WHEN MOD(seq,15) >= 13 THEN 'failed' ELSE 'parsed' END,
  NULL,
  150 + MOD(seq*37, 8000),
  ELT(MOD(seq,4)+1, '203.0.113.10', '198.51.100.20', '192.0.2.30', '203.0.113.50'),
  ELT(MOD(seq,4)+1, 'claude-cli/1.0.83 (external)', 'codex_cli_rs/0.5.0', 'python-httpx/0.27.0', 'Mozilla/5.0 (Macintosh)'),
  ELT(MOD(seq,4)+1, 'claude_code', 'codex', 'python', 'browser'),
  SHA2(CONCAT('req-demo-m', seq), 256),
  NOW() - INTERVAL MOD(seq*97, 43200) MINUTE,
  NOW() - INTERVAL MOD(seq*97, 43200) MINUTE
FROM (
  SELECT (t1.n + t2.n*10) + 1 AS seq,
         100 + MOD(((t1.n + t2.n*10) + 1) * 23, 3000) AS pt
  FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
       (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
        UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10 UNION SELECT 11) t2
) seqs;

-- 非聊天模态正文（按模态构造 req/resp·请求详情可看）
INSERT INTO nai_request_body (request_id, req_body, resp_body, create_dt)
SELECT l.request_id,
  CASE l.modality
    WHEN 'embedding' THEN CONCAT('{"model":"', l.model, '","input":"需要向量化的演示文本片段 #', SUBSTRING(MD5(l.request_id),1,6), '"}')
    WHEN 'image' THEN '{"model":"dall-e-3","prompt":"a serene mountain lake at dawn, ultra detailed","size":"1024x1024","n":1}'
    WHEN 'audio' THEN '{"model":"whisper-1","file":"(multipart) demo-audio.mp3","response_format":"json"}'
    ELSE '{"model":"rerank-3","query":"什么是 AI 中转网关","top_n":3,"documents":["文档A","文档B","文档C"]}'
  END,
  CASE
    WHEN l.status <> 1 THEN CONCAT('{"error":{"message":"', COALESCE(l.error_code,'error'), '","type":"', COALESCE(l.error_code,'error'), '"}}')
    WHEN l.modality = 'embedding' THEN CONCAT('{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.012,-0.034,0.056,0.078]}],"model":"', l.model, '","usage":{"prompt_tokens":', l.prompt_tokens, ',"total_tokens":', l.total_tokens, '}}')
    WHEN l.modality = 'image' THEN '{"created":1717000000,"data":[{"url":"https://cdn.demo-upstream.com/img/demo.png","revised_prompt":"a serene mountain lake at dawn"}]}'
    WHEN l.modality = 'audio' THEN '{"text":"这是一段语音转写的演示文本结果。"}'
    ELSE '{"results":[{"index":1,"relevance_score":0.97},{"index":0,"relevance_score":0.61},{"index":2,"relevance_score":0.42}]}'
  END,
  l.create_dt
FROM nai_request_log l WHERE l.request_id LIKE 'req-demo-m%';

-- 用量事件 outbox（每条成功请求一条·cost_basis≈上游成本·与 cost_usd 差为毛利·delivered 少量积压演示）
DELETE FROM nai_usage_log WHERE request_id LIKE 'req-demo-%';
INSERT INTO nai_usage_log
  (id, request_id, project_code, workspace_id, channel_id, key_id, model,
   prompt_tokens, completion_tokens, cache_tokens, cache_creation_tokens, total_tokens,
   cost_basis, cost_usd, status, delivered, create_dt, update_dt)
SELECT 972000000000000000 + (l.id - 970000000000000000),
   l.request_id, l.project_code, l.workspace_id, l.channel_id, l.key_id, l.model,
   l.prompt_tokens, l.completion_tokens, l.cache_tokens, l.cache_creation_tokens, l.total_tokens,
   ROUND(l.cost_usd * 0.7, 8), l.cost_usd, 1,
   CASE WHEN MOD(l.id, 30) = 0 THEN 0 ELSE 1 END,
   l.create_dt, l.create_dt
FROM nai_request_log l WHERE l.request_id LIKE 'req-demo-t%' AND l.status = 1;

-- =====================================================================
-- 第三部分：控制台运维数据
-- =====================================================================
-- ===================== 异步任务（async-tasks 页·nai_task） =====================
DELETE FROM `nai_task` WHERE `task_id` LIKE 'task-demo-%';

INSERT INTO `nai_task`
  (`id`,`task_id`,`upstream_task_id`,`project_code`,`token_id`,`channel_id`,`key_id`,`modality`,`action`,`model`,
   `status`,`progress`,`frozen_usd`,`result_url`,`fail_reason`,`submit_body`,`create_dt`,`update_dt`)
SELECT
  980000000000000000 + seq,
  CONCAT('task-demo-', LPAD(seq, 4, '0')),
  CONCAT('up-', SUBSTRING(MD5(seq), 1, 12)),
  ELT(MOD(seq, 3) + 1, 'my-saas', 'mobile-app', 'data-platform'),
  ELT(MOD(seq, 2) + 1, 900001, 900002),
  ELT(MOD(seq, 3) + 1, 1, 3, 3),
  ELT(MOD(seq, 3) + 1, 10, 14, 15),
  ELT(MOD(seq, 5) + 1, 'image', 'video', 'image', 'image', 'video'),
  ELT(MOD(seq, 5) + 1, 'generate', 'generate', 'upscale', 'variation', 'generate'),
  ELT(MOD(seq, 5) + 1, 'mj-v6', 'video-gen-1', 'mj-v6', 'mj-v6', 'video-gen-1'),
  -- 状态分布：60% SUCCESS / 15% FAILED / 15% IN_PROGRESS / 10% SUBMITTED
  CASE WHEN MOD(seq, 20) < 12 THEN 'SUCCESS'
       WHEN MOD(seq, 20) < 15 THEN 'FAILED'
       WHEN MOD(seq, 20) < 18 THEN 'IN_PROGRESS'
       ELSE 'SUBMITTED' END,
  CASE WHEN MOD(seq, 20) < 12 THEN 100
       WHEN MOD(seq, 20) < 15 THEN 0
       WHEN MOD(seq, 20) < 18 THEN 10 + MOD(seq * 7, 80)
       ELSE 0 END,
  120 + MOD(seq * 13, 900),
  CASE WHEN MOD(seq, 20) < 12 THEN CONCAT('https://cdn.demo-upstream.com/results/task-demo-', LPAD(seq, 4, '0'), '.png') END,
  CASE WHEN MOD(seq, 20) BETWEEN 12 AND 14
       THEN ELT(MOD(seq, 3) + 1, '上游生成超时（>600s）', '内容安全审核未通过', '上游配额耗尽（quota_exceeded）') END,
  '{"prompt":"a futuristic relay gateway control room, blueprint style","size":"1024x1024"}',
  NOW() - INTERVAL MOD(seq * 37, 10080) MINUTE,
  NOW() - INTERVAL MOD(seq * 17, 9000) MINUTE
FROM (
  SELECT (t1.n + t2.n * 10) AS seq
  FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
       (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3) t2
) seqs
WHERE seq < 36;

-- ===================== 调度策略（routing 页·nai_routing_group） =====================
DELETE FROM `nai_routing_group` WHERE `name` IN ('缓存亲和优先（演示）', '低成本兜底（演示）');

INSERT INTO `nai_routing_group` (`id`,`name`,`description`,`enabled`,`is_system_default`,`config_json`,`version`,`create_dt`)
VALUES
  (981000000000000001, '缓存亲和优先（演示）', '会话亲和锚定同 Key 复用上游 prompt cache，长对话省输入费',
   1, 0,
   '{"defaultPolicy":{"priorityMode":"key","schedulingMode":"cache_affinity"},"modelPolicies":[{"model":"claude-3-5-sonnet","priorityMode":"key","schedulingMode":"cache_affinity"},{"model":"gpt-4o","priorityMode":"provider","schedulingMode":"load_balance"}]}',
   3, NOW() - INTERVAL 5 DAY),
  (981000000000000002, '低成本兜底（演示）', '按渠道优先级固定顺序，官方直连优先、聚合中转兜底',
   0, 0,
   '{"defaultPolicy":{"priorityMode":"provider","schedulingMode":"fixed_order"}}',
   1, NOW() - INTERVAL 2 DAY);

-- ===================== 故障转移链（健康/性能/详情抽屉·nai_failover_event） =====================
-- 给带 retry/fallback 计数的演示请求补事件链（详情抽屉「请求链路」有数可看）
DELETE FROM `nai_failover_event` WHERE `reason` LIKE 'demo:%';

-- 重试链：retry_count>0 的请求 → 每条 1~2 个 retry 事件（同渠道换 Key）
INSERT INTO `nai_failover_event` (`id`,`request_id`,`attempt`,`from_channel_id`,`from_key_id`,`error_code`,`reason`,`outcome`,`create_dt`)
SELECT 982000000000000000 + ROW_NUMBER() OVER (ORDER BY id),
       request_id, 1, channel_id,
       ELT(MOD(id, 6) + 1, 10, 11, 12, 13, 14, 15),
       ELT(MOD(id, 3) + 1, '429', '401', '429'),
       ELT(MOD(id, 3) + 1, 'demo:upstream-429 限流，换 Key 重试', 'demo:Key 鉴权失败，封禁换号', 'demo:upstream-429 限流，换 Key 重试'),
       'retry',
       create_dt - INTERVAL 2 SECOND
FROM nai_request_log WHERE retry_count > 0 AND request_id LIKE 'req-demo-%' LIMIT 120;

-- 转移链：fallback_count>0 的请求 → 渠道切换事件
INSERT INTO `nai_failover_event` (`id`,`request_id`,`attempt`,`from_channel_id`,`from_key_id`,`error_code`,`reason`,`outcome`,`create_dt`)
SELECT 982100000000000000 + ROW_NUMBER() OVER (ORDER BY id),
       request_id, 1, ELT(MOD(id, 2) + 1, 1, 2),
       ELT(MOD(id, 2) + 1, 10, 12),
       '503',
       'demo:upstream-503 渠道故障，跨渠道转移',
       'retry',
       create_dt - INTERVAL 3 SECOND
FROM nai_request_log WHERE fallback_count > 0 AND request_id LIKE 'req-demo-%' LIMIT 80;

-- 校验
SELECT 'nai_task' tbl, COUNT(*) cnt FROM nai_task WHERE task_id LIKE 'task-demo-%'
UNION ALL SELECT 'nai_routing_group', COUNT(*) FROM nai_routing_group
UNION ALL SELECT 'nai_failover_event(demo)', COUNT(*) FROM nai_failover_event WHERE reason LIKE 'demo:%';

-- ===================== 缓存监控（cache-monitor 页·演示用测试 Token） =====================
-- 已知明文 sk-cache-demo-0611（仅本地演示）：向 8600 发请求即可生成会话亲和条目（亲和列表/4 统计卡有数）；
-- 命中分析/TTL 分析吃既有 nai_request_log（cache_tokens 演示行 ~2000 条），无需新表。
INSERT INTO `nai_token` (`id`,`key_hash`,`project_code`,`group_code`,`status`,`quota_usd`,`create_dt`)
VALUES (900099, SHA2('sk-cache-demo-0611', 256), 'my-saas', 'default', 1, NULL, NOW())
ON DUPLICATE KEY UPDATE `key_hash`=SHA2('sk-cache-demo-0611', 256), `status`=1;

-- ===================== 缓存创建 tokens 演示回填（cache-monitor 命中概览「缓存创建费用」格） =====================
-- Anthropic 口径：有读命中的 claude 行回填创建写入 ≈ prompt 的 8%~20%（幂等·仅 demo 行）
UPDATE `nai_request_log`
SET `cache_creation_tokens` = FLOOR(`prompt_tokens` * (0.08 + MOD(`id`, 13) * 0.01))
WHERE `request_id` LIKE 'req-demo-%' AND `model` LIKE 'claude%' AND `cache_tokens` > 0;

-- ============================================================
-- 代理节点 / 系统设置 演示数据（2026-06-11·幂等·demo 前缀可识别）
-- ============================================================
DELETE FROM nai_proxy_node_event WHERE node_id BETWEEN 910001 AND 910006;
DELETE FROM nai_proxy_node_metric WHERE node_id BETWEEN 910001 AND 910006;
DELETE FROM nai_proxy_node WHERE id BETWEEN 910001 AND 910006;

INSERT INTO nai_proxy_node (id, name, proxy_url, username, password_enc, region, status, total_requests, failed_requests, avg_latency_ms, last_probe_at, last_ok_at, create_dt) VALUES
(910001, 'demo-美西出口', 'http://demo-us-west.proxy.local:8080', 'demo', NULL, 'US-West', 'online', 152340, 312, 186.4, NOW(), NOW(), DATE_SUB(NOW(), INTERVAL 21 DAY)),
(910002, 'demo-日本东京', 'socks5://demo-jp.proxy.local:1080', NULL, NULL, 'JP-Tokyo', 'online', 98220, 1207, 92.1, NOW(), NOW(), DATE_SUB(NOW(), INTERVAL 18 DAY)),
(910003, 'demo-香港直连', 'http://demo-hk.proxy.local:3128', NULL, NULL, 'HK', 'online', 285930, 145, 48.6, NOW(), NOW(), DATE_SUB(NOW(), INTERVAL 15 DAY)),
(910004, 'demo-新加坡', 'socks5://demo-sg.proxy.local:1080', 'demo', NULL, 'SG', 'offline', 45110, 5210, 134.0, NOW(), DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 12 DAY)),
(910005, 'demo-德国法兰克福', 'http://demo-de.proxy.local:8080', NULL, NULL, 'DE-Frankfurt', 'online', 12480, 36, 245.9, NOW(), NOW(), DATE_SUB(NOW(), INTERVAL 6 DAY)),
(910006, 'demo-备用线路', 'socks5h://demo-backup.proxy.local:1080', NULL, NULL, NULL, 'offline', 0, 0, NULL, NOW(), NULL, DATE_SUB(NOW(), INTERVAL 2 DAY));

-- 连接事件（最近 7 天·状态翻转）
INSERT INTO nai_proxy_node_event (node_id, event_type, detail, create_dt)
SELECT 910004, 'disconnected', '探测失败: connection timed out', DATE_SUB(NOW(), INTERVAL 3 HOUR)
UNION ALL SELECT 910004, 'error', '探测失败: connection refused', DATE_SUB(NOW(), INTERVAL 2 HOUR)
UNION ALL SELECT 910004, 'error', '探测失败: connection timed out', DATE_SUB(NOW(), INTERVAL 1 HOUR)
UNION ALL SELECT 910002, 'disconnected', '探测失败: read timeout', DATE_SUB(NOW(), INTERVAL 2 DAY)
UNION ALL SELECT 910002, 'connected', '探测恢复，延迟 95ms', DATE_SUB(NOW(), INTERVAL 47 HOUR)
UNION ALL SELECT 910001, 'connected', '探测恢复，延迟 190ms', DATE_SUB(NOW(), INTERVAL 5 DAY)
UNION ALL SELECT 910006, 'disconnected', '探测失败: no route to host', DATE_SUB(NOW(), INTERVAL 1 DAY);

-- 24h 指标桶（每节点每小时一桶·在线节点高在线率·910004 近 3h 故障）
INSERT INTO nai_proxy_node_metric (node_id, bucket_start, samples, ok_samples, latency_sum_ms, latency_max_ms, errors)
SELECT n.id,
       DATE_FORMAT(DATE_SUB(NOW(), INTERVAL h.h HOUR), '%Y-%m-%d %H:00:00'),
       60,
       CASE WHEN n.id = 910004 AND h.h < 3 THEN 0
            WHEN n.id = 910006 THEN 0
            WHEN h.h = 7 THEN 57
            ELSE 60 END,
       CASE WHEN n.id = 910004 AND h.h < 3 THEN 0
            WHEN n.id = 910006 THEN 0
            ELSE ROUND(60 * COALESCE(n.avg_latency_ms, 100) * (0.9 + (h.h % 5) * 0.05)) END,
       CASE WHEN n.id = 910004 AND h.h < 3 THEN 0
            WHEN n.id = 910006 THEN 0
            ELSE ROUND(COALESCE(n.avg_latency_ms, 100) * 1.8) END,
       CASE WHEN n.id = 910004 AND h.h < 3 THEN 60
            WHEN n.id = 910006 THEN 60
            WHEN h.h = 7 THEN 3
            ELSE 0 END
FROM nai_proxy_node n
JOIN (SELECT 0 h UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7
      UNION SELECT 8 UNION SELECT 9 UNION SELECT 10 UNION SELECT 11 UNION SELECT 12 UNION SELECT 13 UNION SELECT 14 UNION SELECT 15
      UNION SELECT 16 UNION SELECT 17 UNION SELECT 18 UNION SELECT 19 UNION SELECT 20 UNION SELECT 21 UNION SELECT 22 UNION SELECT 23) h
WHERE n.id BETWEEN 910001 AND 910006;

-- 系统设置默认行（幂等）
INSERT INTO nai_system_config (cfg_key, cfg_value, description) VALUES
('site_name', '"NexusAI"', '站点名称'),
('site_subtitle', '"AI Gateway"', '站点副标题'),
('enable_auto_cleanup', 'true', '记录自动清理开关'),
('cleanup_time', '"03:30"', '每日清理时间'),
('log_retention_days', '30', '请求日志保留天数'),
('enable_proxy_node_probe', 'true', '代理节点探测开关')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;

-- ============================================================
-- 集群节点演示数据（2026-06-11·幂等·demo 节点：1 离线 worker·真实本机节点由心跳自动注册）
-- ============================================================
DELETE FROM nai_node WHERE node_id IN ('10.0.8.21:8600', '10.0.8.22:8600');
INSERT INTO nai_node (id, node_id, host, port, version, started_at, role, active_requests, cpu_percent, heap_used_mb, heap_max_mb, labels, create_dt, update_dt) VALUES
(920001, '10.0.8.21:8600', '10.0.8.21', 8600, '25.0.1+9-LTS', UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 9 DAY))*1000, 'worker', 0, 12.4, 1830, 4096, '{"zone":"cn-hk-1"}', DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 5 HOUR)),
(920002, '10.0.8.22:8600', '10.0.8.22', 8600, '25.0.1+9-LTS', UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 3 DAY))*1000, 'worker', 0, 8.1, 1102, 4096, '{"zone":"cn-hk-1"}', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY));

-- 审计日志演示数据（demo·2026-06-11）
INSERT INTO nai_audit_log (actor, event_type, description, path, method, ip, status_code) VALUES
('admin', 'channel_created', 'POST /admin/channels（demo）', '/admin/channels', 'POST', '192.168.1.10', 200),
('admin', 'config_changed', 'PUT /admin/system/configs（demo）', '/admin/system/configs', 'PUT', '192.168.1.10', 200),
('project:demo-biz', 'token_created', 'POST /admin/tokens（demo）', '/admin/tokens', 'POST', '10.0.0.8', 200),
('admin', 'module_config_changed', 'PUT /admin/modules/response_cache/settings（demo）', '/admin/modules/response_cache/settings', 'PUT', '192.168.1.10', 200),
('unknown', 'admin_login_failed', '控制台登录失败（demo）', NULL, NULL, '203.0.113.7', NULL);

-- 运维监控演示数据（2026-06-11·告警规则 + 告警事件·demo 前缀幂等）
INSERT INTO nai_alert_rule (name, description, enabled_flag, metric_type, operator, threshold, window_minutes, sustained_minutes, cooldown_minutes, severity, notify_flag)
SELECT 'demo-错误率超 5%', 'demo 演示规则：5 分钟窗口错误率 ≥ 5% 触发 P1', 1, 'error_rate', '>=', 5, 5, 2, 10, 'P1', 1
WHERE NOT EXISTS (SELECT 1 FROM nai_alert_rule WHERE name = 'demo-错误率超 5%');

INSERT INTO nai_alert_rule (name, description, enabled_flag, metric_type, operator, threshold, window_minutes, sustained_minutes, cooldown_minutes, severity, notify_flag)
SELECT 'demo-P99 延迟超 30s', 'demo 演示规则：P99 延迟 ≥ 30000ms 触发 P2', 1, 'p99_latency_ms', '>=', 30000, 10, 3, 15, 'P2', 1
WHERE NOT EXISTS (SELECT 1 FROM nai_alert_rule WHERE name = 'demo-P99 延迟超 30s');

INSERT INTO nai_alert_rule (name, description, enabled_flag, metric_type, operator, threshold, window_minutes, sustained_minutes, cooldown_minutes, severity, notify_flag)
SELECT 'demo-熔断告警', 'demo 演示规则：熔断的渠道/Key 总数 > 0 触发 P2', 0, 'circuit_open_count', '>', 0, 5, 1, 30, 'P2', 1
WHERE NOT EXISTS (SELECT 1 FROM nai_alert_rule WHERE name = 'demo-熔断告警');

INSERT INTO nai_alert_event (rule_id, rule_name, severity, status, title, description, metric_value, threshold_value, fired_dt, resolved_dt, notified_flag)
SELECT r.id, r.name, 'P1', 'resolved', '[P1] demo-错误率超 5%', 'error_rate = 12.5 >= 5（窗口 5 分钟·持续 2 分钟）（demo）', 12.5, 5, NOW() - INTERVAL 6 HOUR, NOW() - INTERVAL 5 HOUR, 1
FROM nai_alert_rule r WHERE r.name = 'demo-错误率超 5%'
  AND NOT EXISTS (SELECT 1 FROM nai_alert_event WHERE description LIKE '%demo%' AND severity = 'P1');

INSERT INTO nai_alert_event (rule_id, rule_name, severity, status, title, description, metric_value, threshold_value, fired_dt, notified_flag)
SELECT r.id, r.name, 'P2', 'firing', '[P2] demo-P99 延迟超 30s', 'p99_latency_ms = 45200 >= 30000（窗口 10 分钟·持续 3 分钟）（demo）', 45200, 30000, NOW() - INTERVAL 20 MINUTE, 1
FROM nai_alert_rule r WHERE r.name = 'demo-P99 延迟超 30s'
  AND NOT EXISTS (SELECT 1 FROM nai_alert_event WHERE description LIKE '%demo%' AND severity = 'P2');

-- ===== 演示正文（请求详情抽屉 JSON 树/对话视图目检用·幂等） =====
INSERT INTO nai_request_body (request_id, req_body, resp_body)
SELECT 'req-demo-t000276',
  '{"model":"gemini-1.5-pro","messages":[{"role":"system","content":"You are a concise assistant for the demo console."},{"role":"user","content":"用一句话介绍 NexusAI 网关。"}],"temperature":0.7,"max_tokens":256}',
  '{"id":"chatcmpl-demo276","object":"chat.completion","model":"gemini-1.5-pro","choices":[{"index":0,"message":{"role":"assistant","content":"NexusAI 是一个把任意协议的大模型请求统一转发、计量并强闸门扣费的独立中转网关。"},"finish_reason":"stop"}],"usage":{"prompt_tokens":54,"completion_tokens":38,"total_tokens":92}}'
WHERE NOT EXISTS (SELECT 1 FROM nai_request_body WHERE request_id = 'req-demo-t000276');

INSERT INTO nai_request_body (request_id, req_body, resp_body)
SELECT 'req-demo-t000273',
  '{"model":"gpt-4o-mini","messages":[{"role":"user","content":[{"type":"text","text":"分析这张图的配色"},{"type":"image_url","image_url":{"url":"data:image/png;base64,DEMO"}}]}],"tools":[{"type":"function","function":{"name":"get_palette","description":"提取主色板"}}]}',
  '{"id":"chatcmpl-demo273","object":"chat.completion","model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_demo","type":"function","function":{"name":"get_palette","arguments":"{\"count\":5}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":210,"completion_tokens":12,"total_tokens":222}}'
WHERE NOT EXISTS (SELECT 1 FROM nai_request_body WHERE request_id = 'req-demo-t000273');

-- ===================== 结算补偿 outbox（settle 失败重放队列·状态多样） =====================
DELETE FROM nai_settlement_outbox WHERE request_id LIKE 'req-demo-%';
INSERT INTO nai_settlement_outbox (id, request_id, token_id, project_code, refund_micro_usd, status, attempts, last_error, next_retry_at, create_dt) VALUES
(973000000000000001, 'req-demo-t000017', 900001, 'my-saas',  120000, 0, 1,  'Redis 连接抖动: read timeout',          NOW() - INTERVAL 2 MINUTE,  NOW() - INTERVAL 30 MINUTE),
(973000000000000002, 'req-demo-t000033', 900002, 'mobile-app',   -8000, 3, 2,  '处理中(已被某实例租约认领)',            NOW() + INTERVAL 1 MINUTE,  NOW() - INTERVAL 20 MINUTE),
(973000000000000003, 'req-demo-t000051', 900001, 'my-saas',   45000, 1, 1,  NULL,                                    NOW() - INTERVAL 1 HOUR,    NOW() - INTERVAL 65 MINUTE),
(973000000000000004, 'req-demo-t000077', 900003, 'mobile-app',  300000, 2, 12, '重试 12 次仍失败·已放弃·需人工对账',     NOW() - INTERVAL 1 DAY,     NOW() - INTERVAL 2 DAY),
(973000000000000005, 'req-demo-t000099', 900001, 'my-saas',    6000, 0, 3,  '额度服务 5xx·待重试',                   NOW() - INTERVAL 5 MINUTE,  NOW() - INTERVAL 90 MINUTE),
(973000000000000006, 'req-demo-t000120', 900002, 'mobile-app',   -2500, 1, 1,  NULL,                                    NOW() - INTERVAL 3 HOUR,    NOW() - INTERVAL 190 MINUTE);

-- 幂等防重走 Redis·不落库（nai_idempotent 死表已删·无演示数据）

-- =====================================================================
-- 全表覆盖校验（24 张 nai_ 表·确认无遗落·数字应均 > 0）
-- =====================================================================
SELECT 'nai_channel'            t, COUNT(*) demo_cnt FROM nai_channel
UNION ALL SELECT 'nai_channel_key',       COUNT(*) FROM nai_channel_key
UNION ALL SELECT 'nai_model',             COUNT(*) FROM nai_model
UNION ALL SELECT 'nai_ability',           COUNT(*) FROM nai_ability
UNION ALL SELECT 'nai_project',           COUNT(*) FROM nai_project
UNION ALL SELECT 'nai_workspace',         COUNT(*) FROM nai_workspace
UNION ALL SELECT 'nai_token(demo)',       COUNT(*) FROM nai_token            WHERE id BETWEEN 900001 AND 900099
UNION ALL SELECT 'nai_request_log(demo)', COUNT(*) FROM nai_request_log      WHERE request_id LIKE 'req-demo-%'
UNION ALL SELECT 'nai_request_body(demo)',COUNT(*) FROM nai_request_body     WHERE request_id LIKE 'req-demo-%'
UNION ALL SELECT 'nai_usage_log(demo)',   COUNT(*) FROM nai_usage_log        WHERE request_id LIKE 'req-demo-%'
UNION ALL SELECT 'nai_settlement_outbox', COUNT(*) FROM nai_settlement_outbox WHERE request_id LIKE 'req-demo-%'
UNION ALL SELECT 'nai_task(demo)',        COUNT(*) FROM nai_task             WHERE task_id LIKE 'task-demo-%'
UNION ALL SELECT 'nai_routing_group',     COUNT(*) FROM nai_routing_group
UNION ALL SELECT 'nai_failover_event',    COUNT(*) FROM nai_failover_event   WHERE reason LIKE 'demo:%'
UNION ALL SELECT 'nai_proxy_node(demo)',  COUNT(*) FROM nai_proxy_node       WHERE id BETWEEN 910001 AND 910006
UNION ALL SELECT 'nai_proxy_node_event',  COUNT(*) FROM nai_proxy_node_event WHERE node_id BETWEEN 910001 AND 910006
UNION ALL SELECT 'nai_proxy_node_metric', COUNT(*) FROM nai_proxy_node_metric WHERE node_id BETWEEN 910001 AND 910006
UNION ALL SELECT 'nai_node(demo)',        COUNT(*) FROM nai_node             WHERE node_id IN ('10.0.8.21:8600','10.0.8.22:8600')
UNION ALL SELECT 'nai_audit_log',         COUNT(*) FROM nai_audit_log
UNION ALL SELECT 'nai_alert_rule(demo)',  COUNT(*) FROM nai_alert_rule       WHERE name LIKE 'demo-%'
UNION ALL SELECT 'nai_alert_event',       COUNT(*) FROM nai_alert_event
UNION ALL SELECT 'nai_system_config',     COUNT(*) FROM nai_system_config
UNION ALL SELECT 'nai_admin_user(demo)',  COUNT(*) FROM nai_admin_user        WHERE id = 960001
UNION ALL SELECT 'nai_admin_user_project', COUNT(*) FROM nai_admin_user_project WHERE admin_user_id = 960001;
