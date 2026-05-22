# 推演平台 (EIC-CC) 功能文档

> 基于本体图谱的 AI 因果推演系统：通过自然语言对话构建知识图谱，沿因果链做正向预测或反向溯因，支持多分支假设对比。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 系统架构](#2-系统架构)
- [3. 技术栈](#3-技术栈)
- [4. 快速开始](#4-快速开始)
- [5. 核心概念](#5-核心概念)
- [6. 功能模块](#6-功能模块)
  - [6.1 本体模型管理](#61-本体模型管理)
  - [6.2 AI 对话建模](#62-ai-对话建模)
  - [6.3 图谱可视化与交互](#63-图谱可视化与交互)
  - [6.4 撤销 / 重做 / 历史](#64-撤销--重做--历史)
  - [6.5 节点详情与 Schema](#65-节点详情与-schema)
  - [6.6 因果推演 (Prediction)](#66-因果推演-prediction)
  - [6.7 推演分支与对比](#67-推演分支与对比)
  - [6.8 文档导入 (PDF / 图片)](#68-文档导入-pdf--图片)
  - [6.9 假设模板](#69-假设模板)
  - [6.10 多模型 LLM 配置](#610-多模型-llm-配置)
  - [6.11 对话历史](#611-对话历史)
  - [6.12 导出与共享](#612-导出与共享)
  - [6.13 用户偏好设置](#613-用户偏好设置)
- [7. 数据存储](#7-数据存储)
- [8. REST API 一览](#8-rest-api-一览)
- [9. 快捷键](#9-快捷键)
- [10. 配置与环境变量](#10-配置与环境变量)

---

## 1. 项目概述

**推演平台** 是一个面向因果推理的 AI 辅助本体建模工具，核心能力：

| 能力 | 说明 |
|---|---|
| **本体建模** | 用自然语言或导入文档，自动抽取实体、流程、事件、规则及其关系，构建有向图谱 |
| **因果推演** | 在已有图谱上以选定节点为起点（或终点），由 LLM 沿因果链做多步正向/反向推演 |
| **分支假设** | 每一次推演形成独立的"假设分支"，可在主干与多个分支之间切换、对比、回写主干 |
| **多源融合** | 支持上传 PDF、图片、Markdown、文本、CSV 等结构化/非结构化资料 |
| **可解释性** | 节点/边都标注来源（derived / inferred / predicted），便于审计 |

适用场景：供应链推演、流程审计、事故复盘、规则建模、知识资产沉淀等。

---

## 2. 系统架构

```
┌──────────────────────────────────────────────────────────┐
│                       Browser                            │
│  ┌──────────────────────────────────────────────────┐   │
│  │            Vue 3 SPA  (frontend)                 │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────┐     │   │
│  │  │ Graph    │ │ Chat     │ │  Timeline    │     │   │
│  │  │ Canvas   │ │ Panel    │ │  Predict     │     │   │
│  │  └──────────┘ └──────────┘ └──────────────┘     │   │
│  └──────────────────────┬───────────────────────────┘   │
└─────────────────────────┼─────────────────────────────────┘
                          │  HTTP / SSE
                          ▼
┌──────────────────────────────────────────────────────────┐
│             Spring Boot 后端 (port 8000)                  │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Controllers  /  Services  /  Models             │   │
│  │  ChatController       ─ 对话 + 图谱增量          │   │
│  │  ScenarioController   ─ 推演与分支               │   │
│  │  OntologyModelController ─ 本体 CRUD + 抽取      │   │
│  │  ConversationController, HypothesisTemplate ...  │   │
│  └─────────────────┬────────────┬───────────────────┘   │
│         │                       │                        │
│         ▼                       ▼                        │
│  ┌─────────────┐         ┌──────────────┐              │
│  │ LlmService  │  HTTP   │  File Store  │              │
│  │  (OpenAI/   │ ─────►  │ ~/.tuiyan/   │              │
│  │  Anthropic) │         │  *.json      │              │
│  └─────────────┘         └──────────────┘              │
└──────────────────────────────────────────────────────────┘
```

- 前端 SPA 与后端通过 `/api/**` 通信；Vite 开发期代理到 `localhost:8000`。
- 流式数据走 **SSE**（`Server-Sent Events`），客户端可中途 `abort`。
- 后端无数据库，所有数据落本地文件（`~/.tuiyan/`）。

---

## 3. 技术栈

| 层 | 技术 |
|---|---|
| **前端** | Vue 3.5 (Composition API + `<script setup>`)、TypeScript 5.8、Vite 6 |
| **后端** | Java 17、Spring Boot 3、Spring Web、Jackson、Java `HttpClient` |
| **LLM 协议** | OpenAI 兼容 / Anthropic（streaming + JSON 输出） |
| **文档解析** | Apache PDFBox（PDF 文本抽取） |
| **存储** | 本地 JSON 文件，路径见 [§7](#7-数据存储) |
| **通信** | HTTP + SSE（流式生成 / 推演实时回执） |

---

## 4. 快速开始

### 4.1 依赖

- JDK 17+，Maven 3.8+
- Node.js 18+，npm
- LLM API Key（默认配置 DeepSeek，可在设置中替换为 OpenAI / Anthropic / 自建兼容端点）

### 4.2 一键启动

```bash
./start.sh
```

脚本会同时启动：

- 后端：`http://localhost:8000`
- 前端：`http://localhost:5173`

按 `Ctrl+C` 终止两个进程。

### 4.3 单独启动

```bash
# 后端
cd backend && mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev
```

### 4.4 配置 LLM Key

两种方式任选其一：

1. **环境变量**：`export DEEPSEEK_API_KEY=sk-xxxx`
2. **Web 设置页**：左侧导航进入「设置」，添加自定义模型配置（base URL、API Key、协议、上下文窗口等）。

---

## 5. 核心概念

| 概念 | 说明 |
|---|---|
| **本体模型 (OntologyModel)** | 一个图谱画布，包含 nodes / edges 与元信息 |
| **节点类型** | `entity`（实体）、`process`（流程）、`event`（事件）、`data`（数据源）、`external`（外部系统）、`rule`（规则/条件） |
| **边来源 (source)** | `derived`（文本明示）、`inferred`（模型推断填充）、`predicted`（推演生成）、`preset`（预置）|
| **rule_driven 边** | 由 rule 节点驱动的关系，画布中以粉色显示 |
| **主干 (trunk)** | 本体模型的"真实"图谱状态 |
| **分支 (branch)** | 一次推演的快照，挂在主干或其它分支下，可以增量增加节点/边 |
| **推演意图 (intent)** | `forward`（从原因到结果）或 `backward`（从结果反推原因） |

---

## 6. 功能模块

### 6.1 本体模型管理

文件：`OntologyModelController.java`、`useOntologyModel.ts`、`Sidebar.vue`

| 功能 | 入口 | 说明 |
|---|---|---|
| 创建模型 | 「列表」页 → "新建模型" / 欢迎页对话提交 | 自动生成默认标题，创建后跳转到画布 |
| 列出模型 | 左侧栏 / "列表" 页 | 卡片形式显示节点/关系统计与更新时间 |
| 打开模型 | 点击模型卡片或左侧条目 | 加载 nodes/edges 并重置历史栈 |
| 重命名 | 顶栏标题（规划中） | — |
| 删除模型 | 卡片右上角 "×" | 二次确认，同时清理对应分支 |
| 自动保存 | 任何编辑触发 | 1.2 s 防抖 PUT 到后端；切换/撤销时立即保存 |

模型 JSON 结构：

```jsonc
{
  "id": "om_1700000000",
  "title": "供应链本体图",
  "desc": "...",
  "graphData": {
    "nodes": [ /* OntologyNode[] */ ],
    "edges": [ /* OntologyEdge[] */ ]
  },
  "updated": "2026-05-22 10:00"
}
```

节点结构：

```jsonc
{
  "id": "n_xxx",
  "label": "客户",
  "type": "entity",
  "source": "derived",
  "x": 120, "y": 240,
  "props": [ { "key": "..", "value": "..", "source": "derived" } ]
}
```

---

### 6.2 AI 对话建模

文件：`ChatPanel.vue`、`ChatController.java`、`LlmService.java`

#### 6.2.1 流程

```
用户输入  ──►  ChatPanel.send()
                │
                ├─ 收集附件（图片走 dataUrl；文本拼入正文；PDF 已在导入流程预处理）
                ├─ 拼装 history（最近 40 条）
                ├─ 附带当前图谱摘要（nodes/edges 精简版）
                ▼
        POST /api/chat (Accept: text/event-stream)
                │
                ├─ event: text       ── 模型 token 流（聚合为 rawJsonBuf）
                ├─ event: complete   ── 解析后的 { reply, add_nodes, add_edges }
                └─ event: error      ── 上游或解析错误
                ▼
        前端 dedupeIncoming → history.snapshot() → 落图
```

#### 6.2.2 增量扩展（重要）

后端 `buildChatPrompt()` 把当前图谱摘要拼到 user prompt 前面，明确告诉 LLM：

> "Existing ontology graph (the user is incrementally extending this — do NOT recreate any of these; reuse the ids exactly when you need to reference them)…"

前端再做 **两层防御性去重**：

1. **按 id**：返回中已存在的 id 直接丢弃。
2. **按 `label + type`**：命中现有节点，则记入 `idRemap`，并把 LLM 输出的 edges 引用重写到旧 id 上。
3. **edges 二次去重**：`(from, to, label)` 三元组命中则丢弃；id 冲突时自动重新分配 id。
4. 全部被去掉时 toast 提示 "已忽略 N 个重复节点 / M 条重复关系"。

#### 6.2.3 流式控制

- 模型生成中，对话框右下角的"发送箭头"自动变为白底方块的**停止按钮**，外圈带绿色旋转环。
- 点击停止：调用 `chatHandle.abort()` 中断 SSE，AI 占位气泡更新为"已停止生成。"，`loading` 状态立即清理。
- 切换会话或卸载组件时也会自动中止当前流。

#### 6.2.4 @ 提及

输入 `@` 触发下拉，候选包含画布中所有节点与关系。`↑↓` 选择、`Enter` 确认、`Esc` 取消。被引用的实体名会插入到输入框。

#### 6.2.5 附件支持

| 类型 | 处理方式 |
|---|---|
| 图片（PNG / JPEG / WebP / GIF） | 编码为 `dataUrl`，作为多模态 attachment 发给模型 |
| 文本类（txt / md / json / csv / 源代码…） | 读取为字符串拼到 prompt 后部，超大文件自动截断并标注 |
| PDF | 走「导入」流程预先抽取（见 §6.8） |

---

### 6.3 图谱可视化与交互

文件：`GraphCanvas.vue`

#### 6.3.1 画布操作

| 操作 | 行为 |
|---|---|
| 拖动空白区 | 平移画布 |
| `Ctrl/⌘ + 滚轮` | 以鼠标为锚点缩放（0.1× ~ 3×） |
| 点击节点 | 选中 + 高亮相关边 |
| 拖动节点 | 移动节点（拖拽开始时触发一次历史快照） |
| 右键节点 | 弹出"从此推演"上下文菜单 |
| 点击空白 | 取消选中 |
| 双指/+ - 按钮 | 缩放 |

#### 6.3.2 顶部工具栏

```
[↶ 撤销] [↷ 重做]   [分支选择]   [⚖ 对比] [📥 导入]   [● N 节点] [M 关系]   [Schema] [导出] [共享]
```

#### 6.3.3 画布动作浮条

居中显示：

- **适应屏幕** — 自动 fit-view，重新计算缩放与中心
- **自动布局** — 分层 (Sugiyama 风格) DAG 布局，处理回边、孤立节点
- **布局方向** — 在「从左向右 (LR)」与「从上到下 (TB)」之间切换
- **清空画布** — 二次确认后清空（产生一次撤销快照）

#### 6.3.4 图例（Legend）

右上角彩色色块列表，每行对应一种节点类型。**点击图例项可作为筛选器：**

- 第一次点击：高亮该类型节点（描边、阴影加重），其它节点和不相关连线统一变暗 (opacity 0.2 / 0.15)
- 再次点击同一项：清除筛选
- 点击其它类型：切换筛选目标

#### 6.3.5 视觉编码

| 视觉 | 含义 |
|---|---|
| 节点左侧粗色条 | 节点类型（颜色与图例一致） |
| 节点绿色脉冲点 | 新增 (`isNew`) 节点，0.8 s 后还原 |
| 节点白边 + 阴影 | 当前选中 |
| 节点右上"预测"角标 | `source === 'predicted'`，附带步数 |
| 边白色流光 | 普通"流向"动画，选中时加速 |
| 边粉色 | `rule_driven`（规则驱动） |
| 边金色虚线 | `source === 'predicted'`（推演新增） |

---

### 6.4 撤销 / 重做 / 历史

文件：`composables/useGraphHistory.ts`、`App.vue`

| 项 | 值 |
|---|---|
| 最大历史条数 | 50 |
| 触发快照的时机 | LLM 增量更新、清空画布、自动布局、合并导入、节点拖拽（一次拖拽只一次） |
| 撤销 | 顶栏 ↶ / `Ctrl/⌘ + Z` |
| 重做 | 顶栏 ↷ / `Ctrl/⌘ + Shift + Z` / `Ctrl + Y` |
| 输入框内 | 不抢键盘（让浏览器原生撤销生效） |
| 切换模型 | 历史栈重置，以当前模型快照为起点 |
| 同状态去重 | 连续两次 snapshot 若内容一致则只保留一份，避免空快照堆积 |
| 撤销后落盘 | 立即调用 `persistCurrentModel(true)`，不走防抖 |

栈语义：经典 undo —— 处于历史中间时再做新改动，会丢弃 redo 分支。

---

### 6.5 节点详情与 Schema

文件：`NodeInfo.vue`

- 选中节点后，右侧抽屉显示：节点元数据、属性表 (`props[]`)、入边/出边列表（点击跳转）、推演置信度（如果是预测节点）。
- 顶栏 "Schema" 按钮：切换"模式视图"，显示当前图谱里每种 type 的属性合集，便于审计/导出。

---

### 6.6 因果推演 (Prediction)

文件：`PredictDialog.vue`、`ScenarioTimeline.vue`、`ScenarioController.java`、`PredictionOrchestrator.java`

#### 6.6.1 启动方式

1. **右键节点 → 从此推演** — 该节点预填为 seed
2. **手动**（规划：顶栏入口）

#### 6.6.2 参数

| 字段 | 说明 |
|---|---|
| `intent` | `forward`（正向推演，从 seeds 走向下游）或 `backward`（反向溯因） |
| `seeds` | 起点（或目标点）节点 id 列表 |
| `steps` | 推演步数 / 溯因层数（默认 4） |
| `prompt` | 自然语言场景描述（"假设主供应商断货 3 天 …"） |
| `constraints` | 单点约束：`mode` 可为 `require` / `exclude` 等（用于剪枝） |
| `name` | 分支名 |

#### 6.6.3 流式响应

推演走 SSE，前端在画布右侧打开 **时间轴 (ScenarioTimeline)**：

- 实时显示每一步的事件、置信度、被剪枝的候选
- 推演完成后预测节点入图（金色虚线 + 推演角标）
- 关闭时间轴回到对话面板

#### 6.6.4 落盘格式

新分支以 **增量 DAG** 形式落盘（仅记录预测新增的节点/边，主干通过 `parentBranchId` 引用），节省空间，便于做 trunk vs branch 的对比。

---

### 6.7 推演分支与对比

文件：`BranchPicker.vue`、`BranchCompareDialog.vue`、`useScenarios.ts`

#### 6.7.1 分支选择器

顶栏左侧下拉，列出主干 + 所有分支：

- 切换分支：画布即时刷新为该分支视图（主干 + 该分支的增量节点）
- 删除分支：单条删除，会确认
- "迁移"：把旧版（v0.5 之前的快照式）分支批量转换为新版 DAG 增量格式

#### 6.7.2 分支横幅

进入非主干分支时，画布顶部出现金色横幅："当前查看推演分支 · 可右键节点从此再次分叉"，一键返回主干。

#### 6.7.3 分支对比

顶栏 "⚖ 对比" 按钮（节点数 ≥ 2 个分支时可用）：

- 弹出对话框选择 A、B 两个分支
- 展示节点 / 边的并集，标注「仅 A」、「仅 B」、「共有」
- 用于评估不同假设的差异

---

### 6.8 文档导入 (PDF / 图片)

文件：`ImportDialog.vue`、`useImportFlow.ts`、`OntologyModelController.extract`、`DocumentExtractionService.java`、`PdfTextExtractor.java`

#### 6.8.1 支持来源

- PDF（后端用 PDFBox 抽取每页文字，超长会切片；扫描件自动回落到逐页渲染走视觉抽取）
- DOCX（Word 文档，用 Apache POI 抽取段落 + 表格；表格保留二维结构以 markdown 形式）
- 图片（PNG/JPEG/WebP/GIF，作为多模态 attachment 给 LLM）
- 文本类（直接 base64 / utf-8 拼入 prompt）

#### 6.8.2 流程

1. 拖入或选择文件 → 上传到 `/api/ontology-models/extract`（multipart）
2. 后端按文件类型走不同管线：PDF → 文本；图片/其它 → 二进制 attachment
3. LLM 用 `EXTRACT_SYSTEM` prompt 抽取实体/关系，强调"以文档为准、不要虚构"
4. 抽取结果回到前端 `ImportDialog`，可预览
5. 用户选择：
   - **合并到当前模型** — 增量加入主干，自动避开现有节点位置
   - **另存为新模型** — 新建一个 OntologyModel

#### 6.8.3 限制

| 参数 | 默认 |
|---|---|
| 单文件 | ≤ 20 MB（PDF/DOCX 各自单独再限 12 MB；图片 8 MB） |
| 单次请求 | ≤ 80 MB（spring multipart） |
| 单次上传文件数 | ≤ 8 |
| 抽取文本预算 | PDF / DOCX 各自 60K 字，超出截断 |
| LLM 上下文 | 由配置的 `context-window` 决定，超长会截断并提示 |

---

### 6.9 假设模板

文件：`HypothesisTemplateController.java`、`PredictDialog.vue` 中"加载模板"

把一次推演的参数（intent / seeds / steps / prompt / constraints）保存为模板，下次可一键复用：

- 列表：`GET /api/hypothesis-templates?modelId=`
- 保存：`POST /api/hypothesis-templates`
- 标记使用（提到列表前端）：`POST /{id}/touch`
- 删除：`DELETE /{id}`

加载时如果原始 seed 节点已被删除，会自动跳过并 toast 提示"已加载 X/Y 个起点"。

---

### 6.10 多模型 LLM 配置

文件：`SettingsView.vue`、`ConfigController.java`、`LlmService.java`、`LlmProperties.java`

#### 6.10.1 配置项

| 字段 | 含义 |
|---|---|
| `id` | 唯一标识 |
| `provider` | `openai` / `anthropic` / `deepseek` / 自定义 |
| `protocol` | `openai` 或 `anthropic`（决定请求体格式） |
| `base-url` | API 网关 |
| `model-name` | 模型 ID |
| `api-key` | 鉴权 |
| `context-window` | 总上下文 token 上限 |
| `max-output-tokens` | 生成长度 |
| `capabilities` | `streaming` / `json` / `vision` 等 |
| `enabled` | 是否在对话框模型选择器中可见 |

#### 6.10.2 默认配置

`application.yml` 预置了 `deepseek-chat`（DeepSeek V4 Flash，OpenAI 兼容协议）。可通过环境变量 `DEEPSEEK_API_KEY` 注入 key。

#### 6.10.3 运行时切换

对话框头部模型下拉可即时切换。每条请求支持 `configId`（指定预置）或 `modelOverride`（覆盖模型名）。

#### 6.10.4 协议适配

`LlmService` 内置 OpenAI 与 Anthropic 两套请求/响应/流式解析：

- OpenAI 协议：`/v1/chat/completions`，流式格式 `data: {...}`
- Anthropic 协议：`/v1/messages`，流式事件 `event: content_block_delta` 等
- 自动按 `base-url` 与 `model-name` 判断走哪一套

---

### 6.11 对话历史

文件：`ConversationController.java`、`useConversations.ts`、`Sidebar.vue` 中"历史对话"区域

- 每个本体模型独立一组对话
- 自动以首条用户消息生成标题（前 ~16 字）
- 左侧栏列出最近对话，支持删除
- 本地浏览器 + 后端双向持久化
- 切换会话会中止当前 SSE 流

---

### 6.12 导出与共享

| 入口 | 行为 |
|---|---|
| 顶栏「导出」 | 下载当前图谱为 JSON（含元信息、所有节点、所有边、导出时间戳） |
| 顶栏「共享」 | 把图谱摘要（标题 + 节点数 + 前 10 个节点） 写入剪贴板；不支持剪贴板时打到 console |

JSON 导出文件名按标题安全化，非汉字/字母数字会替换为 `_`。

---

### 6.13 用户偏好设置

文件：`PrefsController.java`、`useSettingsPrefs.ts`

存储在 `~/.tuiyan/prefs.json`，包括：

- 默认 LLM
- 默认布局方向
- 自动布局开关
- 一键清空所有分支：`DELETE /api/prefs/scenarios`

---

## 7. 数据存储

后端无数据库，全部落地到 `~/.tuiyan/`（可通过 `app.data.dir` 改）。

```
~/.tuiyan/
├── ontology-models/      # 一个模型一个 JSON
│   └── om_xxx.json
├── scenarios/            # 推演分支
│   └── sc_xxx.json
├── conversations/        # 对话历史
│   └── cv_xxx.json
├── hypothesis-templates/ # 假设模板
│   └── ht_xxx.json
└── prefs.json            # 全局偏好
```

写盘使用 `JsonAtomic` 原子写（先写 `.tmp` 再 rename），避免半截文件。

---

## 8. REST API 一览

| 方法 & 路径 | 用途 |
|---|---|
| `POST /api/chat` | 对话 + 图谱增量（`Accept: application/json` 同步；`Accept: text/event-stream` 流式 SSE） |
| `GET /api/ontology-models` | 列出本体模型 |
| `GET /api/ontology-models/{id}` | 取单个模型 |
| `POST /api/ontology-models` | 新建 |
| `PUT /api/ontology-models/{id}` | 更新 |
| `DELETE /api/ontology-models/{id}` | 删除 |
| `POST /api/ontology-models/extract` | 上传文档抽取（multipart） |
| `GET /api/scenarios?modelId=` | 列出分支 |
| `GET /api/scenarios/{id}` | 取单个分支 |
| `POST /api/scenarios` | 推演（SSE 流式） |
| `DELETE /api/scenarios/{id}` | 删除分支 |
| `POST /api/scenarios/migrate` | 旧版分支批量迁移 |
| `GET /api/conversations` | 列出对话 |
| `GET /api/conversations/{id}` | 单个对话 |
| `POST /api/conversations` | 新建对话 |
| `PUT /api/conversations/{id}` | 更新对话 |
| `DELETE /api/conversations/{id}` | 删除对话 |
| `GET /api/hypothesis-templates?modelId=` | 列出模板 |
| `POST /api/hypothesis-templates` | 新建/更新模板 |
| `POST /api/hypothesis-templates/{id}/touch` | 标记为最近使用 |
| `DELETE /api/hypothesis-templates/{id}` | 删除 |
| `GET /api/models` | 列出可用 LLM 配置 |
| `GET /api/config` | 取前端可见的应用配置 |
| `GET /api/prefs` | 取用户偏好 |
| `PUT /api/prefs` | 更新偏好 |
| `DELETE /api/prefs/scenarios` | 一键清空所有分支 |

#### SSE 事件类型

`/api/chat`：

| event | data |
|---|---|
| `text` | 模型流式文本 chunk |
| `complete` | `{ reply, add_nodes, add_edges }` |
| `error` | 错误描述 |

`/api/scenarios`（推演）：

| event | data |
|---|---|
| `step` | 单步推演结果 |
| `prune` | 被剪枝的候选及原因 |
| `complete` | 最终 `Scenario` 对象 |
| `error` | 错误 |

---

## 9. 快捷键

> 在图谱视图（`view === 'graph'`）下生效；输入框内不抢键。

| 快捷键 | 操作 |
|---|---|
| `Ctrl/⌘ + Z` | 撤销 |
| `Ctrl/⌘ + Shift + Z` 或 `Ctrl + Y` | 重做 |
| `Ctrl/⌘ + 滚轮` | 缩放画布 |
| `Enter` | 发送对话（输入框内） |
| `Shift + Enter` | 输入框换行 |
| `@` | 触发节点/关系引用下拉 |
| `↑ / ↓` | 在 `@` 下拉中切换候选 |
| `Esc` | 关闭弹窗 / 上下文菜单 / `@` 下拉 |

---

## 10. 配置与环境变量

### 10.1 后端 `application.yml`

```yaml
server:
  port: 8000             # 后端端口

app:
  data:
    dir: ${user.home}/.tuiyan   # 数据目录
  llm:
    models:              # 可配多个 LLM
      - id: deepseek-chat
        provider: deepseek
        base-url: https://api.deepseek.com
        model-name: deepseek-v4-flash
        api-key: ${DEEPSEEK_API_KEY:...}
        protocol: openai
        enabled: true
        context-window: 64000
        max-output-tokens: 8192

spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 80MB
```

### 10.2 环境变量

| 变量 | 作用 |
|---|---|
| `DEEPSEEK_API_KEY` | 注入默认 DeepSeek 模型 key |
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | 在 `application.yml` 中以 `${...}` 占位即可生效 |
| `APP_DATA_DIR` | 通过 `--app.data.dir=` 或环境变量覆盖数据目录 |

### 10.3 前端

Vite 配置 (`vite.config.ts`) 中把 `/api/*` 代理到 `http://localhost:8000`。如部署到不同主机，可改代理目标，或在生产环境用 Nginx 反向代理。

---

## 附录 A：节点 / 边类型颜色

| 类型 | 颜色 | 含义 |
|---|---|---|
| `entity` | 蓝 `#3d9bff` | 实体（人、物、组织） |
| `process` | 橙 `#ffaa22` | 流程（动作、操作） |
| `event` | 绿 `#22dd88` | 事件（具体一次发生） |
| `data` | 紫 `#bb77ff` | 数据源 |
| `external` | 红 `#ff6644` | 外部系统 |
| `rule` | 粉 `#ff3399` | 规则 / 条件 |

边：

| 视觉 | 含义 |
|---|---|
| 半透明白 | 普通 inferred / derived |
| 粉色 | `rule_driven` |
| 绿色高亮 + 流光加速 | 与选中节点关联 |
| 金色虚线 + ◇ 标记 | 推演新增（`source: 'predicted'`） |

---

## 附录 B：术语缩写

| 缩写 | 含义 |
|---|---|
| SSE | Server-Sent Events，单向流式 HTTP |
| DAG | Directed Acyclic Graph，有向无环图 |
| trunk | 主干分支（即模型本身的"现实"状态） |
| seed | 推演起点节点 |
| chain | 推演产生的因果链 |
| LR / TB | 布局方向：Left→Right / Top→Bottom |

---

*文档版本：v1.0 · 与代码同步对应分支：`claude/dreamy-einstein-hrn9j`*
