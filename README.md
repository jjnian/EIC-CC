# 推演平台 (EIC-CC)

> **基于本体图谱的 AI 因果推演系统**：通过自然语言对话构建知识图谱，沿因果链做正向预测或反向溯因，支持多分支假设对比。

![status](https://img.shields.io/badge/status-active-success) ![license](https://img.shields.io/badge/license-internal-blue) ![java](https://img.shields.io/badge/java-17-orange) ![vue](https://img.shields.io/badge/vue-3.5-brightgreen)

---

## 目录

- [一、项目概述](#一项目概述)
- [二、快速开始](#二快速开始)
- [三、系统架构与技术栈](#三系统架构与技术栈)
- [四、核心概念](#四核心概念)
- [五、功能模块总览](#五功能模块总览)
  - [5.1 本体模型管理](#51-本体模型管理)
  - [5.2 AI 对话建模](#52-ai-对话建模)
  - [5.3 图谱可视化与交互](#53-图谱可视化与交互)
  - [5.4 图谱搜索与定位](#54-图谱搜索与定位)
  - [5.5 节点编辑与删除](#55-节点编辑与删除)
  - [5.6 批量节点操作](#56-批量节点操作)
  - [5.7 撤销 / 重做 / 历史](#57-撤销--重做--历史)
  - [5.8 节点详情与 Schema](#58-节点详情与-schema)
  - [5.9 文档导入与实体抽取](#59-文档导入与实体抽取)
  - [5.10 图谱模板库](#510-图谱模板库)
  - [5.11 假设模板](#511-假设模板)
  - [5.12 多 LLM 配置与连接测试](#512-多-llm-配置与连接测试)
  - [5.13 对话历史与数据源回看](#513-对话历史与数据源回看)
  - [5.14 导出与共享](#514-导出与共享)
  - [5.15 版本管理与回滚](#515-版本管理与回滚)
  - [5.16 系统监控](#516-系统监控)
  - [5.17 用户偏好设置](#517-用户偏好设置)
- [六、因果推演（详细用法）](#六因果推演详细用法)
- [七、分支假设（详细用法）](#七分支假设详细用法)
- [八、推演深化能力](#八推演深化能力)
- [九、前端架构详解](#九前端架构详解)
- [十、后端架构详解](#十后端架构详解)
- [十一、数据存储](#十一数据存储)
- [十二、REST API 完整参考](#十二rest-api-完整参考)
- [十三、快捷键](#十三快捷键)
- [十四、配置与环境变量](#十四配置与环境变量)
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
| **可解释性** | 节点 / 边都标注来源（derived / inferred / predicted），推演节点支持三段式解释（依据 / 假设 / 反例） |
| **可回溯** | 完整撤销 / 重做历史；模型版本快照自动备份与回滚；对话里每条上传文件都可重新查看 |
| **概率推理** | 支持 force / block / probability 三种约束模式，概率约束融合贝叶斯先验 |
| **多 LLM 支持** | 10+ 主流模型提供商，OpenAI / Anthropic 双协议，即时切换 |

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
┌───────────────────────────────────────────────────────────────┐
│                         Browser                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │              Vue 3 SPA  (frontend:5173)                 │ │
│  │  ┌───────────┐ ┌───────────┐ ┌──────────────────────┐  │ │
│  │  │ Graph     │ │ Chat      │ │  Prediction /        │  │ │
│  │  │ Canvas    │ │ Panel     │ │  Explanation Panel   │  │ │
│  │  │ (SVG)     │ │ (SSE)     │ │  (SSE streaming)     │  │ │
│  │  └───────────┘ └───────────┘ └──────────────────────┘  │ │
│  └────────────────────────┬────────────────────────────────┘ │
└────────────────────────────┼──────────────────────────────────┘
                             │  HTTP / SSE
                             ▼
┌───────────────────────────────────────────────────────────────┐
│              Spring Boot 后端 (port 8000)                      │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  Controllers (7 个)                                     │ │
│  │  ├── ChatController       — 对话 + 图谱增量             │ │
│  │  ├── ScenarioController   — 推演、分支、解释             │ │
│  │  ├── OntologyModelController — 本体 CRUD + 抽取 + 版本  │ │
│  │  ├── ConversationController  — 对话历史                  │ │
│  │  ├── ModelController      — LLM 模型列表 + 连接测试      │ │
│  │  ├── HypothesisTemplateController — 假设模板             │ │
│  │  ├── GraphTemplateController — 图谱模板                  │ │
│  │  ├── DocumentTextController  — DOCX 抽文本               │ │
│  │  ├── ConfigController     — 前端配置下发                 │ │
│  │  ├── PrefsController      — 用户偏好                     │ │
│  │  └── SystemController     — 健康检查 + LLM 指标          │ │
│  └──────────┬──────────────────────────┬────────────────────┘ │
│             │                          │                      │
│             ▼                          ▼                      │
│  ┌──────────────────┐         ┌───────────────┐              │
│  │   LlmService     │  HTTP   │  File Store   │              │
│  │  (OpenAI 兼容 /  │ ──────► │ ~/.tuiyan/    │              │
│  │   Anthropic)     │         │  *.json       │              │
│  └──────────────────┘         └───────────────┘              │
└───────────────────────────────────────────────────────────────┘
```

| 层 | 技术 |
|---|---|
| **前端** | Vue 3.5（Composition API + `<script setup>`）、TypeScript 5.8、Vite 6、自定义 SVG 画布 |
| **后端** | Java 17、Spring Boot 3.3、Spring Web、Jackson、Java `HttpClient` |
| **LLM 协议** | OpenAI 兼容协议 / Anthropic 原生协议（streaming + JSON 输出） |
| **文档解析** | Apache PDFBox 3.0（PDF 文字 + 扫描件图片渲染）、Apache POI 5.2（DOCX 段落 + 表格） |
| **存储** | 本地 JSON 文件（`~/.tuiyan/`），原子写入保证一致性 |
| **通信** | HTTP REST + SSE（流式生成 / 推演实时回执 / 节点解释） |

### 前端架构概要

| 模块 | 说明 |
|---|---|
| **路由** | 无 vue-router，通过 `ref<'welcome' | 'list' | 'graph' | 'settings'>` 状态机切换 |
| **状态管理** | Vue 3 Composition API + 16 个 Composable（无 Vuex / Pinia） |
| **UI 组件** | 31 个 `.vue` 组件，全部自定义 CSS（暗色主题 + 毛玻璃效果），无第三方 UI 库 |
| **API 层** | 9 个 API 模块，统一 HTTP 封装 + SSE 流式解析 |
| **渲染模式** | 编辑模式（完整功能） / 预览模式（`?preview=<id>` 只读全屏） |

### 后端架构概要

| 模块 | 说明 |
|---|---|
| **Controller** | 11 个 REST 控制器，覆盖所有业务端点 |
| **Service** | 11 个业务服务：LLM 适配、推演编排、解释生成、文档抽取、指标收集等 |
| **Model** | 9 个领域模型 / DTO |
| **Support** | 6 个工具类：原子写入、PDF/DOCX 抽取、文件嗅探、ID 盐重写、SSE 推送、概率计算 |
| **异步线程池** | `predictionExecutor`：核心 4 线程，最大 8，队列 50，用于推演 / 解释异步执行 |

---

## 四、核心概念

| 概念 | 说明 |
|---|---|
| **本体模型 (OntologyModel)** | 一个图谱画布，包含 nodes / edges 与元信息，支持版本快照 |
| **节点类型** | `entity`（实体）、`process`（流程）、`event`（事件）、`data`（数据源）、`external`（外部系统）、`rule`（规则/条件） |
| **边来源 (source)** | `derived`（文本明示）、`inferred`（模型推断填充）、`predicted`（推演生成）、`preset`（预置）|
| **rule_driven 边** | 由 rule 节点驱动的关系，画布中以粉色显示 |
| **主干 (trunk)** | 本体模型的"真实"图谱状态 |
| **分支 (branch / Scenario)** | 一次推演的快照，挂在主干或其它分支下，仅增量记录预测新增的节点/边 |
| **推演意图 (intent)** | `forward`（从原因到结果）或 `backward`（从结果反推原因） |
| **约束 (constraint)** | 推演前指定某节点 `force`（必然发生）、`block`（不会发生）或 `probability`（设定先验概率），用于剪枝或概率融合 |
| **推演 DAG (PredictionDag)** | 推演产生的有向无环图，包含节点、边、因果链顺序、约束列表、节点解释缓存 |
| **节点解释 (NodeExplanation)** | 对预测节点的三段式 LLM 解释：依据、假设、反例 |

---

## 五、功能模块总览

### 5.1 本体模型管理

| 功能 | 入口 | 说明 |
|---|---|---|
| 创建模型 | 「列表」页 → "新建模型" / 欢迎页对话提交 | 自动生成默认标题，创建后跳转到画布 |
| 列出模型 | 左侧栏 / "列表" 页 | 卡片形式显示节点 / 关系统计与更新时间 |
| 打开模型 | 点击模型卡片或左侧条目 | 加载 nodes / edges 并重置历史栈 |
| 删除模型 | 卡片右上角 "×" | 二次确认，同时清理对应分支 |
| 自动保存 | 任何编辑触发 | 1.2 s 防抖 PUT 到后端；切换 / 撤销时立即保存 |
| 首次启动默认模型 | 后端初始化时 | 自动创建供应链、金融风控、组织架构等示例模型 |

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
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000,
  "updated": "2026-05-22 10:00"
}
```

---

### 5.2 AI 对话建模

#### 5.2.1 输入方式

| 方式 | 操作 |
|---|---|
| 文字 | 直接输入；`@` 触发节点/边引用下拉（自动补全） |
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

#### 5.2.5 `@` 引用系统

输入 `@` 后弹出节点 / 关系候选下拉：
- 支持按名称模糊搜索
- 用 `↑ / ↓` 键切换候选，`Enter` 确认插入
- 插入后自动替换为带 id 的引用标记，LLM 可精确定位图谱元素

---

### 5.3 图谱可视化与交互

#### 基础操作

| 操作 | 行为 |
|---|---|
| 拖动空白区 | 平移画布 |
| `Ctrl/⌘ + 滚轮` | 以鼠标为锚点缩放（0.1× ~ 3×） |
| 点击节点 | 选中 + 高亮相关边（绿色流光加速） |
| `Ctrl/⌘ + 点击节点` | 多选/取消多选（加入多选集合） |
| 拖动节点 | 移动节点（拖拽开始时触发一次历史快照） |
| 批量拖动 | 多选状态下拖动其中一个，所有选中节点同步移动 |
| 右键节点 | 弹出上下文菜单（推演 / 编辑 / 删除 / 解释） |
| 双击节点 | 进入编辑模式（修改名称和类型） |
| 点击空白 | 取消选中 |

#### 顶部工具栏（左 → 右）

```
[↶ 撤销] [↷ 重做]   [分支选择]   [⚖ 对比] [📥 导入]   [● N 节点] [M 关系]   [Schema] [版本] [📋 模板] [💾 存为模板] [导出] [共享] [预览]
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

#### 热力图模式

对推演节点按 `effectiveProbability` / `confidence` 在**绿 → 黄 → 红**之间渐变着色，直观展示概率分布。

#### 分支对比着色

在对比模式下，节点按来源差异着色：蓝色 = A 独有，橙色 = B 独有，紫色 = 共同。

#### 视口裁剪优化

画布采用视口裁剪策略，超出可见区域的节点和边不渲染，支持 80+ 节点场景下的流畅交互。

---

### 5.4 图谱搜索与定位

画布左上角提供搜索框，支持 `Ctrl/⌘ + F` 快捷键唤起：

| 功能 | 说明 |
|---|---|
| 模糊匹配 | 按节点名称、类型、ID 进行不区分大小写的模糊匹配 |
| 结果计数 | 搜索框右侧显示 "当前/总数" 计数器 |
| 逐个跳转 | `Enter` 下一个，`↑` 按钮上一个，画布自动平移到目标节点并选中 |
| 高亮显示 | 匹配节点高亮，非匹配节点变暗；当前定位节点带金色轮廓 |
| 清除搜索 | `Esc` 或 `✕` 按钮清除搜索状态 |

---

### 5.5 节点编辑与删除

#### 编辑节点

| 入口 | 操作 |
|---|---|
| 右键菜单 → "✎ 编辑节点" | 弹出编辑对话框 |
| 双击节点 | 弹出编辑对话框 |

编辑对话框支持：
- 修改节点**名称** (label)
- 切换节点**类型** (entity / process / event / data / external / rule)
- 修改后自动触发历史快照 + 持久化

#### 删除节点

| 入口 | 操作 |
|---|---|
| 右键菜单 → "✕ 删除节点" | 二次确认后删除 |
| `Delete` 键（单选/多选状态下） | 删除选中节点 |

删除节点时自动级联删除所有关联的边。

#### 属性编辑（NodeInfo 面板）

选中节点后右侧面板的属性 Tab 支持：
- 编辑已有属性的 key / value
- 新增属性行
- 删除属性行
- 保存按钮（仅在有修改时出现）

#### 删除关系

NodeInfo 面板的关系 Tab 中，每条边末尾有 `✕` 按钮，点击直接删除该关系。

---

### 5.6 批量节点操作

| 操作 | 说明 |
|---|---|
| `Ctrl/⌘ + 点击` | 将节点加入/移出多选集合 |
| 批量拖动 | 多选状态下拖动任一选中节点，所有选中节点同步平移 |
| 批量删除 | `Delete` 键删除所有选中节点及其关联边 |

---

### 5.7 撤销 / 重做 / 历史

| 项 | 值 |
|---|---|
| 最大历史条数 | 50 |
| 触发快照 | LLM 增量更新、清空画布、自动布局、合并导入、节点拖拽（每次拖拽只一次）、节点编辑、节点删除、属性编辑、边删除 |
| 撤销 | 顶栏 ↶ / `Ctrl/⌘ + Z` |
| 重做 | 顶栏 ↷ / `Ctrl/⌘ + Shift + Z` / `Ctrl + Y` |
| 输入框内 | 不抢键盘 |
| 切换模型 | 历史栈重置 |
| 同状态去重 | 内容一致的连续快照只保留一份 |
| 撤销后落盘 | 立即 `persistCurrentModel(true)`，不走防抖 |

栈语义：经典 undo —— 处于历史中间时再做新改动会丢弃 redo 分支。

---

### 5.8 节点详情与 Schema

- **节点详情面板**：选中节点后，右侧抽屉显示：
  - 节点元数据（id、类型、来源）
  - 属性表 (`props[]`)——支持在线编辑
  - 入边 / 出边列表（点击跳转到关联节点）——支持删除
  - 推演置信度 + 有效概率（若为预测节点）
- **Schema 面板**：顶栏 "Schema" 按钮切换到模式视图，列出当前图谱里每种 type 的属性合集、约束信息

---

### 5.9 文档导入与实体抽取

#### 5.9.1 支持格式

| 格式 | 处理方式 |
|---|---|
| **PDF** | PDFBox 抽每页文字；扫描件（每页文字 ≤ 200 字符）自动回落到逐页渲染（110 DPI，最多 8 页）走视觉抽取 |
| **DOCX** | Apache POI 抽取段落 + 表格；表格保留二维结构 |
| **图片**（PNG / JPEG / WebP / GIF） | base64 编码作为多模态 attachment 给 LLM |
| **网页 URL** | Jsoup 拉静态 HTML，启发式去掉 nav/footer/script，优先取 article/main 正文；文本 < 500 字时回落 Playwright headless Chromium 重抓（应对 Vue/React SPA） |
| **文本类** | 直接拼入 prompt |

#### 5.9.2 流程

1. 拖入或点击选择文件、或在「网址」区粘贴一行一个 URL → 上传到 `/api/ontology-models/extract`（multipart）
2. 后端按魔术字节（非扩展名）做文件类型嗅探，分流到对应管线
3. URL 走 `WebPageFetcher`：解析→ SSRF 校验（拒绝 loopback / link-local / 内网 / CGNAT）→ Jsoup 抓取 → 文本不足时 Playwright 兜底
4. LLM 用 `EXTRACT_SYSTEM` prompt 抽取实体 / 关系，强调"以文档为准、不要虚构"
5. 抽取结果经 **ID 盐重写**（防止多次导入同一文件时 ID 冲突）
6. 返回前端 `ImportDialog`，可预览
7. 用户选择：
   - **合并到当前模型** — 增量加入主干（自动去重）
   - **另存为新模型** — 新建一个 OntologyModel

> **关于 headless 兜底**：Playwright 首次使用前需要执行 `mvn exec:java -e -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install chromium"` 下载 Chromium（约 150 MB）。未安装时静态 HTML 仍可正常工作，仅 SPA 站点会拿到骨架。

#### 5.9.3 限制

| 参数 | 默认 |
|---|---|
| 单文件 | PDF / DOCX ≤ 12 MB；图片 ≤ 8 MB |
| 单次请求 | ≤ 80 MB（spring multipart） |
| 单次上传文件数 | ≤ 8 |
| 单次 URL 数 | ≤ 5 |
| 单次总图片数 | ≤ 12 |
| 抽取文本预算 | PDF / DOCX / URL 各自 60K 字，超出截断 |
| 单页 HTML 体积 | ≤ 5 MB |
| 网页连接 / 读取超时 | 8s / 15s（headless 渲染 25s） |

---

### 5.10 图谱模板库

| 功能 | 入口 | 说明 |
|---|---|---|
| 保存为模板 | 顶栏「💾 存为模板」 | 将当前模型的图谱（标题 + nodes + edges）保存到模板库，标题自动追加"(模板)" |
| 从模板创建 | 顶栏「📋 模板」→ 选择模板 | 基于模板创建一个全新的本体模型 |
| 删除模板 | 模板列表中的删除按钮 | 二次确认后删除 |
| 模板存储 | `~/.tuiyan/templates/` | ID 格式 `tpl_<timestamp>`，独立于模型数据 |

---

### 5.11 假设模板

把一次推演的参数（intent / seeds / steps / prompt / constraints）保存为模板，下次一键复用。

| 功能 | 说明 |
|---|---|
| 保存模板 | 在 PredictDialog 左侧"模板"面板输入名称 → 保存 |
| 加载模板 | 列表点击即可加载已有模板参数 |
| 按模型隔离 | 不同模型有不同的模板列表（节点 ID 不同） |
| 最近使用排序 | `POST /{id}/touch` 更新时间戳，最近使用的排在最前 |
| 容错处理 | 加载时如果原始 seed 节点已被删除，会自动跳过并提示"已加载 X/Y 个起点" |

---

### 5.12 多 LLM 配置与连接测试

#### 配置字段

| 字段 | 含义 |
|---|---|
| `id` | 唯一标识 |
| `provider` | `openai` / `anthropic` / `deepseek` / 自定义 |
| `protocol` | `openai` 或 `anthropic`（决定请求体格式） |
| `base-url` | API 网关 |
| `model-name` | 模型 ID |
| `api-key` | 鉴权（支持环境变量占位符 `${...}`） |
| `context-window` | 总上下文 token 上限 |
| `max-output-tokens` | 生成长度上限 |
| `capabilities` | `streaming` / `json` / `vision` 等 |
| `enabled` | 是否启用 |

#### 支持的模型提供商

OpenAI、Anthropic (Claude)、DeepSeek、Qwen（通义千问）、Kimi（月之暗面）、GLM（智谱）、MiniMax、Ernie（文心一言）、DouBao（豆包）以及任何 OpenAI 兼容端点。

#### 协议自动检测

`LlmService` 根据 `baseURL` 或 `model name` 前缀自动判断使用 OpenAI 还是 Anthropic 协议。

#### 连接测试

设置页模型管理区域提供：

| 功能 | 说明 |
|---|---|
| 单个测试 | 每个已启用模型卡片上的「测试连接」按钮，发送最小化请求验证 API 可用性 |
| 全部测试 | 标题栏「🔌 全部测试」按钮，逐个测试所有已启用模型 |
| 结果显示 | 成功：`✓ {latency}ms`；失败：`✕ 失败`（hover 可见错误详情） |
| 测试逻辑 | 后端向目标 LLM 发送 `"hi"` + `max_tokens=1`，15 秒超时 |

#### 即时切换

对话框头部和推演对话框均提供模型下拉选择，支持 `configId`（指定预置配置）或 `modelOverride`（覆盖模型名）。

---

### 5.13 对话历史与数据源回看

| 功能 | 说明 |
|---|---|
| 模型独立对话 | 每个本体模型独立一组对话 |
| 自动标题 | 以首条用户消息生成标题（前 ~16 字） |
| 对话列表 | 左侧栏列出最近对话，按更新时间倒序，支持删除 |
| 双向持久化 | 本地浏览器 + 后端 JSON 文件双向保存 |
| **附件回看** | 每条用户消息上的附件 chip 可点击，弹出预览 |
| 图片预览 | 内联 `<img>` 显示 |
| 文本预览 | 等宽字体显示提取的文本，支持复制 / 下载 |
| 体积阈值 | 文本 ≤ 100KB、图片 dataUrl ≤ 2MB；超出的只保留元信息 |

---

### 5.14 导出与共享

| 功能 | 入口 | 说明 |
|---|---|---|
| **导出 JSON** | 顶栏「导出」→ JSON | 下载当前图谱为 JSON 文件（含元信息、所有节点/边、导出时间戳） |
| **导出 PNG** | 顶栏「导出」→ PNG | Canvas 2D 手绘简化版图谱截图，支持 80+ 节点 |
| **导出 Mermaid** | 顶栏「导出」→ Mermaid | 导出为 `.mmd` 文件，可粘贴到 Mermaid Live Editor 或 Markdown |
| **导出 Markdown** | 顶栏「导出」→ Markdown | 导出为结构化 `.md` 报告，含节点列表和关系表 |
| **共享** | 顶栏「共享」 | 把图谱摘要（标题 + 节点数 + 前 10 个节点）写入剪贴板 |
| **预览** | 顶栏「预览」 | 在新浏览器标签页以**只读全屏**模式打开当前图谱 |

预览模式特性：
- URL 形如 `?preview=om_xxx`，可直接分享
- 无侧栏、无对话框、无编辑按钮
- 仅支持 pan / zoom / 选中查看节点详情 / 适应屏幕 / 切 Schema / 导出 JSON
- 点击「预览」前会先 flush 防抖保存，避免加载到旧数据

---

### 5.15 版本管理与回滚

后端在每次保存模型时自动创建版本快照：

| 功能 | 说明 |
|---|---|
| 自动备份 | 每次 `PUT /api/ontology-models/{id}` 时自动创建版本快照 |
| 版本列表 | 顶栏「版本」按钮展开下拉，显示每个版本的时间戳、节点数、边数、文件大小 |
| 版本回滚 | 点击某个版本 → 二次确认 → 恢复到该时间点的图谱状态 |
| 存储限制 | 每个模型最多保留 **100 个**历史版本，超出自动淘汰最旧版本 |
| 存储路径 | `~/.tuiyan/ontology-models/versions/<modelId>/` |

---

### 5.16 系统监控

设置页提供系统健康检查和 LLM 调用指标监控：

#### 健康检查

| 指标 | 说明 |
|---|---|
| 系统状态 | UP / DOWN |
| JVM 内存 | 可用 MB / 总 MB |
| 数据目录 | `~/.tuiyan/` 是否可写 |

#### LLM 调用指标

| 指标 | 说明 |
|---|---|
| 总调用次数 | 累计 LLM API 调用数 |
| 总错误次数 | 失败的调用数（红色显示） |
| 平均延迟 | 所有调用的平均响应时间（ms） |
| 按模型分组 | 每个模型的调用次数、错误数、平均延迟 |
| 重置指标 | `POST /api/system/metrics/llm/reset` |

---

### 5.17 用户偏好设置

设置页「偏好」Tab 提供全局配置：

| 偏好项 | 说明 | 默认值 |
|---|---|---|
| 推演默认步数 | `predictDefaultSteps` | 4 |
| 最低置信度 | `predictMinConfidence` | 0.0 |
| 显示边标签 | `showEdgeLabels` | true |
| 自动适应画布 | `autoFit` | true |
| 字体大小 | 图谱节点字体 | 13px |

偏好存储在 `~/.tuiyan/prefs.json`，新版本新增字段会自动合并到旧文件中（向后兼容）。

设置页还提供「清空所有推演分支」按钮（`DELETE /api/prefs/scenarios`），慎用。

---

## 六、因果推演（详细用法）

> 因果推演 = 让 AI 沿现有图谱中的因果链 **接着往下走**（正向 / forward），或者 **倒着往回找**（反向 / backward）。每次推演都会生成一个新的"分支"，不会污染主干。

### 6.1 它能解决什么问题？

| 场景 | 选用 |
|---|---|
| "如果原料价格上涨 20%，下游会怎样？" | **forward** —— 从「原料涨价」节点向前推演影响 |
| "客户突然大量流失，可能是什么导致的？" | **backward** —— 从「客户流失」节点向上溯因 |
| "假设供应商 A 全面罢工，再叠加规则 R3 生效..." | forward + 多 seeds + force 约束 |
| "把 X 节点排除后，因果链会怎么改？" | forward + block 约束 |
| "如果节点 Y 发生的概率为 70%，下游影响如何？" | forward + probability 约束 |

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

不填则后端自动按 `seed_label · MM-dd HH:mm` 格式生成。建议填一句短描述（如「供应商断货-旺季」），方便日后切换。

#### ⑥ 约束（constraints）— 可选

点击「+ 约束节点」展开，可对画布上任意节点设三种约束：

| 模式 | 颜色 | 语义 |
|---|---|---|
| **force**（必然） | 绿色 | 该节点一定发生 / 一定存在；提高其下游事件先验 |
| **block**（禁止） | 红色 | 该节点不会发生；推演时凡是依赖它的预测都会被剪枝 |
| **probability**（概率） | 紫色 | 设定 0~1 的先验概率，作为下游联合概率入口初值，用贝叶斯式更新 |

点击 chip 可在三态间循环切换（force → block → probability → force）。

概率约束仅适用于现有图谱节点（非预测节点），预测节点的概率按钮会被禁用。

**约束冲突检测**：UI 会扫描 force 与 block 之间的直接因果边，发现冲突时显示橙色警告。

#### ⑦ 加载 / 保存模板 — 可选

左下角 📁 切到"模板"面板：

- 输入名称 → 「保存为模板」把当前所有参数存起来
- 列表点击即可加载已有模板
- 模板按"最近使用"排序

### 6.4 执行与实时步骤

1. 点击右下角 **「开始推演」** / 「开始溯因」
2. 对话框关闭，**右侧对话面板里新增一条「⚡ 推演」消息**（金色边框）
3. 推演通过 SSE 流式返回，每一步实时追加（220ms 视觉节奏间隔）：

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

### 6.5 推演消息功能

| 功能 | 说明 |
|---|---|
| 置信度过滤滑块 | 消息底部（≥ 2 步时出现），拖动可隐藏低置信度的预测步 |
| 点击步骤卡片 | 画布自动 focus 对应节点 |
| 点击起点 chip | 画布聚焦到相关节点 |
| 剪枝报告 | 列出因 block 约束被剪掉的候选及理由 |
| 停止按钮 | 运行中显示，点击中止推演 |
| 📜 prompt 按钮 | 查看本次推演发给 LLM 的完整 system + user 文本 |
| 持久化 | 推演消息沉到对话历史，与对话一并保存 |

### 6.6 推演结果落图

推演完成后，**新增节点 / 边自动加入图谱**（标记 `source: 'predicted'`），视觉表现：

- 节点右上角带"预测 N"角标 + 置信度百分比
- 边用 **金色虚线 + ◇ 标记** 渲染
- 当前画布所属"分支"切换为新生成的分支（顶栏出现金色横幅）

### 6.7 评估与调整

| confidence | 含义 |
|---|---|
| ≥ 0.7 | 模型相当笃定，有规则或上下文支撑 |
| 0.4 ~ 0.7 | 合理推断，但存在多种可能 |
| < 0.4 | 弱推断，建议用 prompt / 约束细化后重跑 |

调整后重跑通常会生成新的分支，而不是覆盖。

---

## 七、分支假设（详细用法）

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
- branch 只存"在父分支基础上 **新增** 了什么"（DAG 增量格式），节省存储
- 切换分支时，前端沿祖先链回溯并合并所有增量，重建完整画布

### 7.2 创建分支

分支不需要手动创建 —— **每次推演完成后自动生成一个分支**，名字来自 PredictDialog 的「分支名」字段（不填则自动生成）。

在分支视图下再次右键节点 → 「从此推演」，会以**当前分支**为 `parentBranchId`，产生子分支。

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

### 7.4 在分支上继续推演（叠加假设）

1. 切到 branch A（"供应商断货-旺季"）
2. 此时画布显示 trunk + A 的增量节点（金色）
3. 右键某节点 → 「从此推演」
4. 在 PredictDialog 里填新的 prompt（如"再叠加物流瘫痪 24h"）
5. 推演完成 → 自动生成 **branch A1**，挂在 A 之下

层层堆叠假设，每一层都可独立切换查看，互不污染。

### 7.5 对比分支

顶栏「**⚖ 对比**」按钮（**分支数 ≥ 2 时可用**）：

1. 弹出 BranchCompareDialog
2. 在左右两个下拉选择 A、B 两个分支（或 trunk）
3. 对比视图展示三段：

| 段 | 颜色 | 内容 |
|---|---|---|
| **仅 A** | 蓝色 | A 有而 B 没有的节点 / 边 |
| **共有** | 紫色 | 两边都存在的（取交集） |
| **仅 B** | 橙色 | B 有而 A 没有的节点 / 边 |

画布中节点同步着色显示差异。

### 7.6 删除分支

分支选择器里每一项右侧有 `×` 按钮（hover 显示），点击 → 二次确认 → 删除。

- **trunk 不可删**
- 删父分支不会自动级联删子分支（先删叶再删父）

### 7.7 旧分支迁移

从 v0.5 之前升级的旧分支以"完整快照"形式落盘。分支选择器下拉里有「**迁移旧分支**」入口，自动转换为 DAG 增量格式。

### 7.8 一键清空所有分支

`DELETE /api/prefs/scenarios`（前端入口在「设置」页面）—— 清空所有模型下的所有分支。

### 7.9 典型工作流

**场景：评估三种应急预案**

```
1. 在 trunk 上建好基线供应链图谱
2. 推演分支 A："启动 B 级应急预案"  → 看交付率影响
3. 推演分支 B："启动 A 级应急预案"  → 看交付率影响
4. 推演分支 C："不响应"             → 作为对照
5. 用「⚖ 对比」分别看 A vs C 和 B vs C 的差异路径
6. 在最优分支上继续推演子分支验证细节
```

---

## 八、推演深化能力

### 8.1 概率约束（三态 What-if）

在 PredictDialog 的约束区域，除了 force / block 外，第三种模式 **probability**：

| 操作 | 说明 |
|---|---|
| 添加概率约束 | 候选节点添加时选择「概率」按钮（紫色），默认 0.5 |
| 调整概率值 | 同行追加 range 滑块 + 数值显示，范围 0~1 |
| 后端融合 | 概率值作为入口节点的 `effProb` 初值，下游节点的联合概率用贝叶斯式更新 |
| 限制 | 仅适用于现有图谱节点，预测节点禁用此模式 |

### 8.2 原始 Prompt 查看

| 入口 | 说明 |
|---|---|
| 推演消息头部 📜 prompt 按钮 | 弹出 RawPromptDialog |
| 展示内容 | 推演时发给 LLM 的完整文本（`=== SYSTEM ===` + `=== USER ===`） |
| 操作 | 全文复制 / 关闭 |
| 兼容性 | 早期版本创建的分支无此数据，提示"该分支创建于早期版本，未保存原始 prompt" |

### 8.3 节点详细解释（"为什么会发生？"）

| 入口 | 说明 |
|---|---|
| 推演节点右键 → 🔍 为什么会发生？ | 仅 `source === 'predicted'` 节点可用 |
| 展示方式 | 浮动 ExplanationPanel（360×440），可同时打开多个（最多 3 个），支持拖拽移动 + 折叠 + 关闭 |
| 内容 | 三段式 LLM 解释 |
| 缓存 | 解释结果缓存到 `PredictionDag.explanations[nodeId]`，再次查看直接读取 |
| 重新生成 | 面板底部 🔄 按钮（`forceRegenerate: true`） |

三段式解释内容：

| 段落 | 含义 |
|---|---|
| **依据 (evidence)** | 支持该预测的证据链——上游节点、规则、数据 |
| **假设 (assumptions)** | 该预测隐含的前提条件 |
| **反例 (counterexamples)** | 可能导致该预测不成立的反例情景 |

解释通过 SSE 流式返回，每个字段独立推送增量文本。

---

## 九、前端架构详解

### 9.1 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Vue | 3.5.32（Composition API + `<script setup>`） |
| TypeScript | 5.8 |
| Vite | 6.2.0（开发服务器 + 构建） |
| UI 库 | 无（全部自定义 CSS，暗色主题 + 毛玻璃效果） |
| 图标 | Unicode 符号（✦, ◈, ⚡, 📥 等） |

### 9.2 目录结构

```
frontend/src/
├── main.ts                    # 入口点（编辑模式 / 预览模式双入口）
├── App.vue                    # 根编排器（~1100+ 行，管理全局状态）
├── constants.ts               # 节点类型定义、初始数据
├── types.ts                   # TypeScript 接口定义
├── index.css                  # 全局样式
├── api/                       # REST / SSE 客户端（9 个模块）
│   ├── http.ts               # 统一 HTTP 封装 + SSE 流解析
│   ├── ontology.ts           # 本体模型 CRUD + 抽取 + 版本 + 模板
│   ├── scenarios.ts          # 分支 CRUD + 推演 SSE 流
│   ├── conversations.ts      # 对话历史 CRUD
│   ├── chat.ts               # LLM 对话 + SSE 流式
│   ├── explanations.ts       # 节点解释 SSE 流 + rawPrompt
│   ├── config.ts             # 前端配置
│   ├── models.ts             # LLM 模型列表 + 连接测试
│   ├── prefs.ts              # 用户偏好
│   └── hypothesisTemplates.ts # 假设模板 CRUD
├── composables/               # 可复用状态逻辑（16 个）
│   ├── usePrediction.ts      # 推演编排 + 解释面板管理
│   ├── useScenarios.ts       # 分支管理 + DAG 祖先合并
│   ├── useGraphHistory.ts    # 撤销 / 重做栈（50 条）
│   ├── useGraphActions.ts    # 布局 + 导出（JSON/PNG/Mermaid/Markdown）
│   ├── useConversations.ts   # 对话持久化
│   ├── useChatModels.ts      # 模型选择 + 加载
│   ├── useAttachments.ts     # 文件附件管理
│   ├── useMention.ts         # @引用自动补全
│   ├── useSSE.ts             # SSE 流处理
│   ├── useOntologyModel.ts   # 模型 CRUD
│   ├── useModelConfigs.ts    # LLM 配置 + 连接测试
│   ├── useSettingsPrefs.ts   # 偏好管理
│   ├── useImportFlow.ts      # 导入流程
│   ├── useToast.ts           # 消息提示
│   ├── useConfirm.ts         # 确认对话框
│   └── useDivider.ts         # 面板拖拽分割
└── components/                # Vue 组件（31 个）
    ├── Sidebar.vue            # 导航侧边栏
    ├── WelcomeChat.vue        # 欢迎页对话
    ├── GraphCanvas.vue        # SVG 图谱画布（核心组件，600+ 行）
    ├── NodeInfo.vue           # 节点详情面板（可编辑属性）
    ├── ChatPanel.vue          # 对话面板
    ├── ExplanationPanel.vue   # 节点解释浮动面板
    ├── SchemaPanel.vue        # TBox 模式编辑器
    ├── PredictDialog.vue      # 推演参数配置对话框
    ├── BranchCompareDialog.vue # 分支对比对话框
    ├── BranchPicker.vue       # 分支选择器
    ├── ImportDialog.vue       # 文档导入对话框
    ├── RawPromptDialog.vue    # 原始 prompt 查看
    ├── ScenarioTimeline.vue   # 推演时间线
    ├── SettingsView.vue       # 设置页
    ├── views/
    │   ├── GraphView.vue      # 图谱页容器
    │   └── PreviewView.vue    # 只读预览页
    └── chat/
        ├── ChatMessageList.vue     # 消息列表
        ├── AttachmentChips.vue     # 附件标签
        ├── AttachmentPreview.vue   # 附件预览
        └── PredictionMessage.vue   # 推演消息展示
```

### 9.3 核心数据类型

```typescript
// 图谱节点
interface OntologyNode {
  id: string;
  label: string;
  type: 'entity' | 'process' | 'event' | 'data' | 'external' | 'rule';
  x?: number; y?: number;
  source?: 'predicted' | 'derived' | 'inferred' | 'preset';
  props?: { key: string; value: any; source?: string }[];
  attributes?: Record<string, any>;
  constraints?: Record<string, any>;
  predictedStep?: number;
  confidence?: number;
  effectiveProbability?: number;
  explanation?: string;
}

// 图谱边
interface OntologyEdge {
  id: string;
  from: string;
  to: string;
  label?: string;
  source?: string;
  rule_driven?: boolean;
  ruleId?: string;
  constraints?: Record<string, any>;
}

// 推演分支
interface Scenario {
  id: string;
  name: string;
  modelId: string;
  parentBranchId?: string;
  intent: 'forward' | 'backward';
  steps: number;
  seeds: string[];
  prompt?: string;
  rawPrompt?: string;
  dag: PredictionDag;
  createdAt: number;
}

// 推演 DAG
interface PredictionDag {
  intent: string;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  chain: ChainStep[];
  constraints?: Constraint[];
  explanations?: Record<string, NodeExplanation>;
}

// LLM 模型配置
interface ModelConfig {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey: string;
  enabled: boolean;
  contextWindow: number;
  maxOutputTokens: number;
  capabilities?: string[];
}
```

### 9.4 数据流

```
用户交互 → Composable 方法调用 → Ref 更新 → 模板响应式渲染
                                       ↓
                              1.2s 防抖 → PUT /api → 后端持久化
```

---

## 十、后端架构详解

### 10.1 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Java | 17 |
| Spring Boot | 3.3.0 |
| Maven | 构建工具 |
| Jackson | JSON 序列化 / 反序列化 |
| Apache PDFBox | 3.0.3（PDF 文字抽取 + 图片渲染） |
| Apache POI | 5.2.5（DOCX 解析） |
| Jsoup | 1.20.1（HTML 抓取 + 正文抽取） |
| Playwright for Java | 1.50.0（SPA 网页 headless 兜底，需首次 `install chromium`） |
| Java HttpClient | 内置 HTTP 客户端（LLM API 调用） |

### 10.2 目录结构

```
backend/src/main/java/com/tuiyan/backend/
├── BackendApplication.java           # Spring Boot 入口
├── config/
│   ├── AppPaths.java                 # 数据目录路径配置
│   ├── AsyncConfig.java              # 异步线程池配置
│   ├── GlobalExceptionHandler.java   # 全局异常处理
│   ├── LlmProperties.java           # LLM 模型配置绑定
│   ├── ResourceNotFoundException.java # 404 异常
│   └── WebConfig.java               # CORS 配置
├── controller/                       # 11 个 REST 控制器
│   ├── ChatController.java
│   ├── ConfigController.java
│   ├── ConversationController.java
│   ├── DocumentTextController.java
│   ├── GraphTemplateController.java
│   ├── HypothesisTemplateController.java
│   ├── ModelController.java
│   ├── OntologyModelController.java
│   ├── PrefsController.java
│   ├── ScenarioController.java
│   └── SystemController.java
├── model/                            # 9 个领域模型
│   ├── ChatRequest.java
│   ├── ConfigResponse.java
│   ├── Constraint.java               # force / block / probability
│   ├── Conversation.java
│   ├── HypothesisTemplate.java
│   ├── LlmProvider.java              # 提供商枚举 + 协议检测
│   ├── NodeExplanation.java           # 三段式解释
│   ├── OntologyModel.java
│   ├── PredictRequest.java
│   ├── PredictionDag.java
│   └── Scenario.java
├── service/                          # 11 个业务服务
│   ├── ConversationService.java
│   ├── DocumentExtractionService.java # 多格式文档处理管线
│   ├── GraphTemplateService.java
│   ├── HypothesisTemplateService.java
│   ├── LlmMetricsService.java        # 调用指标收集
│   ├── LlmService.java               # 多提供商 LLM 适配器
│   ├── OntologyModelService.java      # 模型 CRUD + 版本管理
│   ├── PredictionOrchestrator.java    # 推演流程编排
│   ├── PrefsService.java
│   ├── ScenarioExplanationService.java # 节点解释生成
│   └── ScenarioService.java
├── support/                          # 6 个工具类
│   ├── DocxTextExtractor.java
│   ├── FileSniffer.java              # 魔术字节文件类型检测
│   ├── IdSaltRewriter.java           # ID 盐重写（防冲突）
│   ├── PdfTextExtractor.java         # PDF 文字 + 扫描件渲染
│   ├── PredictionMath.java           # 概率计算工具
│   └── SsePushUtils.java             # SSE 推送 + 可取消 emitter
└── util/
    └── JsonAtomic.java               # 原子 JSON 写入
```

### 10.3 核心服务详解

#### LlmService — 多提供商 LLM 适配器

| 方法 | 说明 |
|---|---|
| `chat()` / `chatStreaming()` | 对话 + 图谱增量生成 |
| `predict()` / `streamPredictChain()` | 因果链推演 |
| `extractFromDocuments()` | 文档实体抽取 |
| `explainNode()` | 节点三段式解释 |
| `testModelConnection()` | LLM 连接测试（最小请求 + 15s 超时） |
| `resolveConfig()` | 按 ID 或覆盖解析模型配置 |
| `truncateGraphForContext()` | 上下文窗口感知的图谱裁剪（N-hop 邻居） |
| `mergeExtractionByLabel()` | 跨文档块合并抽取结果 |

协议支持：OpenAI 兼容 + Anthropic 原生，流式 / 非流式双模式。

#### PredictionOrchestrator — 推演编排

执行流程：
1. 解析 intent（forward/backward）+ parentBranchId（fork 还是新建）
2. 裁剪图谱上下文（N-hop 邻居，适配 LLM 上下文窗口）
3. 调用 `LlmService.predictChain()` 获取因果链
4. 逐步构建节点/边，应用 force/block/probability 约束
5. SSE 推送每一步结果（220ms 视觉间隔）
6. 自动布局（X_STEP=220px, Y_STEP=100px）
7. 落盘 Scenario 到磁盘
8. 发送 complete/error 事件

约束处理：
- `force` → 提高下游事件先验概率
- `block` → 剪枝依赖该节点的所有预测
- `probability` → 作为入口节点 effProb 初值，贝叶斯式更新

#### DocumentExtractionService — 文档处理管线

处理流程：
1. 魔术字节文件类型嗅探（不依赖扩展名）
2. PDF → 文字抽取（PDFBox），每页文字 ≤ 200 字符则回落到图片渲染（110 DPI，最多 8 页）
3. DOCX → 段落 + 表格抽取（Apache POI）
4. 图片 → base64 编码
5. 调用 LLM 抽取实体和关系
6. ID 盐重写（防止多次导入时 ID 冲突）

### 10.4 CORS 与安全

- 无认证：单用户本地工具
- CORS 限制：仅允许 `localhost:*` 和 `127.0.0.1:*`
- 全局异常处理：`ResourceNotFoundException` → 404，`IllegalArgumentException` → 400，`IOException` → 500

### 10.5 异步配置

推演和解释在独立线程池 `predictionExecutor` 中运行：
- 核心线程：4
- 最大线程：8
- 队列容量：50
- 关闭等待：20 秒

---

## 十一、数据存储

后端无数据库，全部落地到 `~/.tuiyan/`（可通过 `app.data.dir` 配置修改）。

```
~/.tuiyan/
├── ontology-models/          # 一个模型一个 JSON 文件
│   ├── om_xxx.json
│   └── versions/             # 版本快照
│       └── om_xxx/           # 每个模型独立目录（最多 100 个快照）
│           ├── 1716547200000.json
│           └── 1716547300000.json
├── scenarios/                # 推演分支
│   └── sc_xxx.json
├── conversations/            # 对话历史
│   └── cv_xxx.json
├── hypothesis-templates/     # 假设模板
│   └── ht_xxx.json
├── templates/                # 图谱模板
│   └── tpl_xxx.json
└── prefs.json                # 全局偏好
```

**原子写入**：使用 `JsonAtomic` 工具类，先写 `.tmp` 文件再原子 rename，避免半截文件导致数据损坏。若文件系统不支持原子移动，则降级为 `REPLACE_EXISTING`。

**容错**：读取时跳过格式错误的 JSON 文件，不影响其他数据加载。

---

## 十二、REST API 完整参考

### 对话与 LLM

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/chat` | 对话 + 图谱增量（`Accept: application/json` 同步；`Accept: text/event-stream` 流式 SSE） |
| `GET` | `/api/config` | 取前端可见的应用配置（providers + customModels） |
| `GET` | `/api/models` | 列出所有已配置 LLM 模型 |
| `POST` | `/api/models/{id}/test` | 测试模型连接（返回 `{status, latencyMs}` 或 `{status, error}`） |

### 本体模型

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/ontology-models` | 列出所有本体模型 |
| `GET` | `/api/ontology-models/{id}` | 取单个模型 |
| `POST` | `/api/ontology-models` | 新建模型 |
| `PUT` | `/api/ontology-models/{id}` | 更新模型（自动创建版本快照） |
| `DELETE` | `/api/ontology-models/{id}` | 删除模型 |
| `POST` | `/api/ontology-models/extract` | 上传文档 / 网址抽取实体/关系（multipart：`files` 文件 + `urls` 网址，二者可混用） |
| `GET` | `/api/ontology-models/{id}/versions` | 列出版本快照 |
| `POST` | `/api/ontology-models/{id}/versions/{timestamp}/restore` | 恢复到指定版本 |

### 推演与分支

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/scenarios?modelId=` | 列出分支（可按模型筛选） |
| `GET` | `/api/scenarios/{id}` | 取单个分支 |
| `POST` | `/api/scenarios` | 启动推演（SSE 流式返回每一步） |
| `DELETE` | `/api/scenarios/{id}` | 删除分支 |
| `GET` | `/api/scenarios/{id}/raw-prompt` | 获取推演的完整 prompt 文本 |
| `POST` | `/api/scenarios/{id}/explain` | 节点解释（SSE 流式返回三段式解释） |
| `POST` | `/api/scenarios/migrate` | 旧版分支批量迁移到 DAG 增量格式 |

### 对话历史

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/conversations` | 列出所有对话（按更新时间倒序） |
| `GET` | `/api/conversations/{id}` | 取单个对话 |
| `POST` | `/api/conversations` | 新建对话 |
| `PUT` | `/api/conversations/{id}` | 更新对话 |
| `DELETE` | `/api/conversations/{id}` | 删除对话 |

### 经验库

与「数据源 / 历史记录」同级别挂在工作空间下，沉淀可复用的经验文档（标题 + 正文 + 标签）。
保存后自动建立向量索引（落 `exp_chunk` / `exp_embedding`）；对话建模时按相关度自动召回为参考资料，
来源名以「经验：…」前缀与数据源内容区分。

> **本体血缘图由经验库文件构建（数据源只供血）**
> 新数据流以经验库为本体血缘图的唯一构建入口：在经验库页点击「🧬 构建本体血缘图」，
> 后端会把**当前工作空间下的全部经验文件**聚合成长文本，经文档抽取管线
> （`ExperienceOntologyService` → `ExtractionLlmService`）抽出节点 / 边，再 salt 重写后供前端合并 / 另存为模型。
> 数据库类数据源不再直接出图（旧的 `POST /api/data-sources/{id}/extract-ontology` 直出链路与
> `SchemaOntologyService` 已移除）：改为在「表」页点「⤓ 导出结构到经验库供血」把 DDL 沉淀成经验文件参与建图。
> 对应建图 SSE 接口 `POST /api/experiences/extract-ontology`
> （事件序列：`step`* → `complete{nodes,edges,reply,salt,sourceCount}`）。
>
> **第二阶段·数据供血绑定**：图建好后，在节点详情面板「供血」页把节点绑定到数据源的表（可选 WHERE 过滤），
> 运行时按绑定取数为节点供血。对应 `node_data_binding` 表与 `/api/node-bindings` 端点（含 `/{id}/fetch` 取数）。

索引管线参考 Cursor 的做法（仅经验库启用）：

- **结构感知切块**：按 Markdown 标题层级切小节，每块前置标题面包屑作为上下文（`TextChunker.chunkStructured`）。
- **内容哈希增量**：块按 `content_hash` 比对，未变化的块复用旧向量，只对新增/改动块调 embedding，省调用费。
- **ANN 检索（可选）**：装了 pgvector 扩展时走 HNSW 近似最近邻（`embedding_vec <=> ?::vector`）；
  没装则回退 TEXT 向量的暴力余弦。pgvector 在应用就绪后由 `PgVectorSupport` **尝试性启用**，
  失败不影响启动（故意不放进 `init.sql`，因其 `continue-on-error: false`）。TEXT `embedding` 列始终是真值来源。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/experiences?workspaceId=` | 列出工作空间下经验（按更新时间倒序，含 `indexStatus`） |
| `GET` | `/api/experiences/{id}` | 取单条经验（含正文） |
| `POST` | `/api/experiences` | 新建经验（`{title, content?, tags?}`），自动建索引 |
| `PUT` | `/api/experiences/{id}` | 更新经验（字段可选，null 不改动），自动重建索引 |
| `DELETE` | `/api/experiences/{id}` | 删除经验（索引随外键级联清理） |
| `POST` | `/api/experiences/{id}/reindex` | 手动重建向量索引（embedding 配置变更后补建） |
| `GET` | `/api/experiences/{id}/index-status` | 查询索引状态 + 文本块数量 |
| `POST` | `/api/experiences/file` | 上传文件建经验（PDF/Word/TXT/MD 抽正文，音频走 ASR） |
| `POST` | `/api/experiences/from-ddl` | 数据源导出 DDL 沉淀为经验（`{dataSourceId}`，「供血」入口） |
| `POST` | `/api/experiences/extract-ontology` | **聚合整个工作空间经验库构建本体血缘图（SSE）** |
| `GET` | `/api/experiences/{id}/file` | 预览/下载上传原件（`?download` 附件下载，`?wsId` 兜底鉴权） |

### 节点数据供血绑定

把已建好的本体血缘图节点绑定到数据源的表/列，运行时按绑定取数为节点供血（新数据流第二阶段）。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/node-bindings?modelId=&nodeId=` | 列出某模型（可选某节点）的供血绑定 |
| `POST` | `/api/node-bindings` | 新建绑定（`{modelId, nodeId, dataSourceId, tableName?, columnMap?, filterSql?}`） |
| `PUT` | `/api/node-bindings/{id}` | 更新绑定 |
| `DELETE` | `/api/node-bindings/{id}` | 删除绑定 |
| `POST` | `/api/node-bindings/{id}/fetch` | 按绑定取数供血（`{limit?}` → `{columns, rows, rowCount, truncated}`） |

### 模板

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/hypothesis-templates?modelId=` | 列出假设模板（按模型 + 最近使用排序） |
| `POST` | `/api/hypothesis-templates` | 保存 / 创建模板 |
| `POST` | `/api/hypothesis-templates/{id}/touch` | 标记为最近使用 |
| `DELETE` | `/api/hypothesis-templates/{id}` | 删除模板 |
| `GET` | `/api/templates` | 列出图谱模板 |
| `POST` | `/api/templates` | 保存图谱模板 |
| `DELETE` | `/api/templates/{id}` | 删除图谱模板 |

### 文档处理

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/extract/docx-text` | DOCX → 纯文本（返回 `{name, text, chars, paragraphs, tables, truncated}`） |

### 用户偏好

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/prefs` | 读取用户偏好设置 |
| `PUT` | `/api/prefs` | 保存偏好设置 |
| `DELETE` | `/api/prefs/scenarios` | 清空所有推演分支 |

### 系统管理

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/system/health` | 健康检查（内存、数据目录状态） |
| `GET` | `/api/system/metrics/llm` | LLM 调用统计（总次数、错误数、延迟、按模型分组） |
| `POST` | `/api/system/metrics/llm/reset` | 重置 LLM 指标 |

### SSE 事件类型

#### `/api/chat` 事件

| event | data | 说明 |
|---|---|---|
| `text` | 文本片段 | 模型流式 token |
| `complete` | `{ reply, add_nodes, add_edges }` | 对话完成，含新增节点/边 |
| `error` | 错误描述 | 生成失败 |

#### `/api/scenarios`（推演）事件

| event | data | 说明 |
|---|---|---|
| `step` | 单步推演结果 | 包含 nodeId、label、type、confidence、triggeredBy 等 |
| `prune` | 被剪枝候选 | 因 block 约束被移除的预测 |
| `complete` | 完整 Scenario 对象 | 推演完成 |
| `error` | 错误描述 | 推演失败 |

#### `/api/scenarios/{id}/explain`（节点解释）事件

| event | data | 说明 |
|---|---|---|
| `chunk` | `{ field, text }` | 增量文本片段（field: evidence / assumptions / counterexamples） |
| `complete` | `{ explanation: NodeExplanation }` | 解释完成 |
| `error` | 错误描述 | 生成失败 |

---

## 十三、快捷键

> 在图谱视图（`view === 'graph'`）下生效；输入框内不抢键。

| 快捷键 | 操作 |
|---|---|
| `Ctrl/⌘ + Z` | 撤销 |
| `Ctrl/⌘ + Shift + Z` 或 `Ctrl + Y` | 重做 |
| `Ctrl/⌘ + F` | 打开图谱搜索框 |
| `Ctrl/⌘ + 滚轮` | 缩放画布 |
| `Ctrl/⌘ + 点击节点` | 多选节点 |
| `Delete` | 删除选中节点（支持多选批量删除） |
| `Enter` | 发送对话（输入框内）/ 搜索框跳转下一个 |
| `Shift + Enter` | 输入框换行 |
| `Ctrl/⌘ + V`（输入框内） | 粘贴图片附件 |
| `@` | 触发节点 / 关系引用下拉 |
| `↑ / ↓` | 在 `@` 下拉 / 搜索结果中切换候选 |
| `Esc` | 关闭弹窗 / 上下文菜单 / `@` 下拉 / 清除搜索 |

---

## 十四、配置与环境变量

### 14.1 后端 `application.yml`

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

### 14.2 环境变量

| 变量 | 作用 |
|---|---|
| `DEEPSEEK_API_KEY` | 注入默认 DeepSeek 模型 key |
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | 在 `application.yml` 中以 `${...}` 占位即可生效 |
| `APP_DATA_DIR` | 通过 `--app.data.dir=` 或环境变量覆盖数据目录 |

### 14.3 前端

Vite 配置 (`vite.config.ts`) 把 `/api/*` 代理到 `http://localhost:8000`。如部署到不同主机，可改代理目标，或在生产环境用 Nginx 反向代理。

前端脚本：

```json
{
  "dev":     "vite",           // 开发服务器 (HMR)
  "build":   "vite build",     // 生产构建
  "preview": "vite preview",   // 预览构建产物
  "lint":    "tsc --noEmit"    // TypeScript 类型检查
}
```

---

## 附录 A：节点 / 边视觉编码

### 节点类型

| 类型 | 颜色 | 含义 |
|---|---|---|
| `entity` | 蓝 `#3d9bff` | 实体（人、物、组织） |
| `process` | 橙 `#ffaa22` | 流程（动作、操作） |
| `event` | 绿 `#22dd88` | 事件（具体一次发生） |
| `data` | 紫 `#bb77ff` | 数据源 |
| `external` | 红 `#ff6644` | 外部系统 |
| `rule` | 粉 `#ff3399` | 规则 / 条件 |

### 边视觉

| 视觉 | 含义 |
|---|---|
| 半透明白 | 普通 inferred / derived |
| 粉色 | `rule_driven` |
| 绿色高亮 + 流光加速 | 与选中节点关联 |
| 金色虚线 + ◇ 标记 | 推演新增（`source: 'predicted'`） |

### 特殊标记

| 标记 | 含义 |
|---|---|
| 节点右上角角标 | "预测 N" — 预测步序号 |
| 节点右上角百分比 | 有效概率 / 置信度 |
| 热力图渐变 | 绿(高概率) → 黄(中) → 红(低概率) |
| 蓝色节点 | 分支对比中 A 独有 |
| 橙色节点 | 分支对比中 B 独有 |
| 紫色节点 | 分支对比中共有 |
| 金色轮廓 | 搜索当前定位目标 |

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
| force / block / probability | 推演约束：必然 / 禁止 / 概率先验 |
| effProb | 有效概率（effective probability），融合先验与上下文的综合后验 |
| TBox | 术语集（terminological box），Schema 层面的类型与属性定义 |

---

*文档版本：v2.0 · 2026-05-24*
