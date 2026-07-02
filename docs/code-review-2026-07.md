# NexusAI 全仓代码评审报告

> 评审日期：2026-07-02　·　评审范围：nexusai 全仓（8 大模块，约 3.8 万行 Java + 4.3 万行前端）
> 方法：8 个专职代理分片深审 + 对每条 P0/P1 回原始代码逐一核实

## 总体判断

这是一套工程素养明显高于同类中转网关的代码——反应式调度边界、settle-exactly-once、Lua 原子扣费、熔断状态机、ClientIp 可信代理解析、加密与脱敏纪律大多做得扎实且有自觉注释。但作为**尚未上线的计费网关**，当前存在 4 类必须在上线前解决的资损/接管级缺陷，且大量"计费口径"在非 JSON-chat 模态下失真。

## 模块质量记分卡

| 模块 | 规模 | 质量 | 最高severity | 一句话 |
|------|------|------|:---:|------|
| nexusai-relay 数据面 | 6.6k | 高 | P0 | 主链扎实，旁路（任务/multipart）绕闸门、body 无上限 |
| nexusai-billing 计费 | 1.0k | 高 | P0 | 金额纪律干净，freeze/settle 生命周期两处致命竞态 |
| nexusai-router/provider/protocol | 5.2k | 高 | P0 | 路由/熔断成熟，Gemini 计量漏计、Bedrock 出站体非法 |
| nexusai-control 控制面 | 13k | 中上 | P0 | scope 强制完备，默认口令+令牌明文+无爆破防护 |
| nexusai-persistence 持久层 | 5.0k | 高 | P0 | Entity↔DDL 一致，额度复活、PG DDL 开箱即坏 |
| nexusai-observe/task/common | 1.9k | 高 | P1 | ClientIp 正确，trace 断链、leader 锁无续期 |
| nexusai-admin-ui 前端 | 42k | 中上 | P1 | 敏感数据处理规范，401 登出死循环、分区文件缺失 |
| 工程/CI/配置 | — | 中 | P0 | 依赖图无环，actuator 裸奔、CI 免测、flatten 缺失 |

---

## 🔴 P0 — 上线前必须修复（资损 / 接管 / 服务不可用）

### P0-1　额度复活：管理端停用/编辑触发 invalidate，按陈旧 DB 基线重播种，已花掉的额度整体复活
`billing/quota/QuotaLedger.java:36-38,79-82,402-409` + `control/.../WorkspaceAdminController.java:103,120` + `control/.../ProjectAdminController.java:88,129`

额度真理源在 Redis，消耗只扣 Redis、DB 基线从不回写。工作区/项目在停用、编辑、unlimited 切换时调 `invalidateWorkspace/Project` 删掉 Redis 余额 key；下一次 `freeze` 见 key 缺失，用 DB 里从未被扣减的 `quotaUsd` 重播种。此外 `SETTLE_LUA` 的 `incrby` 对缺失 key 按 0 起建，在途请求 settle 会把 key 重建为仅退款的小额值，同样绕过状态校验复活停用项目。

**影响**：钱包充 $100 花 $90 后，控制台"暂停→恢复"即回到 $100，消耗蒸发；可反复触发，直接资损。
**修复**：invalidate 前用 GETDEL 把 Redis 余额原子回写 DB 基线；或不删 key、在 freeze Lua 内加 status 校验。（注：ledger 已内置 `adjustProject/adjustWorkspace` 原子充退，应改走该路径。）

### P0-2　控制台默认口令实质无法通过文档方式修改，且无生产 fail-fast
`server-starter/.../application.yml:78-79` + `control/.../AdminUserSeeder.java:26-30`

yml 与发布说明反复强调"生产必须用 `NEXUSAI_ADMIN_PASSWORD` 覆盖默认密码"，但全仓无任何代码消费 `nexusai.admin.username/password`（登录只校验 DB 内口令），播种器读的是另一个属性 `nexusai.admin.seed-password`。运维照文档设环境变量完全无效，超管仍是 `admin/admin123`；且无生产 profile fail-fast。控制台掌管上游渠道密钥/额度/路由，等于公网可直接接管。

**修复**：seeder 改读 `nexusai.admin.*` 与 yml/env 对齐；非 dev profile 下默认口令启动即抛错。

### P0-3　未鉴权即无上限聚合请求体 → 内存耗尽 DoS
`relay/RelayOrchestrator.java:115`（同型 `controller/AsyncTaskController.java:42`、`task/TaskHttp.java:30`）

`DataBufferUtils.join(getRequest().getBody())` 无 `maxByteCount` 参数把整个请求体聚合进内存，而鉴权在其后的 `gate()` 里执行。`spring.codec.max-in-memory-size` 只约束 codec 反序列化，对裸 `getBody()` join 无效。无有效 sk- 的攻击者灌数 GB body 即可打爆堆内存。

**修复**：改 `DataBufferUtils.join(body, maxBytes)`（chat 10–20MB，multipart 单放宽），超限回 413。

