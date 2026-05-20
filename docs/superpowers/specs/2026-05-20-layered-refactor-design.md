# 前后端分层重构设计 — 2026-05-20

## 1. 背景与目标

当前项目的实际网络流向已经是「前端 → 后端 → 大模型」(前端 Vue 代码不再直连任何 LLM 厂商),但代码层面存在三类规范问题:

1. **后端 controller 承担了大量业务逻辑**。`ScenarioController.runPrediction`(~200 行)、`OntologyModelController.extract`(~120 行)直接编排算法/IO/SSE 推送;`ChatController` 也内联了 SseEmitter 的超时与异常处理。
2. **前端 6 个组件直接 `fetch('/api/...')`**,缺少统一的 API 抽象层;`fetch` 散落 20+ 处,错误处理重复。
3. **遗留资产 `frontend/server.ts` + `frontend/llm-config.json` + `frontend/.env.example`** 是早期 Express 直连阿里 dashscope 的旧通路,虽然运行时已不被调用,但语义上违反「前端不连 LLM」的原则;同时 `package.json` 中残留大量未使用的依赖(`@google/genai` / `react` / `lucide-react` / `motion` / `express` / `dotenv` / `tailwindcss` 等)。

**目标:** 通过三阶段递进重构,把项目对齐到以下规范:

- **后端:** controller 只做「接口三件事」 — 接收参数、调用 service、包装返回。所有业务逻辑落在 service;可复用的纯算法/IO 助手落在 `support/` 包。
- **前端:** 网络调用统一收敛到 `src/api/*.ts`;Vue 组件零 `fetch`;App.vue / ChatPanel.vue / SettingsView.vue 三个超大组件按职责拆小。
- **遗留:** 彻底删除 `frontend/server.ts` 及配套文件,清理 `package.json` 未使用依赖。

**非目标:**

- 不新增单元测试套件(后续如有需要,用 java-test-generator 单独发起)。
- 不替换框架(继续 Spring Boot + Vue 3 + Vite)。
- 不引入新的第三方依赖(不上 axios,沿用原生 fetch + 60 行薄封装)。
- 不做与上述三目标无关的重构(例如更换样式方案、改造图布局算法、调整数据持久化目录)。

## 2. 现状摘要

### 后端关键文件行数

| 文件 | 行数 | 现状 |
|---|---|---|
| `service/LlmService.java` | 1354 | service,LLM 适配与调用 |
| `controller/ScenarioController.java` | 387 | **大半是 runPrediction 编排** |
| `controller/OntologyModelController.java` | 394 | **大半是 extract 文件解析** |
| `controller/ModelController.java` | 127 | controller 内做了非空校验(轻量违规) |
| `controller/PrefsController.java` | 88 | **包含 prefs 文件读写默认值合并** |
| `controller/ChatController.java` | 71 | **SseEmitter 装配内联在 controller** |
| `controller/ConfigController.java` | 41 | 已基本规范 |

### 前端关键文件行数

| 文件 | 行数 | 现状 |
|---|---|---|
| `components/SettingsView.vue` | 1318 | **模型管理 + 偏好 + 关于,三块混在一起** |
| `components/ChatPanel.vue` | 934 | **会话/消息/输入/附件/模型选择/SSE 全部内联** |
| `App.vue` | 921 | **视图路由 + 模型 CRUD + 推演编排 + 分隔条全部内联** |
| `components/ImportDialog.vue` | 629 | 含 `fetch('/api/ontology-models/extract')` |
| `composables/useSSE.ts` | 117 | 已是 composable,内部裸用 fetch,可复用度高 |
| `server.ts`(根目录) | 157 | **遗留:Express 直连 dashscope,无人调用** |

### 前端 `fetch('/api/...')` 调用点(grep 结果)

- `App.vue`:7 处(ontology-models / scenarios)
- `ChatPanel.vue`:2 处(config / prefs)
- `SettingsView.vue`:9 处(models / config / prefs)
- `ImportDialog.vue`:1 处(ontology-models/extract)
- `composables/useSSE.ts`:1 处(底层 fetch,SSE 通用入口)

### 关键事实

- 后端端口 `8000`(`application.yml`);Vite 已配置 `/api → http://localhost:8000` 代理。
- `package.json` 中 `dev` 脚本是 `tsx server.ts`(走旧 Express),需要改为 `vite`。
- 通过 grep 验证:`@google/genai` / `react` / `react-dom` / `lucide-react` / `motion` / `@vitejs/plugin-react` / `@tailwindcss/vite` / `tailwindcss` / `autoprefixer` / `express` / `dotenv` 这些依赖在 `frontend/src` 中均未被 `import`,属于历史残留。

