# 推演平台 (EIC-CC)

> **基于本体图谱的 AI 业务血缘建模系统** —— 把访谈录音、业务文档、数据库结构、内网系统探索、联网调研沉淀进「经验库」，再由 AI 从经验库构建业务血缘图；数据源不直接出图，而是为建好的图节点「供血」，并用真实数据对推断出的血缘边做数据佐证。

![status](https://img.shields.io/badge/status-active-success)
![license](https://img.shields.io/badge/license-internal-blue)
![java](https://img.shields.io/badge/java-17-orange)
![spring--boot](https://img.shields.io/badge/spring--boot-3.3-brightgreen)
![vue](https://img.shields.io/badge/vue-3.5-42b883)
![vite](https://img.shields.io/badge/vite-6-646cff)

---

## 目录

- [一、项目概述](#一项目概述)
- [二、核心范式：经验库 → 血缘图 → 供血](#二核心范式经验库--血缘图--供血)
- [三、快速开始](#三快速开始)
- [四、系统架构与技术栈](#四系统架构与技术栈)
- [五、核心概念](#五核心概念)
- [六、建图流水线详解](#六建图流水线详解)
- [七、功能模块](#七功能模块)
- [八、后端架构](#八后端架构)
- [九、前端架构](#九前端架构)
- [十、数据存储](#十数据存储)
- [十一、安全模型](#十一安全模型)
- [十二、REST API 参考](#十二rest-api-参考)
- [十三、配置与环境变量](#十三配置与环境变量)
- [附录 A：节点 / 边视觉编码与血缘方向语义](#附录-a节点--边视觉编码与血缘方向语义)
- [附录 B：术语表](#附录-b术语表)

---

## 一、项目概述

**推演平台** 面向「业务血缘」的梳理与治理：一个企业里数据从哪来、到哪去、被谁消费、受什么规则约束，往往散落在访谈、文档、数据库和各内网系统里。本平台把这些异构来源统一沉淀成**经验库**，用 AI 从中抽取实体 / 流程 / 事件 / 规则及其关系，构建一张可审计、可追溯、可用真实数据佐证的**有向业务血缘图**。

| 能力 | 说明 |
|---|---|
| **多源沉淀** | 五类信息来源统一进经验库：AI 对话/录音转写、文档导入（PDF/DOCX/图片/CSV/MD）、数据库结构（DDL）、内网系统自动探索、联网调研 |
| **AI 建图** | 从整个工作空间的经验库构建血缘图：分域分批并行抽取、受控词表统一命名、向量兜底同义消解、跨批连边、增量建图 |
| **确定性血缘** | 外键（FK）、视图定义、存储过程/函数源码、SQL 脚本都是血缘 ground truth，确定性解析直出 `confidence=1.0` 的血缘边，不靠 LLM 猜 |
| **数据佐证** | 对「按命名推断」的血缘边做**值包含检验**（子表列的值是否都能在父表列找到），把推断边升级为「数据证实」或否掉 |
| **供血绑定** | 图节点绑定真实数据源（库表 / HTTP 接口），支撑推演与数据落地 |
| **血缘分析** | 上下游追溯、路径查询、结构体检（血缘环 / 方向矛盾 / 孤立节点 / 重复节点）、影响分析报告导出 |
| **可解释** | 节点 / 边标注来源（derived / inferred / manual）、置信度、证据（evidence）、所属领域（domain），全程可审计 |
| **多 LLM** | OpenAI / Anthropic 双协议，多模型即时切换；内建 ASR（语音转写）与 Vision（图片识别）专用模型槽位 |

**适用场景**：业务血缘梳理、跨系统数据流盘点、数据资产治理、流程与规则建模、内网系统功能反推、知识资产沉淀。

---

## 二、核心范式：经验库 → 血缘图 → 供血

这是理解整个系统的关键。**数据源不再直接出图**，数据流分三段：

```
  ┌──────────────── 1. 沉淀进经验库 ────────────────┐
  │  AI 对话 / 录音转写                               │
  │  文档导入   (PDF·DOCX·图片OCR·CSV·MD·文本)         │      经验(experience)
  │  数据库结构 (内省→DDL，含外键血缘结构化片段)  ──▶  │  ─── origin: chat/upload/
  │  系统探索   (Playwright 无头浏览器自动"用"系统)    │       ddl/explore/websearch
  │  联网调研   (搜索→抓取→LLM 归纳《业务知识文档》)   │
  └───────────────────────┬──────────────────────────┘
                          │
  ┌───────────── 2. AI 从经验库建图 ─────────────────┐
  │  受控词表骨架(统一命名) → 分域分批并行抽取         │
  │  → 服务端确定性归一 + 批内去重 → 向量兜底同义消解  │   本体血缘图
  │  → 跨批连边 → 外键确定性血缘直连合并 → 图校验      │ ─── nodes + edges
  │  → 加盐防冲突 → 合并进模型 (支持增量建图)          │    (source/confidence/domain)
  └───────────────────────┬──────────────────────────┘
                          │
  ┌────────────── 3. 数据源供血 + 佐证 ───────────────┐
  │  图节点 ── 绑定 ──▶ 真实数据源(库表/HTTP 接口)     │   可推演、可落地、
  │  推断血缘边 ── 值包含检验 ──▶ 数据证实 / 否决      │   数据证实的业务血缘
  └───────────────────────────────────────────────────┘
```

- **经验库**是建图的**唯一入口**。任何来源都先变成一篇经验文档（Markdown），再统一参与建图 —— 这让抽取口径一致、可增量、可审计。
- **数据源**（关系库 / HTTP 接口）承担两个角色：① 把库结构 DDL 沉淀成经验参与建图；② 为建好的图节点绑定真实数据、并对推断血缘做**值包含检验**。
- **公开资料非本企业事实**：联网调研产出的经验会显式标注，建议建图后用内部三源（访谈 / 系统探索 / 库表）校对。

---

## 三、快速开始

### 3.1 依赖

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 17+ | 后端运行 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 前端构建 |
| PostgreSQL | 13+ | 业务数据（27 张表，启动时自动执行 `init.sql` 建表，幂等） |
| MinIO | 任意 | 对象存储：数据源原文件（PDF/音频等）与抽取文本落桶 |
| LLM API Key | — | 建图 / 抽取 / 对话（默认 DeepSeek，可换 OpenAI / Anthropic / 自建兼容端点） |
| Chromium | 可选 | 系统探索 / SPA 网页兜底渲染（Playwright，首次用到时按提示安装） |

### 3.2 准备外部服务

```bash
# PostgreSQL：建库 tuiyan（表结构由后端启动时自动建）
createdb tuiyan

# MinIO（本地）
docker run -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  quay.io/minio/minio server /data --console-address ":9001"
```

### 3.3 配置（推荐用环境变量）

```bash
export DB_URL=jdbc:postgresql://localhost:5432/tuiyan
export DB_USERNAME=postgres
export DB_PASSWORD=yourpassword
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export DEEPSEEK_API_KEY=sk-xxxx        # 或在 Web 设置页添加自定义模型配置
```

> 所有密钥都应通过环境变量注入，切勿把真实 Key 硬编码进 `application.yml` 提交到仓库。完整变量清单见[第十三节](#十三配置与环境变量)。

### 3.4 启动

```bash
./start.sh                       # 一键同时起前后端
# 或分别启动：
cd backend  && mvn spring-boot:run          # 后端 http://localhost:8000
cd frontend && npm install && npm run dev   # 前端 http://localhost:5173
```

`Ctrl+C` 终止。前端构建：`npm run build`；类型检查：`npm run lint`。

---

## 四、系统架构与技术栈

### 4.1 技术栈

| 层 | 技术 |
|---|---|
| **前端** | Vue 3.5（`<script setup>` + Composition API）、TypeScript、Vite 6、reka-ui、lucide 图标、Tailwind 4；自绘 SVG 图谱画布；SSE 流式 |
| **后端** | Java 17、Spring Boot 3.3、MyBatis-Plus（ORM，无 XML mapper）、SSE Emitter 异步任务池 |
| **存储** | PostgreSQL（27 张表）、MinIO（对象存储）、向量索引（可选 embedding，落库 `*_embedding` 表） |
| **抽取** | Apache PDFBox（PDF）、Apache POI（DOCX/XLSX）、Vision 模型（图片 OCR）、ASR 模型（音频转写） |
| **联网/探索** | jsoup（静态抓取 + DuckDuckGo 检索）、Microsoft Playwright（无头 Chromium：SPA 渲染 + 系统探索 agent） |
| **LLM** | 自研 `LlmHttpClient`：OpenAI / Anthropic 双协议、多模型配置、指标采集 |

### 4.2 分层与规模

```
Browser (Vue SPA :5173)
  ├─ GraphCanvas(SVG)  ChatPanel(SSE)  Import/Extract(SSE)  Analysis 面板
  └─ api 层(15) · composables(40) · components(101)
        │  HTTP / SSE，请求头带 X-Workspace-Id（多租户隔离）
        ▼
Spring Boot 后端 (:8000)
  ├─ Controller(17)  ── WorkspaceInterceptor 校验并注入工作空间上下文(ThreadLocal)
  ├─ Service(~40)    ── 建图流水线 / 抽取 / 连接器 / 探索 agent / 联网调研 / LLM
  ├─ Repository      ── MyBatis-Plus，按工作空间隔离
  └─ 全局异常处理    ── 统一 {error} 形状；未预期异常不外泄内部细节
        │
        ▼
PostgreSQL(27 表) · MinIO(对象存储) · 外部数据源(用户库表/HTTP) · LLM 服务商
```

---

## 五、核心概念

### 5.1 本体图（Ontology Graph）

有向图，由**节点**与**边**构成。

- **节点语义类型**：业务节点 `entity`（对象/实体）、`process`（流程）、`event`（事件）、`rule`（规则）、`metric`（指标）等；Schema 节点 `attribute`（属性）、`constraint`（约束）—— 后者不参与血缘流向，仅描述业务节点的结构。
- **边**：`from → to`，带受控关系类型 `rel_type` 决定血缘方向（见附录 A）。

### 5.2 来源与置信度（可解释性）

每个节点 / 边都带：

- `source`：`derived`（有原文依据派生）/ `inferred`（按命名等推断）/ `manual`（人工）/ `preset`（预置）。
- `confidence`：置信度 0–1。外键确定性血缘 = 1.0；跨批连边封顶 0.55；值包含检验证实后按裁决写回。
- `evidence`：血缘证据（原文引文 / 外键约束名 / 数据佐证结论）。
- `derived_source`：来自哪篇经验 / 哪个数据源。
- `domain`：所属业务领域（由经验所在文件夹决定），供前端分组 / 着色 / 折叠。

### 5.3 血缘方向语义

血缘遍历、体检、影响分析统一把边归一化为 `source → result`（流向）：

- **反向类** `derived_from / depends_on / consumes / composed_of`：存储方向与流向相反（`from` 是结果，`to` 是来源）。
- **纯关联** `associated_with`：无数据流向，不参与血缘。
- 其余（`produces / flows_to / transforms / triggers / governs`…）：`from` = 来源、`to` = 结果。

> 前后端一致：前端 `useLineageTrace.buildFlowGraph` 与后端 `EdgeSemantics` 使用同一套规则，改一处需同步另一处。

### 5.4 经验（Experience）

一篇 Markdown 文档 + `origin` 标签（`chat` / `upload` / `ddl` / `explore` / `websearch` / `datasource`）。经验按文件夹归类，文件夹即「业务领域（domain）」。经验可被工作空间「引用」纳入侧栏，并建立 RAG 向量索引供检索。

### 5.5 数据源供血（Data Source Binding）

数据源为**全局公共资源**，工作空间通过「引用」纳入。数据源类型：`mysql` / `pgsql` / `oracle` / `dm` / `gbase`（关系库，走 JDBC 连接器）、`https_api`（HTTP 接口，可定时拉取）。图节点通过 `node_data_binding` 绑定到具体库表 / 接口，实现供血；推断血缘边可对绑定库做值包含检验。

### 5.6 工作空间（Workspace，多租户隔离）

所有业务数据按工作空间隔离。前端每个 `/api` 请求带 `X-Workspace-Id` 头，后端 `WorkspaceInterceptor` 校验工作空间存在并注入 `ThreadLocal` 上下文；仓储层据此过滤。白名单接口（工作空间管理、系统、配置、模型、偏好、抽取）不强制该头。

---

## 六、建图流水线详解

入口 `POST /api/experiences/extract-ontology`（SSE 流式），核心在 `ExperienceOntologyService.extractFromWorkspace`。一次建图：

1. **读取经验库**：聚合当前工作空间全部经验（或指定范围），按文件夹划分领域（domain）。
2. **结构化片段直采**：探索文档 / DDL 导出里内嵌的 `<!-- EXPLORE_GRAPH {json} -->` 图片段被直接解析合并、免 LLM 重抽（`extractGraphFragment`）。**外键血缘**即走此路（见下）。
3. **增量建图**（可选）：按目标模型的构建记录（内容哈希）跳过未变化的经验，只抽新增/变更；并检测「上次建图引用、现已删除」的孤儿来源。
4. **受控词表骨架**：先对经验采样，用一次 LLM 调用归纳出「规范名 + 别名 + 类型」的受控词表（每工作空间一份），注入各批抽取 prompt，消除「客户/顾客/Customer 在不同批各造一个节点」的跨批命名漂移。
5. **分域分批并行抽取**：按领域分组、组内按字符预算打包，各批并行调 LLM（有界线程池，防限流）；DDL 批走 schema 专用 prompt。单批失败重试一次，全失败才报错，部分失败继续合并。
6. **服务端确定性归一 + 批内去重**：LLM 对词表的遵循不可靠，服务端把命中别名的 label 强制改写为规范名；`dedupeWithin` 折叠单批内部同名重复节点（跨批合并只折叠批间）。
7. **向量兜底同义消解**：词表没收录的同义词（收款/回款）用向量召回高相似异名对 + 一次 LLM 仲裁 + 并查集折叠；发现的同义反哺词表，下次走确定性快路径。embedding 未配置时整体 no-op。
8. **跨批连边**：并行各批互相看不见对方实体，跨批关系天然缺失；用实体清单追加一轮轻量 LLM 只补跨批关系（`source=inferred`、置信度封顶 0.55、打 `cross_batch` 标记）。
9. **库内确定性血缘**：DDL 导出时，`SchemaGraphFragmentRenderer` 把三类血缘 ground truth 渲染成结构化片段随经验直连合并（`source=derived`、`confidence=1.0`，不靠 LLM 猜）：① 外键 → `depends_on` 边（父表=上游、子表=下游）；② 视图定义（FROM/JOIN 来源表 → 视图）→ `flows_to` 边；③ 存储过程/函数源码（INSERT…SELECT / MERGE / UPDATE…FROM 的来源 → 写入目标）→ `flows_to` 边（`SchemaSqlLineage` 语句级解析）。存储过程源码节选同时附在 DDL 文档里供 LLM 做业务语义增强。
10. **图校验**（`sanitizeGraph`）：丢弃空/重复 id 节点、悬空边、自环，按 (from,to,rel_type) 去重。
11. **加盐防冲突**：给全部 id 加导入 salt，避免多次导入 id 冲突；产出 `{nodes, edges, reply, salt, manifest}`。
12. **合并前体检**：前端在「合并进模型」前先跑结构体检（血缘环 / 方向矛盾 / 孤立 / 重复），把抽取错误挡在污染模型之前。用户确认后合并，并回写构建记录供下次增量。

---

## 七、功能模块

### 7.1 工作空间
创建 / 切换 / 重命名 / 删除；删除最后一个工作空间后自动退回选择页，避免发无头请求。默认工作空间。

### 7.2 经验库（建图的唯一入口）
五类来源统一沉淀为经验：
- **AI 对话 / 录音**：对话建模，录音自动 ASR 转写。
- **文档导入**：PDF（PDFBox）、DOCX（POI）、图片（Vision OCR）、CSV / Markdown / 纯文本，SSE 流式抽取。
- **数据库结构导出**：内省 schema（表/视图/外键/存储过程源码）→ 渲染 DDL（含样例数据可选、过程源码节选）→ 存为经验，并内嵌外键/视图/存储过程血缘的确定性结构化片段。
- **系统探索**：见 7.7。
- **联网调研**：见 7.8。

经验按文件夹（= 领域）归类、可引用进工作空间、建 RAG 索引。

### 7.3 本体血缘图构建
即第六节流水线。支持全量 / 增量、指定经验范围、用户额外提示（如「重点关注审批链路」）。

### 7.4 图谱可视化与编辑
自绘 SVG 画布：节点拖拽、框选、缩放平移、右键菜单、批量操作；节点 / 边增删改；节点详情与 Schema（属性 / 约束）编辑；撤销 / 重做历史；图谱搜索定位；节点着色（按类型 / 领域）；导入合并与去重。

### 7.5 血缘分析与体检（「体检」面板 4 个标签）
- **全图统计**：规模、类型分布、度分布等。
- **节点分析**：上下游血缘追溯（画布高亮：蓝=上游、橙=下游）；**一键导出《血缘影响分析报告》Markdown**（下游爆炸半径按跳数分层 + 上游来源链 + 直接关系）。
- **路径查询**：两节点间血缘路径。
- **体检**：来源标注率 / 方向语义率 / 证据覆盖率 / 供血绑定覆盖率；**孤立节点**与**血缘碎片化**；**冲突检测**（方向矛盾 / 疑似重复 / 血缘环，含「疑点边」修正建议）；**推断边批量数据佐证**（值包含检验，支持「一键数据佐证」引导）；Schema 漂移检测。

### 7.6 数据源与供血
数据源 CRUD（密码 / 鉴权头输出脱敏）；连接测试；schema 内省；只读 SQL 执行（白名单 + 危险函数黑名单 + 强制 LIMIT）；HTTP 接口执行与定时调度；节点供血绑定；**值包含检验**把推断血缘升级为数据证实。

### 7.7 系统探索 Agent
用 Playwright 无头 Chromium 像人一样「用」一个内网 web 系统（可账号密码自动登录），把页面编码成文本快照交 LLM 理解，代码维护 frontier 做覆盖式爬取，产出《业务说明文档》落经验库。**纵深只读护栏**：危险元素标记 + 拒点写操作/登出 + 网络层拦非幂等请求 + 锁定同站，防误改数据、防跑偏第三方站。**LLM 只做页面理解、不驱动动作**，天然抗页面提示注入。

### 7.8 联网调研
搜索业务主题（DuckDuckGo）→ 抓取命中网页（SSRF 防护：拦内网/云元数据）→ LLM 归纳成《业务知识文档》落经验库，正文带来源编号与链接。

### 7.9 多 LLM 配置
`application.yml` 预置 + Web 设置页自定义模型（base URL / Key / 协议 / 上下文窗口 / 能力）。能力标签：`streaming` / `json` / `tools` / `asr`（音频）/ `vision`（图片）。连接测试。

### 7.10 版本管理与监控
模型版本快照自动备份与回滚；对话历史与上传文件回看；系统监控（LLM 调用指标、健康检查）；用户偏好（主题 / 布局 / 当前模型）。

---

## 八、后端架构

### 8.1 Controller（17，`/api/*`）

| 控制器 | 根路由 | 职责 |
|---|---|---|
| `WorkspaceController` | `/api/workspaces` | 工作空间 CRUD（白名单，不需 ws 头） |
| `OntologyModelController` | `/api/ontology-models` | 本体模型 CRUD、版本、血缘遍历、Schema 漂移、抽取（含 SSE） |
| `ModelController` | `/api/models` | LLM 模型配置与连接测试 |
| `ChatController` | `/api/chat` | 对话建模（SSE） |
| `ExperienceController` | `/api/experiences` | 经验库 CRUD、索引、**经验库建图**（SSE）、联网调研（SSE） |
| `ExperienceFolderController` | `/api/experience-folders` | 经验文件夹（领域） |
| `ExploreController` | `/api/explore` | 系统探索 agent（SSE） |
| `DataSourceController` | `/api/data-sources` | 数据源 CRUD、测试、内省、SQL、值包含检验 |
| `DataSourceFolderController` | `/api/data-source-folders` | 数据源文件夹 |
| `NodeBindingController` | `/api/node-bindings` | 节点供血绑定 |
| `DocumentTextController` | `/api/extract` | 文档 / 音频抽取（白名单） |
| `ConversationController` | `/api/conversations` | 对话历史 |
| `GraphTemplateController` | `/api/templates` | 图谱模板库 |
| `IndexController` | `/api/index` | RAG 向量索引 |
| `SystemController` | `/api/system` | 系统监控 / 健康（白名单） |
| `ConfigController` | `/api/config` | 前端配置下发（白名单） |
| `PrefsController` | `/api/prefs` | 用户偏好（白名单） |

### 8.2 Service（~40，按域组织）

- **建图流水线**：`ExperienceOntologyService`（编排）、`ExtractionLlmService`、`ExtractionGraphMerger`（图代数：切片/前缀/按 label 合并/批内去重/图校验）、`OntologyVocabService`（受控词表）、`EntityAlignmentService`（向量对齐）、`ExistingGraphContextService`（增量回灌）、`SchemaDriftService`、`LineageTraversalService`。
- **抽取**：`DocumentExtractionService` / `DocxExtractionService` + `support/*`（PdfTextExtractor、DocxTextExtractor、XlsxTextExtractor、FileSniffer、TextDecoder）。
- **数据源连接器**（`service/connector`）：`JdbcConnectorService`（只读 SQL、schema 内省、值包含检验、DDL 样例）、SQL 方言体系（`SqlDialect` + mysql/pgsql/oracle/dm/gbase）、`HttpConnectorService` + `HttpScheduler`、`FileStoredService`。
- **LLM**（`service/llm`）：`LlmHttpClient`（双协议）、`DdlRenderer`、`SchemaGraphFragmentRenderer`（外键→确定性血缘片段）、`SchemaPromptRenderer`、prompt 模板。
- **Agent / 联网**：`agent/ExplorationAgentService` + `BrowserAgentDriver`（Playwright）、`WebResearchService` + `support/WebPageFetcher`（SSRF 防护）、`WebSearchClient`。
- **存储 / 治理**：`storage/MinioObjectStorage`、`WorkspaceService` / `WorkspaceCascadeCleaner`、`ConversationService`、`indexing/*`（RAG）。

### 8.3 横切
`WorkspaceInterceptor` + `WorkspaceContext`（ThreadLocal）多租户隔离；`AsyncConfig`（`appTaskExecutor` 建图/抽取池 + `explorationExecutor` 探索专用有界池 + 工作空间上下文透传装饰器）；`GlobalExceptionHandler`（统一错误形状、未预期异常不外泄）。

---

## 九、前端架构

- **api 层（15）**：`http.ts`（统一请求 + `X-Workspace-Id` 注入 + SSE 解析）+ 各资源模块（ontology / experiences / dataSources / chat / models / …）。
- **composables（40）**：图编辑/历史/搜索/统计（`useGraphEditor` / `useGraphHistory` / `useGraphSearch` / `useGraphStats`）、**血缘（`useLineageTrace`：`buildFlowGraph` 单一真源）**、画布（视口/拖拽/框选/右键）、聊天/发送/流（`useChatSend` / `useExtractStream`）、导入合并去重、工作空间、模型配置、监控等。
- **utils**：`markdown.ts`（零依赖 MD→HTML，链接 scheme 白名单防 XSS）、**`lineageHealth.ts`（孤立节点/碎片化体检）**、**`lineageReport.ts`（影响分析报告生成）**、`lineageConflicts.ts`（方向矛盾/重复/环）、`lineageVerify.ts`（值包含检验目标解析）。
- **components（101）**：`GraphCanvas`（SVG 画布）、`ChatPanel`、`Sidebar`、`ImportDialog`、`ExpOntologyExtractDialog`（建图结果 + 合并前体检）、`NodeInfo` / `EdgeInfo` / `SchemaPanel`、`GraphAnalysisPanel` + `analysis/*`（GraphStats / NodeAnalysis / PathQuery / LineageHealth）、`datasource/*`、`settings/*`、`form/*`、`ui/*`。

---

## 十、数据存储

### 10.1 PostgreSQL（27 张表，`init.sql` 幂等自建）

| 域 | 表 |
|---|---|
| 工作空间 | `workspace`、`workspace_vocab`（受控词表）、`schema_migration` |
| 本体模型 | `ontology_model`、`ontology_node`、`ontology_node_prop`、`ontology_edge` |
| 版本快照 | `ontology_model_version`、`ontology_version_node`、`ontology_version_node_prop`、`ontology_version_edge` |
| 经验库 | `experience`、`experience_folder`、`experience_ref`、`exp_chunk`、`exp_embedding`（RAG） |
| 数据源 | `data_source`、`data_source_folder`、`data_source_ref`、`data_source_fetch_log`、`ds_chunk`、`ds_embedding` |
| 绑定 / 建图 | `node_data_binding`（节点供血）、`model_build_source`（增量建图记录） |
| 对话 / 模板 | `conversation`、`conversation_message`、`graph_template` |

### 10.2 对象存储（MinIO）
数据源原文件（PDF/DOCX/音频/图片等）与抽取文本统一落桶，不落本地磁盘。

### 10.3 向量检索（RAG）
经验 / 数据源文本切片（`*_chunk`）+ 向量（`*_embedding`）；embedding 未配置时相关功能优雅降级（如向量对齐 no-op）。

---

## 十一、安全模型

| 面 | 防护 |
|---|---|
| **多租户隔离** | `X-Workspace-Id` 强制校验；仓储按工作空间过滤；模型 get/save/delete + 血缘/漂移接口均做归属校验（防 IDOR） |
| **外部 SQL** | 只读语句白名单（SELECT/SHOW/DESC/EXPLAIN）+ 危险函数黑名单（`INTO OUTFILE`/`pg_read_file`/`COPY`…）+ 多语句拦截 + 标识符白名单 + JDBC 危险参数过滤 + 强制 LIMIT + 查询超时 |
| **应用自身 SQL** | 全 MyBatis-Plus 参数化，无 `${}` 拼接、无原生 SQL |
| **SSRF** | 联网抓取仅 http/https + 解析后 IP 校验（拦 loopback/私网/link-local/CGNAT/云元数据）；系统探索有意允许内网但拦云元数据 |
| **浏览器 Agent** | LLM 不驱动动作（抗提示注入）+ 三层只读护栏 + 锁同站 + 禁下载 + 有界并发池 |
| **凭据** | 数据源密码 / 鉴权头输出脱敏，更新走遮蔽合并；storageState 仅服务端侧通道 |
| **错误外泄** | SSE 与全局处理器均对未预期异常回退通用文案、只记服务端日志（避免泄露连接串/内部路径） |
| **XSS** | Markdown 渲染整体转义 + 链接 scheme 白名单（拦 `javascript:`/`data:` 等）+ 引号编码 |

> 密钥务必用环境变量注入，不要提交进仓库；若历史里泄漏过 Key，需到对应控制台作废重签。

---

## 十二、REST API 参考

所有业务接口需带 `X-Workspace-Id` 头（白名单接口除外）。错误统一返回 `{"error": "..."}`。SSE 接口事件序列一般为 `step*`（进度）→ `complete`（结果）/ `error`。

**工作空间** `/api/workspaces`：`GET` 列表 · `POST` 建 · `PUT/{id}` 改 · `DELETE/{id}` 删。

**本体模型** `/api/ontology-models`：`GET`/`GET{id}`/`POST`/`PUT{id}`/`DELETE{id}` · `GET{id}/lineage?node&direction&depth` 血缘遍历 · `POST{id}/schema-drift` 漂移检测 · `POST{id}/build-sources` 回写建图来源 · `GET{id}/versions` · `POST{id}/versions/{ts}/restore` · `POST /extract`、`POST /extract/stream`（SSE）。

**经验库** `/api/experiences`：`GET`（`?all` / `?workspaceId`）· `GET /referencable` · `POST /refs` 引用 · CRUD · `POST /extract-ontology`（SSE，**经验库建图**）· `POST /web-research`（SSE，联网调研）。

**系统探索** `/api/explore`：`POST`（SSE，账号密码/只读参数）。

**数据源** `/api/data-sources`：CRUD · `POST{id}/test` · `POST /test-inline` · `GET{id}/tables` · `GET{id}/introspect` · `POST{id}/sql` · `POST{id}/verify-containment`（值包含检验）· `POST{id}/execute`（HTTP）· `POST{id}/schedule` · `GET{id}/logs`。

**其他**：`/api/node-bindings`（供血绑定）· `/api/extract`（文档/音频抽取）· `/api/conversations` · `/api/templates` · `/api/index`（RAG）· `/api/models`（LLM 配置/测试）· `/api/config` · `/api/prefs` · `/api/system`（监控/健康）。

---

## 十三、配置与环境变量

`backend/src/main/resources/application.yml`，均可用环境变量覆盖：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost:5432/tuiyan | PostgreSQL |
| `DB_INIT_SCHEMA` | `true` | 启动自动执行 `init.sql` |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | localhost:9000 / minioadmin / minioadmin / tuiyan | 对象存储 |
| `DEEPSEEK_API_KEY` | — | 建图 / 抽取 / 对话默认模型 Key |
| `ASR_*`（`ASR_API_KEY` / `ASR_BASE_URL` / `ASR_MODEL` / `ASR_ENABLED` …） | 关闭 | 音频转写模型 |
| `VISION_*`（`VISION_API_KEY` / `VISION_BASE_URL` / `VISION_MODEL` / `VISION_ENABLED`） | 关闭 | 图片识别模型 |
| `UPLOAD_MAX_FILE_SIZE` / `UPLOAD_MAX_REQUEST_SIZE` | 200MB / 400MB | 上传上限 |
| `app.data.dir` | `~/.tuiyan` | 本地小配置文件（prefs.json 等） |

> 生产部署务必覆盖 DB 密码、MinIO 凭据与全部 LLM Key。

---

## 附录 A：节点 / 边视觉编码与血缘方向语义

**节点语义类型**：`entity`（实体/对象）· `process`（流程）· `event`（事件）· `rule`（规则）· `metric`（指标）为业务节点；`attribute`（属性）· `constraint`（约束）为 Schema 节点（不参与血缘流向）。Schema 面板按 对象 / 关系类型 / 属性 / 约束 四类着色。

**边关系类型与血缘方向**（归一化为 `source → result`）：

| rel_type | 语义 | 流向 |
|---|---|---|
| `produces` | 产生 | from→to（from=来源） |
| `flows_to` | 流向 | from→to |
| `transforms` | 转换 | from→to |
| `triggers` | 触发 | from→to |
| `governs` | 治理/约束 | from→to |
| `derived_from` | 派生自 | **反向**（to=来源，from=结果） |
| `depends_on` | 依赖（含外键） | **反向** |
| `consumes` | 消费 | **反向** |
| `composed_of` | 组成 | **反向** |
| `associated_with` | 关联 | **不参与血缘**（无流向） |

**来源着色**：`derived`（有据派生）· `inferred`（推断，建议数据佐证）· `manual`（人工）· `preset`（预置）。

---

## 附录 B：术语表

| 术语 | 含义 |
|---|---|
| 经验（experience） | 沉淀进经验库的一篇 Markdown 文档，是建图唯一入口 |
| 供血（binding） | 图节点绑定到真实数据源（库表/接口） |
| 值包含检验 | 验证 `子表.列 ⊆ 父表.列`，把推断血缘边升级为数据证实或否决 |
| 受控词表（vocab） | 每工作空间一份的「规范名+别名+类型」，统一跨批命名 |
| 向量兜底对齐 | 词表未收录的同义词用 embedding 召回 + LLM 仲裁折叠 |
| 跨批连边 | 并行各批抽完后补跨批缺失关系（inferred，低置信） |
| 外键确定性血缘 | 数据库 FK 直出为 `confidence=1.0` 的 `depends_on` 血缘边 |
| SQL 定义体血缘 | 视图定义 / 存储过程源码经语句级解析直出 `confidence=1.0` 的 `flows_to` 数据流边 |
| 增量建图 | 按内容哈希跳过未变化经验，只抽新增/变更 |
| domain | 经验所属文件夹 = 业务领域，用于分域抽取与节点打标 |
| 结构体检 | 血缘环 / 方向矛盾 / 孤立节点 / 重复节点 / 碎片化的纯计算校验 |
| 爆炸半径（blast radius） | 改动某节点顺血缘波及的下游范围 |

---

> 本 README 反映当前架构（经验库驱动建图 + 数据源供血）。数据流、方向语义、安全模型请以代码为准；前后端血缘方向规则须保持一致（`useLineageTrace.buildFlowGraph` ↔ 后端 `EdgeSemantics`）。