### P0-4　Gemini thinking token 三条路径全漏计，thinking 模型持续零计费（资损）
`relay/usage/UsageExtractor.java:241` + `protocol/.../OpenAiSseConverter.java:176` + `protocol/.../GeminiChatTransformer.java:178`

三处只读 `candidatesTokenCount`，从不读 `thoughtsTokenCount`；计费在 `SettlementService.java:123` 显式重算 `prompt+completion` 丢弃含思考的 total。Gemini 2.5 系默认开思考，单请求数千思考 token（Google 按输出价收费）对客户零计费。

**修复**：三处统一把 `thoughtsTokenCount` 并入 completion，补测试钉死口径。

---

## 🟠 P1 — 上线前应修复（功能缺陷 / 越权 / 资损面 / 泄漏）

### 计费与任务链路
- `relay/filter/QuotaGateFilter.java:60` + `settle/SettlementService.java:84`｜multipart 二进制体按 `bytes/4` 估 prompt token，且转写响应无 usage 时 settle 按 `estimated`=frozen 实扣——25MB 音频可被结算成数十美元。修复：multipart 按 perRequest/perImage 计价，frozen 兜底仅限 chat。
- `relay/controller/MidjourneyController.java:86`（及 suno/kling/v1/tasks）｜任务端点只做鉴权+freeze，整条闸门链（限流/IP 白名单/模型权限/项目模态/幂等/并发）全绕过。修复：抽公共 admission 组件补齐。
- `billing/quota/QuotaLedger.java:59`｜freeze 无持久踪迹/TTL/对账任务，进程崩溃落在 freeze 与 settle 之间时冻结额度永久滞留。修复：freeze 写带 TTL 的冻结记录 + 后台超时对账退款。
- `control/.../system/job/CleanupJob.java:128`｜删任务无 status 过滤，非终态任务被物理删除→额度永久滞留且对账凭据销毁。修复：仅删 `status IN ('SUCCESS','FAILED')`。
- `relay/usage/UsageReporter.java:74` + `billing/event/UsageEventPublisher.java`｜`nai_usage_log.delivered` 是死列，javadoc 承诺的"outbox 兜底重投"不存在，表无保留策略无查询索引。修复：实现重投或删列删注释，补索引。

### 协议 / 上游适配（上线即坏）
- `provider/bedrock/BedrockProvider.java:66`｜出站体含 `model`/`stream`、缺 `anthropic_version`，Bedrock InvokeModel 必然 400；SigV4 对含 `:` 的 modelId 编码存疑。
- `relay/upstream/OutboundRequestAssembler.java:52` + `protocol/.../GeminiChatTransformer.java:45`｜Gemini 原生透传叠加 model_mapping 时向体内注入非法顶层 `model`；Gemini 入站转 OpenAI 上游时 model 取自体内→出站 model=null。
- `relay/filter/ModelAliasFilter.java:39`｜别名归一盲按 JSON 改写 body：multipart+别名直接 500。修复：multipart/Gemini 跳过体改写。
- `protocol/passthrough/RequestPeeker.java:76`｜multipart `model` 按裸字节首匹配，可被上传文件内容伪造→按低价计费执行高价。修复：按 boundary 正规切分。

### 控制面安全
- `ProjectTokenCache.java:57` + `ProjectAdminController.java:100` + `script/sql/nexusai.sql:43`｜M2M 项目令牌（platform 档=全 /admin 权限）明文落库且明文回显、非恒时比对。修复：AES-GCM 落库或哈希+尾4位。
- `control/.../UsageQueryController.java:156`｜`requestCurl` 对多项目人类账号未做作用域校验，可跨项目读请求正文明文。修复：与 requestBody 同走 requirePlatform。
- `control/api/security/AdminAuthController.java:56`｜登录无失败锁定/限速，默认账号名公开可爆破。修复：登录失败锁定或 IP/账号滑窗限流。
- `control/.../UsageQueryController.java:60`｜请求日志分页无 size 上限，`pageSize=1亿` 拉爆内存。修复：走 PageQuery.of 钳制。
- `api/contract/KeySyncController.java:87`｜`byok_credential` 写入零校验，明文可落 nai_token。修复：校验密文可解密或收明文即刻加密。