## 3. 总体架构与原则

### 3.1 后端分层

```
controller   只做接口契约:接收 DTO → 调 service → 返回 ResponseEntity / SseEmitter
   │
   ▼
service      业务编排:校验、事务边界、跨实体协作、SSE 流程编排
   │
   ▼
support      纯算法 / IO 助手:无状态、无 Spring 依赖、可单测
```

**规则:**

- controller **禁止**:try/catch 业务分支、调用文件系统、做参数业务校验(只允许做 `@Valid` 注解层面的格式校验)、装配 SseEmitter 内部逻辑。
- service **可以**:抛 `IllegalArgumentException`(→ controller 转 400)、`IllegalStateException`(→ controller 转 400)、`IOException`(→ controller 转 500)。
- support **必须**:无 `@Component` / `@Autowired`,纯静态方法或可被 service 通过 `new` 构造的工具类。

### 3.2 前端分层

```
components/  只做 UI 渲染 + 事件转发,禁止出现 fetch / EventSource / localStorage 业务读写
   │
   ▼
composables/ 响应式状态 + 业务编排,可调用 api/*
   │
   ▼
api/         网络调用统一入口,内部用 http.ts 薄封装 fetch
```

**规则:**

- 组件 **禁止**:`fetch(`、`new EventSource(`、直接读写 `localStorage` 的业务键(`useConversations` 等专用 composable 除外)。
- composable **单一职责**:每个 composable 返回 `{ state, actions }`,不直接 import 另一个 composable(必要时父级把另一个 composable 的方法作为参数传入)。
- api 层 **不返回 Response 对象**:统一在 `http.ts` 里 `.json()` 完毕后返回业务数据;非 2xx 抛 `ApiError`。

## 4. 阶段 1:后端 controller 业务下沉

### 4.1 新增包结构

```
backend/src/main/java/com/tuiyan/backend/
├── controller/                       (保持原文件,内容大幅瘦身)
├── service/
│   ├── LlmService.java                       (沿用)
│   ├── OntologyModelService.java             (沿用)
│   ├── ScenarioService.java                  (沿用)
│   ├── PredictionOrchestrator.java   【新】runPrediction 整体下沉
│   ├── DocumentExtractionService.java【新】extract 整体下沉
│   └── PrefsService.java             【新】prefs 文件读写 + 默认值
├── support/                          【新包】
│   ├── PdfTextExtractor.java                 (PDF 文本抽取 + 分页渲染)
│   ├── FileSniffer.java                      (PDF/图像 magic-byte 嗅探 + 文件名清洗)
│   ├── IdSaltRewriter.java                   (extract 的 idMap 深度替换 + predict 的 applyIdSalt)
│   ├── PredictionMath.java                   (clamp01 / round3 / 概率累乘 / computeOrigin)
│   └── SsePushUtils.java                     (safeSend / 装配超时 & 异常的 emitter 工厂)
```

### 4.2 各 controller 的目标形态

**`ScenarioController.predict`:**
```java
@PostMapping
public SseEmitter predict(@RequestBody PredictRequest req) {
    SseEmitter emitter = SsePushUtils.newEmitter(180_000L);
    predictionExecutor.execute(() -> predictionOrchestrator.run(req, emitter));
    return emitter;
}
```
其余 list/getOne/delete/migrate 已经合规,仅需统一去掉 `try/catch IOException` 重复样板 — 改为 controller 不 catch,在 service 边界处理。

**`OntologyModelController.extract`:**
```java
@PostMapping(value = "/extract", consumes = "multipart/form-data")
public ResponseEntity<?> extract(@RequestParam("files") List<MultipartFile> files,
                                 @RequestParam(value = "modelOverride", required = false) String modelOverride,
                                 @RequestParam(value = "configId", required = false) String configId) {
    return ResponseEntity.ok(documentExtractionService.extract(files, modelOverride, configId));
}
```
所有文件嗅探、PDF 文本 + 渲染、idMap salt 重写下沉到 `DocumentExtractionService`;PDF/图像处理细节进 `support/PdfTextExtractor` 与 `support/FileSniffer`;salt 重写进 `support/IdSaltRewriter`。

