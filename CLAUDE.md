# nexusai-admin-ui 开发约定（前端二开·项目级）

> 本文件是 `nexusai/CLAUDE.md` 引用的前端二开规范全文，配套机读清单 `.framework-sync.json`（登记接缝与框架改动）。

## 定位
NexusAI 网关**控制台前端**。上游 = **soybean-admin**（学习样本 snail-ai-admin），本仓是其**二开**。
- 技术栈：Vue3 + TS + Vite + NaiveUI + UnoCSS + Pinia。
- 构建：`pnpm build` = `vite build --mode prod`（**不过 vue-tsc**·类型错误不阻断构建，但代码仍须类型自洽）。
- 部署：单 jar 内置前端时挂子路径 **`/nexusai`**；网关端口 **8600**。
- 差异优先走 **`.env`**，不 fork 框架逻辑。

## 二开三类分区纪律

### 🟦 框架原码 —— 只 import 不改
上游模板自带的代码。定制优先用 `.env` / props / slot 覆盖；**能不改就不改**。确需改动即降级为「override」，必须在 `.framework-sync.json` 的 `overrides` 登记。

### 🟨 必改接缝（seams）—— 保留我方逻辑
与后端契约强绑定的集成点，二开必然要改，改动集中在少数目录，须保持稳定、可辨识：
- `src/service/request/`（index.ts / shared.ts / type.ts）：注入 `NexusAI-Admin-Token` 头、解析后端 `code/msg/data` 包络（`code=200` 成功）、HTTP 401 静默重置、超时与登出码处理。
- `src/store/modules/auth/`（index.ts / shared.ts）：账密登录 → Sa-Token 会话；`resetStore` 防重入 + 仅在本地有 token 时吊销服务端会话；nexusai 账号即超管，无独立用户体系。
- `src/store/modules/theme/`（index.ts / shared.ts）：品牌色 / 默认主题 / 覆盖标记。
- `src/plugins/loading.ts`：首屏启动动画品牌化。

### 🟩 新增 —— 放视图本地 + 服务层
本项目新代码**不散落进框架目录**，按归属就近放：
- 视图：`src/views/<视图>/` 下的 `{components, composables, modules}`。
- 接口：`src/service/api/*.ts`（每领域一文件：channel / project / usage / ops …；`index.ts` 汇出）。
- 本地持久化：走 `src/utils/storage.ts` 的 `localStg`（自动带 `VITE_STORAGE_PREFIX=NEXUSAI_` 前缀），**禁裸 `localStorage`**；typed key 登记在 `src/typings/storage.d.ts`。vueuse `useStorage` 等第三方存储 key 亦须带 `NEXUSAI_` 前缀。

## 已登记的框架改动（overrides·权威清单见 .framework-sync.json）
| 文件 | 原因 |
| --- | --- |
| `src/hooks/common/table.ts` | 整体重写 `useTable`：`apiFn/apiParams` 风格 + 后端 `{records,total}` 分页口径 + naive-ui 列助手 |
| `src/hooks/common/router.ts` | 路由跳转封装本地化（登录跳转 / 回跳目标） |
| `src/layouts/modules/global-footer/index.vue` | 页脚品牌信息 |
| `src/layouts/modules/global-header/components/user-avatar.vue` | 顶栏账号菜单适配（账号即超管·登出走 `resetStore`） |
| `src/components/common/system-logo.vue` | 系统 Logo 品牌化 |
| `src/components/advanced/table-header-operation.vue` | 表头操作区本地化 |

> 清单人工维护、非自动生成，可能不完全。**改动任何框架原码后必须在此表与 `.framework-sync.json` 同步登记。**

## 约束速记
- 未上线·不留向后兼容：发现死代码 / 兼容旧后端的 shim 直接删。
- 每个 `.vue` 首行 `<!-- 描述 -->`、`.ts` 首行 `// 描述` 或 `/** 描述 */`（已有则保持）。
- 上游升级：以 `.framework-sync.json` 的 seams/overrides 逐条 rebase，**勿整包覆盖**。