### 基础设施 / 前端 / 工程
- `application.yml:153` + `SaTokenConfigure.java:37`｜`/actuator/metrics`、`/info` 在 8600 业务口匿名可读；`prometheus` 缺 registry 依赖是死配置。修复：actuator 独立端口或纳入鉴权。
- `application.yml:60-63`｜请求体默认 `store: rdb`+`collect: true`，出厂即全量落库用户提示词，注释却写"默认 hash"。修复：默认改 hash 或显著声明。
- `observe/.../RequestLogConsumer.java:144`｜`trace_id` 落库恒 null，控制台按 traceId 排障功能死掉。修复：从 TRACE_ATTR 桥接进日志。
- `logback-boot.xml:63-88`｜三个 AsyncAppender 未设 `neverBlock`，队列满阻塞 Netty；且无 totalSizeCap。修复：`neverBlock=true` + SizeAndTimeBasedRollingPolicy。
- `relay/upstream/UpstreamExchange.java:314`｜流式镜像 Sink `onBackpressureBuffer()` 无界，慢客户端堆爆内存。修复：设上限，溢出断流。
- `task/TaskPoller.java:124`｜leader 锁固定租约无看门狗，长批处理超租约→双实例并发轮询。修复：改可续期锁（lease=-1）。
- `pom.xml:215`｜`${revision}` 未启用 flatten-maven-plugin，client-starter 交付物 pom 带字面量 `${revision}` 无法解析。修复：启用 flatten。
- `.github/workflows/build.yml:4,59`｜CI 仅 tag 触发且 `-DskipTests`，测试永不跑。修复：加 push/PR 的 mvn verify。
- `script/sql/postgres/nexusai_pg.sql:10`｜`COMMENT ON TABLE` 在 `CREATE TABLE` 之前，全新 PG 库执行即报错。修复：注释移到建表后。
- 前端 `service/request/index.ts:115`｜401→resetStore→登出→再 401 无限环。修复：401 分支跳过 logout 自身失败。
- 前端 `views/access-keys/components/install-cli-dialog.vue:25`｜prod 下 CLI 命令用相对路径 `/nexusai` 失效。修复：用 window.location.origin。
- 前端仓库根｜父仓声称的 `nexusai-admin-ui/CLAUDE.md` 与 `.framework-sync.json` 均不存在，框架 hook `table.ts` 被整体重写含 console.log 未登记接缝。修复：补建治理文件。

---

## 🟡 P2 — 质量 / 死代码 / 一致性（择要）

计费/限流：`RateLimiterService` 注释称"Redis 服务端时钟防漂移"实为各 JVM 本地时钟；RRateLimiter 键无 TTL 泄漏；`INLINE_SETTLE_DEDUP_TTL=600s` < outbox 重试窗（窄窗双结算）；`UsageMeter.microUsdFor` 不夹负值 + 费率无非负校验（负费率印钱）；prompt 估算 bytes/4 对中文低估 2–4 倍。
协议/路由：SSE 行缓冲无上限；finish_reason 映射粗糙；429 Retry-After 无上限即全集群封一天；半开无单飞探测；一致性哈希环每请求重建；ProviderFactory 未知 key 静默回退 openai。
持久层：`nai_channel/nai_channel_key` 13 个熔断/健康列全死列；PG 下裸 @Update `update_dt` 不更新；充退 DB 回写"读改写整行"；`NaiRoutingGroup.version` 假乐观锁。
控制面：审计 actor 不区分人类账号（都记 admin）；IP 黑名单仅精确匹配、白名单支持 CIDR（不对称）。
可观测/任务：ClickHouse 逐行 insert（part 爆炸）；日志消费无毒消息处理；`bindUpstream`/`updateProgress` 非 CAS 可复活终态任务且 bindUpstream 死代码。
前端：会话 token 存 localStorage；6 秒超时误写进 headers；响应包络兼容 shim 主选键与后端相反+死分支；三个零引用依赖（含 xlsx@0.18.5 CVE）；refreshToken 死代码。
工程/文档：README demo key 不存在、端点写错、表数 23 实为 24、漏 Bedrock/Vertex；.editorconfig 160 列有 10+ 处超限；Dockerfile 以 root 运行无 HEALTHCHECK；nexusai-example/SmokeExample 空占位类。

---

## 值得肯定的部分

settle 的 AtomicBoolean CAS 门闩 + Lua SET NX 幂等结算 + outbox 补偿 + 断连 drain 保计费；三级额度单段 Lua 先全查后统扣；AES-256-GCM 每次随机 12B nonce 不重用、密钥仅环境注入且长度强校验、明文永不落库；sk- 仅存 SHA-256 且 hash-then-lookup 抗计时；JWT 拒 none/alg 混淆、恒时比对；ClientIp 多跳 XFF 右往左剥离 + CIDR + 伪造头防御逐行正确并有单测；控制面 scope 路径级强制 fail-closed（30 个 Controller 逐一核对）；模块依赖单向无环、零运行时 ruoyi 耦合；金额全程 BigDecimal/long micro-USD 无一处 float。

---

## 建议修复顺序

上线前必须闭环 4 个 P0（额度复活 → 默认口令 → body DoS → Gemini 计量）。紧接着两组 P1：计费口径失真簇（multipart 超收、任务口绕闸门、freeze 无对账、CleanupJob 删非终态）决定"计费网关"定位是否成立；上游适配簇（Bedrock/Gemini 出站体、别名改写）决定这些渠道能否跑通——目前 Bedrock、配了映射的 Gemini 是上线即坏。P2 可在功能稳定后按模块批量清理。