**`ChatController.chat`:**
```java
@PostMapping
public Object chat(@RequestBody ChatRequest request,
                   @RequestHeader(value = "Accept", defaultValue = "application/json") String accept) {
    if (accept.contains("text/event-stream")) {
        SseEmitter emitter = SsePushUtils.newEmitter(180_000L);
        llmService.chatStreaming(request, emitter);
        return emitter;
    }
    return ResponseEntity.ok(llmService.chat(request));
}
```
异常处理改成全局 `@ControllerAdvice`(或保持 service 抛、controller 不 catch,统一交给 Spring 默认错误处理)— 采用方案:**新增 `GlobalExceptionHandler` 用 `@RestControllerAdvice`**,把 `IllegalArgumentException`/`IllegalStateException` 映射到 400,`IOException` 映射到 500,统一返回 `{error: msg}`,消除每个 controller 的重复 try/catch。

**`ConfigController` / `PrefsController` / `ModelController`:**

- `PrefsController`:`readOrDefault` / `defaults` / 删除 scenarios 全部迁入新 `PrefsService`。
- `ModelController`:入参非空校验下沉到 `LlmService.createModelConfig` / `updateModelConfig` 内部前置(抛 `IllegalArgumentException`),controller 不再做业务校验。
- `ConfigController`:已合规,仅去 try/catch。

### 4.3 阶段 1 验证标准

- `mvn -f backend/pom.xml package` 成功;
- 启动后端,前端不动,跑通三条主链路:聊天问答(/api/chat)、推演(SSE 分步事件正常)、文件导入抽取(/api/ontology-models/extract)。
- 每个 controller 方法体 ≤ 10 行(SSE controller 方法允许到 15 行)。
- `grep -E "try \\{" backend/src/main/java/com/tuiyan/backend/controller/*.java | wc -l` 显著下降。

## 5. 阶段 2:前端 API 层 + 删除遗留

### 5.1 新增 API 层

```
frontend/src/api/
├── http.ts          统一封装:request<T>() / sse() / ApiError
├── chat.ts          chat(...) / chatStream(...)
├── models.ts        listModels / createModel / updateModel / deleteModel / toggleModel
├── ontology.ts      listOntologies / getOntology / saveOntology / deleteOntology / extractFromFiles
├── scenarios.ts     listScenarios / getScenario / deleteScenario / migrateScenarios / predictStream
├── config.ts        getConfig / saveConfig
└── prefs.ts         getPrefs / savePrefs / clearAllScenarios
```

### 5.2 `http.ts` 设计要点

- 导出 `class ApiError extends Error { status: number; body: any }`。
- 导出 `async function request<T>(path: string, init?: RequestInit): Promise<T>`:
  - 默认 header `Accept: application/json`;
  - 非 2xx → 读 `body.error` 或文本 → 抛 `ApiError`;
  - 2xx 且 `Content-Length: 0` → 返回 `undefined as T`;否则 `.json()`。
- 导出 `function sse(path, init, handlers)`:
  - 默认 header `Accept: text/event-stream`;
  - 用 `fetch + body.getReader()` 解析 SSE 行协议(`event:` / `data:`);
  - 返回 `{ abort(): void }`;
  - `composables/useSSE.ts` 内部改用此函数,保留响应式封装对外接口。
- 不引入 axios:当前调用 ~20 处,薄封装 + 显式 ApiError 已能满足「统一错误处理」的需求,且省一个三方依赖。

### 5.3 调用点改造

| 文件 | 改造 |
|---|---|
| `App.vue` | 7 处 fetch → `import { listOntologies, saveOntology, ... } from '@/api/ontology'` 与 `@/api/scenarios` |
| `ChatPanel.vue` | 2 处 fetch → `getConfig()` / `getPrefs()`;`useSSE` 调用保持 |
| `SettingsView.vue` | 9 处 fetch → `@/api/models` + `@/api/config` + `@/api/prefs` |
| `ImportDialog.vue` | 1 处 fetch → `extractFromFiles(files, ...)` |
| `useSSE.ts` | 底层 fetch → `sse()` from `@/api/http` |

**验证:** `grep -rE "fetch\\('/api|new EventSource\\(" frontend/src/components frontend/src/App.vue` 必须返回空。

### 5.4 删除遗留(单独 commit)

1. 删 `frontend/server.ts`。
2. 删 `frontend/llm-config.json`、`frontend/.env.example`。
3. 修改 `frontend/package.json`:
   - `scripts.dev`: `tsx server.ts` → `vite`。
   - 删除 `dependencies` 中未使用的:`@google/genai`、`@tailwindcss/vite`、`@vitejs/plugin-react`、`dotenv`、`express`、`lucide-react`、`motion`、`react`、`react-dom`。
   - 删除 `devDependencies` 中未使用的:`@types/express`、`autoprefixer`、`tailwindcss`、`tsx`。
   - 调整 `name` 字段(目前是 `react-example`,误导)。
