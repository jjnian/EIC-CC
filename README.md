# 推演平台 (EIC-CC)

> **基于本体图谱的 AI 因果推演系统**：通过自然语言对话构建知识图谱，沿因果链做正向预测或反向溯因，支持多分支假设对比。

![status](https://img.shields.io/badge/status-active-success) ![license](https://img.shields.io/badge/license-internal-blue) ![java](https://img.shields.io/badge/java-17-orange) ![vue](https://img.shields.io/badge/vue-3.5-brightgreen)

---

## 目录

- [一、项目概述](#一项目概述)
- [二、快速开始](#二快速开始)
- [三、系统架构与技术栈](#三系统架构与技术栈)
- [四、核心概念](#四核心概念)
- [五、功能模块](#五功能模块)
  - [5.1 本体模型管理](#51-本体模型管理)
  - [5.2 AI 对话建模](#52-ai-对话建模)
  - [5.3 图谱可视化与交互](#53-图谱可视化与交互)
  - [5.4 撤销 / 重做 / 历史](#54-撤销--重做--历史)
  - [5.5 节点详情与 Schema](#55-节点详情与-schema)
  - [5.6 文档导入](#56-文档导入)
  - [5.7 假设模板](#57-假设模板)
  - [5.8 多 LLM 配置](#58-多-llm-配置)
  - [5.9 对话历史与数据源回看](#59-对话历史与数据源回看)
  - [5.10 导出与共享](#510-导出与共享)
- [六、🚀 因果推演（详细用法）](#六-因果推演详细用法)
- [七、🌿 分支假设（详细用法）](#七-分支假设详细用法)
- [八、数据存储](#八数据存储)
- [九、REST API 一览](#九rest-api-一览)
- [十、快捷键](#十快捷键)
- [十一、配置与环境变量](#十一配置与环境变量)
- [附录 A：节点 / 边视觉编码](#附录-a节点--边视觉编码)
- [附录 B：术语缩写](#附录-b术语缩写)

---

## 一、项目概述

**推演平台** 是一个面向因果推理的 AI 辅助本体建模工具。核心能力：

| 能力 | 说明 |
|---|---|
| **本体建模** | 用自然语言或导入文档（PDF / DOCX / 图片），自动抽取实体、流程、事件、规则及其关系，构建有向图谱 |
| **因果推演** | 在已有图谱上以选定节点为起点（或终点），由 LLM 沿因果链做多步正向 / 反向推演 |
| **分支假设** | 每一次推演形成独立的"假设分支"，可在主干与多个分支之间切换、对比 |
| **多源融合** | 支持上传 PDF、DOCX、图片、Markdown、文本、CSV 等结构化 / 非结构化资料；对话框可直接粘贴图片 |
| **可解释性** | 节点 / 边都标注来源（derived / inferred / predicted），便于审计 |
| **可回溯** | 完整撤销 / 重做历史；对话里每条上传文件都可重新查看 |

适用场景：供应链推演、流程审计、事故复盘、规则建模、知识资产沉淀等。

---

## 二、快速开始

### 2.1 依赖

- JDK 17+，Maven 3.8+
- Node.js 18+，npm
- LLM API Key（默认配置 DeepSeek，可在设置中替换为 OpenAI / Anthropic / 自建兼容端点）

### 2.2 一键启动

```bash
./start.sh
```

脚本会同时启动：

- 后端：`http://localhost:8000`
- 前端：`http://localhost:5173`

`Ctrl+C` 终止两个进程。

### 2.3 单独启动

```bash
# 后端
cd backend && mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev
```

### 2.4 配置 LLM Key

两种方式任选其一：

1. **环境变量**：`export DEEPSEEK_API_KEY=sk-xxxx`
2. **Web 设置页**：左侧导航进入「设置」，添加自定义模型配置（base URL、API Key、协议、上下文窗口等）

---

## 三、系统架构与技术栈

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
│  │  ChatController       — 对话 + 图谱增量          │   │
│  │  ScenarioController   — 推演与分支               │   │
│  │  OntologyModelController — 本体 CRUD + 抽取      │   │
│  │  DocumentTextController  — DOCX 抽文本           │   │
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

| 层 | 技术 |
|---|---|
| **前端** | Vue 3.5（Composition API + `<script setup>`）、TypeScript 5.8、Vite 6 |
| **后端** | Java 17、Spring Boot 3、Spring Web、Jackson、Java `HttpClient` |
| **LLM 协议** | OpenAI 兼容 / Anthropic（streaming + JSON 输出） |
| **文档解析** | Apache PDFBox（PDF）、Apache POI 5.2（DOCX） |
| **存储** | 本地 JSON 文件（`~/.tuiyan/`） |
| **通信** | HTTP + SSE（流式生成 / 推演实时回执） |

---

## 四、核心概念

| 概念 | 说明 |
|---|---|
| **本体模型 (OntologyModel)** | 一个图谱画布，包含 nodes / edges 与元信息 |
| **节点类型** | `entity`（实体）、`process`（流程）、`event`（事件）、`data`（数据源）、`external`（外部系统）、`rule`（规则/条件） |
| **边来源 (source)** | `derived`（文本明示）、`inferred`（模型推断填充）、`predicted`（推演生成）、`preset`（预置）|
| **rule_driven 边** | 由 rule 节点驱动的关系，画布中以粉色显示 |
| **主干 (trunk)** | 本体模型的"真实"图谱状态 |
| **分支 (branch)** | 一次推演的快照，挂在主干或其它分支下，仅增量记录预测新增的节点/边 |
| **推演意图 (intent)** | `forward`（从原因到结果）或 `backward`（从结果反推原因） |
| **约束 (constraint)** | 推演前指定某节点 `force`（必然发生）或 `block`（不会发生），用于剪枝 |

---

## 五、功能模块

### 5.1 本体模型管理

| 功能 | 入口 | 说明 |
|---|---|---|
| 创建模型 | 「列表」页 → "新建模型" / 欢迎页对话提交 | 自动生成默认标题，创建后跳转到画布 |
| 列出模型 | 左侧栏 / "列表" 页 | 卡片形式显示节点 / 关系统计与更新时间 |
| 打开模型 | 点击模型卡片或左侧条目 | 加载 nodes / edges 并重置历史栈 |
| 删除模型 | 卡片右上角 "×" | 二次确认，同时清理对应分支 |
| 自动保存 | 任何编辑触发 | 1.2 s 防抖 PUT 到后端；切换 / 撤销时立即保存 |

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

---

### 5.2 AI 对话建模

#### 5.2.1 输入方式

| 方式 | 操作 |
|---|---|
| 文字 | 直接输入；`@` 触发节点/边引用下拉 |
| 上传文件 | 点击 📎 选择 — 支持图片 / DOCX / MD / TXT / JSON / CSV / 源代码等 |
| **粘贴图片** | 在输入框 `Ctrl/⌘+V` 直接粘贴截图或剪贴板图片 |
| 拖拽文件 | 拖入对话面板 |

#### 5.2.2 流程

```
用户输入  ──►  ChatPanel.send()
                │
                ├─ 收集附件
                │   ├─ 图片  → dataUrl 多模态 attachment
                │   ├─ DOCX  → POST /api/extract/docx-text 取纯文本
                │   └─ 文本  → 直接拼到 prompt
                ├─ 拼装 history（最近 40 条）
                ├─ 附带当前图谱摘要（nodes / edges 精简版）
                ▼
        POST /api/chat (Accept: text/event-stream)
                │
                ├─ event: text       — 模型 token 流
                ├─ event: complete   — { reply, add_nodes, add_edges }
                └─ event: error
                ▼
        前端 dedupeIncoming → history.snapshot() → 落图
```

#### 5.2.3 增量扩展与去重

每次发请求都带上当前图谱摘要，后端在 prompt 前注入：

> _"Existing ontology graph (the user is incrementally extending this — do NOT recreate any of these; reuse the ids exactly when you need to reference them)…"_

前端再做 **两层防御性去重**：

1. **按 id**：返回中已存在的 id 直接丢弃
2. **按 `label + type`**：命中现有节点，则记入 idRemap；LLM 输出的 edges 引用重写到旧 id
3. **edges 二次去重**：`(from, to, label)` 三元组命中则丢弃
4. 全部被去掉时 toast 提示 "已忽略 N 个重复节点 / M 条重复关系"

#### 5.2.4 流式控制

- 生成中，发送按钮自动变为白底方形**停止按钮**，外圈带绿色旋转环
- 点击停止：中断 SSE，AI 占位气泡更新为"已停止生成。"
- 切换会话 / 卸载组件时自动中止当前流

---

### 5.3 图谱可视化与交互

| 操作 | 行为 |
|---|---|
| 拖动空白区 | 平移画布 |
| `Ctrl/⌘ + 滚轮` | 以鼠标为锚点缩放（0.1× ~ 3×） |
| 点击节点 | 选中 + 高亮相关边 |
| 拖动节点 | 移动节点（拖拽开始时触发一次历史快照） |
| 右键节点 | 弹出"从此推演"上下文菜单 |
| 点击空白 | 取消选中 |

#### 顶部工具栏（左 → 右）

```
[↶ 撤销] [↷ 重做]   [分支选择]   [⚖ 对比] [📥 导入]   [● N 节点] [M 关系]   [Schema] [导出] [共享]
```

#### 画布动作浮条（顶部居中）

- **适应屏幕** — 自动 fit-view
- **自动布局** — 分层 DAG 布局（Sugiyama 风格），处理回边与孤立节点
- **布局方向** — 「从左向右 (LR)」 ↔ 「从上到下 (TB)」 切换
- **清空画布** — 二次确认后清空（产生一次撤销快照）

#### 图例（右上角）— 类型筛选

每行对应一种节点类型。**点击图例项作为筛选器：**

- 第一次点击：高亮该类型节点，其它节点和不相关连线统一变暗 (opacity 0.2 / 0.15)
- 再次点击同一项：清除筛选
- 点击其它类型：切换筛选目标

---

### 5.4 撤销 / 重做 / 历史

| 项 | 值 |
|---|---|
| 最大历史条数 | 50 |
| 触发快照 | LLM 增量更新、清空画布、自动布局、合并导入、节点拖拽（每次拖拽只一次） |
| 撤销 | 顶栏 ↶ / `Ctrl/⌘ + Z` |
| 重做 | 顶栏 ↷ / `Ctrl/⌘ + Shift + Z` / `Ctrl + Y` |
| 输入框内 | 不抢键盘 |
| 切换模型 | 历史栈重置 |
| 同状态去重 | 内容一致的连续快照只保留一份 |
| 撤销后落盘 | 立即 `persistCurrentModel(true)`，不走防抖 |

栈语义：经典 undo —— 处于历史中间时再做新改动会丢弃 redo 分支。

---

### 5.5 节点详情与 Schema

- 选中节点后，右侧抽屉显示：节点元数据、属性表 (`props[]`)、入边 / 出边列表（点击跳转）、推演置信度（若为预测节点）
- 顶栏 "Schema" 按钮：切换模式视图，列出当前图谱里每种 type 的属性合集

---

### 5.6 文档导入

#### 5.6.1 支持来源

- **PDF**：PDFBox 抽每页文字；扫描件自动回落到逐页渲染走视觉抽取
- **DOCX**：Apache POI 抽取段落 + 表格；表格保留二维结构（markdown-ish）
- **图片**（PNG / JPEG / WebP / GIF）：作为多模态 attachment 给 LLM
- **文本类**：直接拼入 prompt

#### 5.6.2 流程

1. 拖入或点击选择文件 → 上传到 `/api/ontology-models/extract`（multipart）
2. 后端按文件类型走管线：PDF→ 文本（或渲染）、DOCX→ 文本、图片 → 二进制 attachment
3. LLM 用 `EXTRACT_SYSTEM` prompt 抽取实体 / 关系，强调"以文档为准、不要虚构"
4. 抽取结果回到 `ImportDialog`，可预览
5. 用户选择：
   - **合并到当前模型** — 增量加入主干
   - **另存为新模型** — 新建一个 OntologyModel

#### 5.6.3 限制

| 参数 | 默认 |
|---|---|
| 单文件 | PDF / DOCX ≤ 12 MB；图片 ≤ 8 MB |
| 单次请求 | ≤ 80 MB（spring multipart） |
| 单次上传文件数 | ≤ 8 |
| 抽取文本预算 | PDF / DOCX 各自 60K 字，超出截断 |

---

### 5.7 假设模板

把一次推演的参数（intent / seeds / steps / prompt / constraints）保存为模板，下次一键复用。

- 列表：`GET /api/hypothesis-templates?modelId=`
- 保存 / 加载：在 PredictDialog 内左侧"模板"面板
- 标记为最近：`POST /{id}/touch`
- 加载时如果原始 seed 节点已被删除，会自动跳过并提示"已加载 X/Y 个起点"

---

### 5.8 多 LLM 配置

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

`application.yml` 预置了 `deepseek-chat`（OpenAI 兼容协议）。对话框头部模型下拉可即时切换。每条请求支持 `configId`（指定预置）或 `modelOverride`（覆盖模型名）。`LlmService` 内置 OpenAI 与 Anthropic 两套请求 / 响应 / 流式解析。

---

### 5.9 对话历史与数据源回看

- 每个本体模型独立一组对话
- 自动以首条用户消息生成标题（前 ~16 字）
- 左侧栏列出最近对话，支持删除
- 本地浏览器 + 后端双向持久化
- **数据源回看**：每条用户消息上的附件 chip 可点击，弹出预览：
  - 图片：内联 `<img>` 显示
  - 文本 / DOCX 提取文本：等宽字体显示，支持复制 / 下载
  - 体积阈值：文本 ≤ 100KB、图片 dataUrl ≤ 2MB；超出的只保留元信息

---

### 5.10 导出与共享

| 入口 | 行为 |
|---|---|
| 顶栏「导出」 | 下载当前图谱为 JSON（含元信息、所有节点 / 边、导出时间戳） |
| 顶栏「共享」 | 把图谱摘要（标题 + 节点数 + 前 10 个节点）写入剪贴板 |

---

## 六、🚀 因果推演（详细用法）

> 因果推演 = 让 AI 沿现有图谱中的因果链 **接着往下走**（正向 / forward），或者 **倒着往回找**（反向 / backward）。每次推演都会生成一个新的"分支"，不会污染主干。

### 6.1 它能解决什么问题？

| 场景 | 选用 |
|---|---|
| "如果原料价格上涨 20%，下游会怎样？" | **forward** —— 从「原料涨价」节点向前推演影响 |
| "客户突然大量流失，可能是什么导致的？" | **backward** —— 从「客户流失」节点向上溯因 |
| "假设供应商 A 全面罢工，再叠加规则 R3 生效..." | forward + 多 seeds + force 约束 |
| "把 X 节点排除后，因果链会怎么改？" | forward + block 约束 |

### 6.2 启动推演 — 两种入口

#### 方式 A：右键节点

1. 在画布上**右键**点击你要作为起点的节点
2. 弹出菜单选 **"⚡ 从此推演 (Forward)"**
3. 该节点自动填到推演对话框的 seeds

#### 方式 B：手动添加

1. 打开 PredictDialog（如已有 seed，从画布右键即可）
2. 在「起点节点」栏的搜索框输入名称，点击候选添加
3. 可同时添加多个 seed（共同作为起点 / 共同作为目标结果）

### 6.3 配置参数

PredictDialog 自上而下：

#### ① 推演意图（Forward / Backward）

| | Forward（正向推演） | Backward（溯因） |
|---|---|---|
| seed 含义 | 起点（原因） | 目标（结果） |
| 输出 | 该节点之后会发生什么 | 该节点之前可能由什么导致 |
| step 含义 | 推演步数 | 溯因层数 |
| 适用 | "如果 X 发生，接下来..." | "X 已发生，可能是因为..." |

切换意图会清空 constraints（语义不同）。

#### ② seeds（起点 / 目标）

- 可以多个节点共同作为 seeds
- 每个 seed chip 可点 `×` 移除
- 提示文案随 intent 切换：「起点节点 (seeds)」 / 「目标节点 (结果)」

#### ③ 步数 / 层数

- 默认 4
- 通常 3~6 步效果最好
- 太多步骤模型会发散；超出会截断

#### ④ 自然语言场景描述（prompt）— 可选但强烈推荐

把"假设"写成一句话提示模型，比如：

- "假设主供应商断货 3 天，叠加旺季订单激增 30%"
- "考虑监管新规 R3 已生效"
- "排除 ERP 升级带来的临时影响"

模型会用这段描述作为约束条件来生成因果链。

#### ⑤ 分支名 — 可选

不填则后端自动按时间戳生成。建议填一句短描述（如「供应商断货-旺季」），方便日后切换。

#### ⑥ 约束（constraints） — 可选

点击「+ 约束节点」展开，可对画布上任意节点设两种约束：

| 模式 | 语义 |
|---|---|
| **force**（必然） | 该节点一定发生 / 一定存在；提高其下游事件先验，常用于"叠加假设" |
| **block**（禁止） | 该节点不会发生；推演时凡是依赖它的预测都会被剪枝 |

点击 chip 可在 force / block 间切换。

**约束冲突检测**：UI 会扫描 force 与 block 之间的直接因果边，发现冲突时显示橙色警告，例如：

> ⚠ 「主供应商断货」(必然) → 「订单交付」(禁止)：存在直接因果关系

允许提交，但提示你这种组合可能矛盾。

#### ⑦ 加载 / 保存模板 — 可选

左下角 📁 切到"模板"面板：

- 输入名称 → 「保存为模板」把当前所有参数存起来
- 列表点击即可加载已有模板
- 模板按"最近使用"排序

### 6.4 执行与对话面板内的实时步骤

1. 点击右下角 **「开始推演」** / 「开始溯因」
2. 对话框关闭，**右侧对话面板里新增一条「⚡ 推演」消息**（金色边框），整轮推演的过程会写在这条消息内
3. 推演通过 SSE 流式返回，每一步实时追加到这条消息上：

```
┌── 步 1 ──────────────────────────────┐
│ ● 库存吃紧                          │
│   置信度: 0.78                       │
│   ↑ 由「主供应商断货」(seed) 触发     │
│   规则: R3-缓冲库存阈值              │
│   解释: 当前缓冲 < 3 天阈值          │
└──────────────────────────────────────┘
                ↓
┌── 步 2 ──────────────────────────────┐
│ ● 紧急调拨启动                       │
│   置信度: 0.65                       │
│   ↑ 由「库存吃紧」触发                │
│   …                                  │
```

推演消息功能：

- **置信度过滤滑块**（消息底部，≥ 2 步时出现）：拖动可隐藏低置信度的预测步
- **点击步骤卡片** → 画布自动 focus 对应节点
- **点击起点 chip / triggered_by id** → 同样可聚焦相关节点
- **剪枝报告**：列出因为 block 约束被剪掉的候选，及剪掉理由
- **「停止」按钮**（运行中显示）：中止推演，消息状态置为「已停止」
- **推演消息会沉到对话历史里**：往上滚仍能看到任意一次旧推演的步骤、起点与剪枝情况，且支持持久化（与对话一并保存到 `~/.tuiyan/conversations/*.json`）

### 6.5 推演结果落图

推演完成后，**新增节点 / 边自动加入图谱**（标记 `source: 'predicted'`），视觉表现：

- 节点右上角带"预测 N"角标
- 边用 **金色虚线 + ◇ 标记** 渲染
- 当前画布所属"分支"切换为新生成的分支（顶栏出现金色横幅）

### 6.6 评估与调整

时间轴上每一步都带 confidence。一般经验：

| confidence | 含义 |
|---|---|
| ≥ 0.7 | 模型相当笃定，有规则或上下文支撑 |
| 0.4 ~ 0.7 | 合理推断，但存在多种可能 |
| < 0.4 | 弱推断，建议用 prompt / 约束细化后重跑 |

调整后重跑通常会生成新的分支，而不是覆盖。

---

## 七、🌿 分支假设（详细用法）

> 分支假设 = 把每一次推演都当成"平行宇宙"。主干 (trunk) 永远是你确认的现实图谱；分支 (branch) 是各种"如果 …" 的快照，可以无限分裂、对比、丢弃。

### 7.1 心智模型

```
   trunk (主干 - 真实图谱)
      │
      ├─── branch A: "供应商断货-旺季"   (从 trunk 推演)
      │       │
      │       └─── branch A1: "断货+物流瘫痪"  (从 A 再分叉)
      │
      ├─── branch B: "原料涨价 20%"      (从 trunk 推演)
      │
      └─── branch C: "新规 R3 上线"      (溯因从 trunk 推演)
```

- 每个 branch 通过 `parentBranchId` 指向父分支或 trunk
- branch 只存"在父分支基础上 **新增** 了什么"，节省存储
- 切换分支时，前端沿祖先链回溯并合并所有增量，重建完整画布

### 7.2 创建分支

分支不需要手动创建 —— **每次推演完成后自动生成一个分支**，名字来自 PredictDialog 的「分支名」字段（不填则按时间戳）。

唯一的额外操作：在分支视图下再次右键节点 → 「从此推演」，会以**当前分支**为 `parentBranchId`，产生子分支。

### 7.3 切换分支

顶栏左侧的 **分支选择器**（下拉）：

- 第一项永远是 **主分支 (trunk)**
- 下面按创建时间倒序列出所有分支
- 点击某项 → 画布立刻刷新为该分支的合并视图
- 切换时若有正在跑的推演 SSE 流，会自动中止

#### 分支横幅

进入非主干分支时，画布顶部出现金色横幅：

> ⚡ **当前查看推演分支** · 可右键节点从此再次分叉   [返回主分支]

点击"返回主分支"按钮可一键切回 trunk。

### 7.4 在分支上继续推演

这是分支系统的核心用法 —— **叠加假设**：

1. 切到 branch A（"供应商断货-旺季"）
2. 此时画布显示 trunk + A 的增量节点（金色）
3. 右键某节点 → 「从此推演」
4. 在 PredictDialog 里填新的 prompt（如"再叠加物流瘫痪 24h"）
5. 推演完成 → 自动生成 **branch A1**，挂在 A 之下

这样可以层层堆叠假设，每一层都可独立切换查看，互不污染。

### 7.5 对比分支

顶栏「**⚖ 对比**」按钮（**分支数 ≥ 2 时可用**）：

1. 弹出 BranchCompareDialog
2. 在左右两个下拉选择 A、B 两个分支（或 trunk）
3. 对比视图展示三段：

| 段 | 内容 |
|---|---|
| **仅 A** | A 有而 B 没有的节点 / 边 |
| **共有** | 两边都存在的（取交集） |
| **仅 B** | B 有而 A 没有的节点 / 边 |

适合做"假设 X 和假设 Y 哪些路径会重叠？哪些是各自独有的？"这种分析。

### 7.6 删除分支

分支选择器里每一项右侧有 `×` 按钮（hover 显示），点击 → 二次确认 → 删除该分支。

注意：
- **trunk 不可删**
- 删父分支不会自动级联删子分支，但子分支的祖先链会断裂，可能显示异常 — 实际操作中先删叶节点

### 7.7 旧分支迁移

如果是从 v0.5 之前升级上来，旧分支以"完整快照"形式落盘。分支选择器下拉里有「**迁移旧分支**」入口，会自动把它们转换为新的 DAG 增量格式（仅保留预测增量），节省存储。

### 7.8 一键清空所有分支

`DELETE /api/prefs/scenarios`（前端入口在「设置」页面）—— 慎用，会清空所有模型下的所有分支。

### 7.9 典型工作流

**场景：评估三种应急预案**

```
1. 在 trunk 上建好基线供应链图谱
2. 推演分支 A："启动 B 级应急预案"  → 看交付率影响
3. 推演分支 B："启动 A 级应急预案"  → 看交付率影响
4. 推演分支 C："不响应"             → 作为对照
5. 用「⚖ 对比」分别看 A vs C 和 B vs C 的差异路径
6. 选定最优方案后,可以把 winning 分支里的关键边/规则
   通过「另存为新模型」沉淀成新的本体模型（v1.1 计划）
```

---

## 八、数据存储

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

## 九、REST API 一览

| 方法 & 路径 | 用途 |
|---|---|
| `POST /api/chat` | 对话 + 图谱增量（`Accept: application/json` 同步；`Accept: text/event-stream` 流式 SSE） |
| `GET /api/ontology-models` | 列出本体模型 |
| `GET /api/ontology-models/{id}` | 取单个模型 |
| `POST /api/ontology-models` | 新建 |
| `PUT /api/ontology-models/{id}` | 更新 |
| `DELETE /api/ontology-models/{id}` | 删除 |
| `POST /api/ontology-models/extract` | 上传文档抽取（multipart，PDF / DOCX / 图片） |
| `POST /api/extract/docx-text` | DOCX → 纯文本（chat 上传 docx 用） |
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
| `POST /api/hypothesis-templates` | 新建 / 更新模板 |
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

## 十、快捷键

> 在图谱视图（`view === 'graph'`）下生效；输入框内不抢键。

| 快捷键 | 操作 |
|---|---|
| `Ctrl/⌘ + Z` | 撤销 |
| `Ctrl/⌘ + Shift + Z` 或 `Ctrl + Y` | 重做 |
| `Ctrl/⌘ + 滚轮` | 缩放画布 |
| `Enter` | 发送对话（输入框内） |
| `Shift + Enter` | 输入框换行 |
| `Ctrl/⌘ + V`（输入框内） | 粘贴图片附件 |
| `@` | 触发节点 / 关系引用下拉 |
| `↑ / ↓` | 在 `@` 下拉中切换候选 |
| `Esc` | 关闭弹窗 / 上下文菜单 / `@` 下拉 |

---

## 十一、配置与环境变量

### 11.1 后端 `application.yml`

```yaml
server:
  port: 8000                       # 后端端口

app:
  data:
    dir: ${user.home}/.tuiyan      # 数据目录
  llm:
    models:                        # 可配多个 LLM
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

### 11.2 环境变量

| 变量 | 作用 |
|---|---|
| `DEEPSEEK_API_KEY` | 注入默认 DeepSeek 模型 key |
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | 在 `application.yml` 中以 `${...}` 占位即可生效 |
| `APP_DATA_DIR` | 通过 `--app.data.dir=` 或环境变量覆盖数据目录 |

### 11.3 前端

Vite 配置 (`vite.config.ts`) 把 `/api/*` 代理到 `http://localhost:8000`。如部署到不同主机，可改代理目标，或在生产环境用 Nginx 反向代理。

---

## 附录 A：节点 / 边视觉编码

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
| force / block | 推演约束：必然 / 禁止 |

---

*文档版本：v1.1 · 与代码同步对应分支：`claude/dreamy-einstein-hrn9j`*