4. 重新生成 `package-lock.json`(`npm install`)。
5. 修改 `start.sh`:`npm run dev` 保持不变(脚本本身没问题,但启动的实际内容从 Express 变成 Vite)。
6. `vite.config.ts` 的 `/api` 代理已正确指向 `:8000`,无需改动。

### 5.5 阶段 2 验证标准

- `npm run build` 通过、无 type 错误。
- `grep -rE "fetch\\(|EventSource|api[-_]?key|openai|anthropic|dashscope|gemini" frontend/src frontend/server.ts frontend/llm-config.json frontend/.env.example 2>/dev/null` 在源码侧无任何 LLM/直连痕迹(以上 server.ts/llm-config.json/.env.example 文件应已不存在)。
- 重启服务,聊天 / 推演 / 设置页 CRUD / 文件导入四条主路径正常。

## 6. 阶段 3:三个超大组件拆分

### 6.1 `App.vue`(921 → 目标 < 300)

**抽出的 composable:**

| 名称 | 职责 |
|---|---|
| `useOntologyModel.ts` | 当前模型加载、防抖持久化、`nodes`/`edges` 状态、`createOnBackend`、`deleteOntologyModel` |
| `useScenarios.ts` | `loadBranches` / `switchBranch` / `collectAncestorChain` / `migrateBranches` / `deleteBranch` |
| `usePrediction.ts` | `openPredictDialog` / `startPrediction` / `liveSteps` / `liveAbort` / `closeTimeline` |
| `useImportFlow.ts` | `onImportCommit`(merge / new-model 两种 mode) |
| `useDivider.ts` | `chatW` / `divDrag` / `startDivider`(纯交互) |

**抽出的子组件:**

| 名称 | 职责 |
|---|---|
| `components/AppShell.vue` | 顶栏 + 侧边栏 + 主区域骨架 |
| `components/views/WelcomeView.vue` | `view==='welcome'` 分支 |
| `components/views/GraphView.vue` | `view==='graph'` 画布 + 工具栏 |

(`list` 视图和 `settings` 视图沿用现有 Sidebar/SettingsView,不重复抽。)

**App.vue 最终结构:** 视图路由 switch + 组合 composables + 4 个子视图挂载,预期 200~280 行。

### 6.2 `ChatPanel.vue`(934 → 目标 < 350)

**抽出的 composable:**

| 名称 | 职责 |
|---|---|
| `useConversations.ts` | localStorage 持久化、`initConversation` / `newConversation` / `switchConversation` / `deleteConversation` / `autoTitle` |
| `useAttachments.ts` | `addFile` + `readAsText` + `readAsDataURL` + 体积/类型校验 |
| `useMention.ts` | `mentionOpen` / `mentionQuery` / `mentionIndex` / `checkMention` / `selectMention` |
| `useChatStream.ts` | `send()` + SSE 接收 + `abortChat` + `msgs` 状态(内部调 `api/chat.ts`) |

**抽出的子组件(`components/chat/` 子目录):**

| 名称 | 职责 |
|---|---|
| `ConversationPicker.vue` | 历史会话下拉 |
| `ChatMessageList.vue` | 消息渲染(role / 头像 / markdown / 代码块) |
| `ChatInput.vue` | textarea + @mention 弹窗 + 附件按钮 |
| `AttachmentChips.vue` | 已选附件 chip |
| `ModelPicker.vue` | 预置/自定义模型切换 |

**ChatPanel.vue 最终结构:** 5 个子组件挂载 + 4 个 composable 组合,预期 250~320 行。

### 6.3 `SettingsView.vue`(1318 → 目标 < 350)

**抽出的 composable:**

| 名称 | 职责 |
|---|---|
| `useModelConfigs.ts` | load/save/delete/toggle + `validateForm` + `detectProviderCode` + `applyPreset` + `toggleCapability` |
| `usePrefs.ts` | `loadPrefs` / `savePrefs` / `clearAllScenarios` |

**抽出的子组件(`components/settings/` 子目录):**

| 名称 | 职责 |
|---|---|
| `SettingsTabs.vue` | tab 导航 |
| `ModelListPanel.vue` | 模型卡片列表 + 启用/删除/编辑触发 |
| `ModelConfigForm.vue` | 新增/编辑模型的表单弹窗(含 provider preset + 能力勾选) |
| `PrefsPanel.vue` | 偏好表单 |
| `AboutPanel.vue` | 关于页(版本号 + 链接) |

**SettingsView.vue 最终结构:** tab 路由 + 4 个 Panel 挂载,预期 100~150 行。

### 6.4 拆分通用原则

- **单一目的的 composable**:每个 composable 只返回 `{ state(refs), actions(funcs) }`,不引用其他 composable;若需要联动,父级把另一个 composable 的方法作为参数传入。
- **显式边界的子组件**:`defineProps` / `defineEmits` 显式声明输入输出;子组件不直接 import `api/*`,数据/动作由父级通过 props/events 注入。
- **样式跟随组件走**:拆出去的子组件携带自己的 `<style scoped>`,不复制大块全局样式;`index.css` 仅保留确实全局的变量与基础样式。
- **每拆一个文件就编译验证一次**:`npm run build` 应能在拆分过程中始终通过,避免最后一次性塌方。

### 6.5 阶段 3 验证标准

- `wc -l frontend/src/App.vue frontend/src/components/ChatPanel.vue frontend/src/components/SettingsView.vue` 三者均 < 400 行。
- `npm run build` 通过,无 type 错误。
- 手工冒烟 5 条主路径:
  1. 欢迎页提交建图;
  2. 切换分支;
  3. 启动推演并看到流式步骤;
  4. 文件导入合并到 trunk;
  5. 设置页 CRUD 模型 + 修改 prefs。

## 7. 错误处理与跨阶段约定

### 7.1 后端异常 → HTTP 状态码映射

通过新增 `GlobalExceptionHandler`(`@RestControllerAdvice`)统一处理:

| 异常 | HTTP 状态 | 响应体 |
|---|---|---|
| `IllegalArgumentException` | 400 | `{error: msg}` |
| `IllegalStateException` | 400 | `{error: msg}` |
| `MissingServletRequestParameterException` 等 Spring 内置 | 400 | `{error: msg}` |
| `IOException` | 500 | `{error: msg}` |
| 其他 `Exception` | 500 | `{error: msg}`(并 log.warn 堆栈) |

Controller 内不再写 `try/catch`(SSE 路径除外:SSE 因为 emitter 已建立、不能用 HTTP 状态码,异常通过 `emitter.send(name="error", ...)` 报告)。

### 7.2 前端 `ApiError` 处理

`http.ts` 抛出的 `ApiError` 由调用方决定:

- 列表页加载失败 → toast + 占位空状态;
- 表单提交失败 → toast + 表单错误提示(若 `error` 含字段含义);
- SSE 失败 → `useSSE` 内部已经处理 `onerror`,转 toast。

不在 `http.ts` 中统一弹 toast(避免 import UI 依赖造成循环 + 调用方无法静默处理)。

### 7.3 不引入新测试套件

每个阶段验证以「编译通过 + 手工冒烟主路径」为准。如阶段 3 完成后用户希望补 JUnit,用 java-test-generator 单独发起,不在本设计范围内。

## 8. 执行节奏与回滚

每阶段独立分支、独立 PR;阶段间存在依赖,但前一阶段合并前不开下一阶段。

| 阶段 | 分支名 | 预计提交数 | 回滚策略 |
|---|---|---|---|
| 1 后端下沉 | `refactor/backend-layering` | 4~6 commits | 单 PR revert |
| 2 前端 API 层 + 清遗留 | `refactor/frontend-api-layer` | 3~4 commits(API 层 / 调用点改造 / 删遗留 / 清依赖,各一 commit) | 单 PR revert;遗留删除是最后一个 commit,便于单独 cherry-pick 或撤销 |
| 3 超大组件拆分 | `refactor/frontend-component-split` | 6~9 commits(每个被拆的超大组件一个 commit) | 单 PR revert;每个超大组件的拆分独立 commit,可单独回滚某个 |

## 9. 完成定义(DoD)

整体重构完成时:

- 后端所有 controller 方法体均 ≤ 15 行;
- 后端 controller 不含 `try/catch` 业务分支;
- 前端 `grep -rE "fetch\\('/api|new EventSource\\(" src/components src/App.vue` 为空;
- 前端 `server.ts` / `llm-config.json` / `.env.example` 不存在;
- 前端 `package.json` 仅保留实际被 import 的依赖;
- App.vue / ChatPanel.vue / SettingsView.vue 均 < 400 行;
- `mvn package` + `npm run build` 均通过;
- 5 条主路径手工冒烟通过。
