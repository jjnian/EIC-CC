# 推演平台 (EIC-CC)

> **基于本体图谱的 AI 业务血缘建模系统**：通过自然语言对话、文档抽取与经验库沉淀构建业务血缘图，并为图节点绑定真实数据源供血。

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
  - [5.11 多 LLM 配置与连接测试](#511-多-llm-配置与连接测试)
  - [5.12 对话历史与数据源回看](#512-对话历史与数据源回看)
  - [5.13 导出与共享](#513-导出与共享)
  - [5.14 版本管理与回滚](#514-版本管理与回滚)
  - [5.15 系统监控](#515-系统监控)
  - [5.16 用户偏好设置](#516-用户偏好设置)
- [六、前端架构详解](#六前端架构详解)
- [七、后端架构详解](#七后端架构详解)
- [八、数据存储](#八数据存储)
- [九、REST API 完整参考](#九rest-api-完整参考)
- [十、快捷键](#十快捷键)
- [十一、配置与环境变量](#十一配置与环境变量)
- [附录 A：节点 / 边视觉编码](#附录-a节点--边视觉编码)
- [附录 B：术语缩写](#附录-b术语缩写)

---

## 一、项目概述

**推演平台** 是一个面向业务血缘的 AI 辅助本体建模工具。核心能力：

| 能力 | 说明 |
|---|---|
| **本体建模** | 用自然语言或导入文档（PDF / DOCX / 图片），自动抽取实体、流程、事件、规则及其关系，构建有向图谱 |
| **多源融合** | 支持上传 PDF、DOCX、图片、Markdown、文本、CSV 等结构化 / 非结构化资料；对话框可直接粘贴图片 |
| **可解释性** | 节点 / 边都标注来源（derived / inferred）与血缘证据（evidence），可审计追溯 |
| **可回溯** | 完整撤销 / 重做历史；模型版本快照自动备份与回滚；对话里每条上传文件都可重新查看 |
| **概率推理** | 支持 force / block / probability 三种约束模式，概率约束融合贝叶斯先验 |
| **多 LLM 支持** | 10+ 主流模型提供商，OpenAI / Anthropic 双协议，即时切换 |

适用场景：业务血缘梳理、流程审计、规则建模、知识资产沉淀等。

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
│  │  │ Graph     │ │ Chat      │ │  Import / Extract    │  │ │
│  │  │ Canvas    │ │ Panel     │ │  Panels              │  │ │
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
│  │  ├── OntologyModelController — 本体 CRUD + 抽取 + 版本  │ │
│  │  ├── ConversationController  — 对话历史                  │ │
│  │  ├── ModelController      — LLM 模型列表 + 连接测试      │ │
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
| **通信** | HTTP REST + SSE（流式生成 / 抽取建图实时回执） |

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
| **Service** | 业务服务：LLM 适配、文档抽取、经验库建图、指标收集等 |
| **Model** | 9 个领域模型 / DTO |
| **Support** | 工具类：原子写入、PDF/DOCX 抽取、文件嗅探、ID 盐重写、SSE 推送等 |
| **异步线程池** | `appTaskExecutor`：核心 4 线程，最大 8，队列 50，用于抽取 / 建图 / 探索等 SSE 长任务 |

---

## 四、核心概念

| 概念 | 说明 |
|---|---|
| **本体模型 (OntologyModel)** | 一个图谱画布，包含 nodes / edges 与元信息，支持版本快照 |
| **节点类型** | `entity`（实体）、`process`（流程）、`event`（事件）、`data`（数据源）、`external`（外部系统）、`rule`（规则/条件） |
| **边来源 (source)** | `derived`（文本明示）、`inferred`（模型推断填充）、`preset`（预置）、`manual`（手动）|
| **rule_driven 边** | 由 rule 节点驱动的关系，画布中以粉色显示 |

---

## 五、功能模块总览

### 5.1 本体模型管理

| 功能 | 入口 | 说明 |
|---|---|---|
| 创建模型 | 「列表」页 → "新建模型" / 欢迎页对话提交 | 自动生成默认标题，创建后跳转到画布 |
| 列出模型 | 左侧栏 / "列表" 页 | 卡片形式显示节点 / 关系统计与更新时间 |
| 打开模型 | 点击模型卡片或左侧条目 | 加载 nodes / edges 并重置历史栈 |
| 删除模型 | 卡片右上角 "×" | 二次确认 |
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
                ├─ event: complete   — { reply, add_nodes, add_edges, questions? }
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
| 右键节点 | 弹出上下文菜单（编辑 / 删除） |
| 双击节点 | 进入编辑模式（修改名称和类型） |
| 点击空白 | 取消选中 |

#### 顶部工具栏（左 → 右）

```
[↶ 撤销] [↷ 重做]   [📥 导入]   [● N 节点] [M 关系]   [Schema] [版本] [📋 模板] [💾 存为模板] [导出] [共享] [预览]
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

#### 对比 / 血缘高亮着色

图分析面板可对节点按差异着色：蓝色 = A 独有（上游来源），橙色 = B 独有（下游影响），紫色 = 共同。

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
- **Schema 面板**：顶栏 "Schema" 按钮切换到模式视图，列出当前图谱里每种 type 的属性合集、约束信息

---

### 5.9 文档导入与实体抽取

#### 5.9.1 支持格式

| 格式 | 处理方式 |
|---|---|
| **PDF** | PDFBox 抽每页文字；扫描件（每页文字 ≤ 200 字符）自动回落到逐页渲染（110 DPI，最多 8 页）走视觉抽取 |
| **DOCX** | Apache POI 抽取段落 + 表格；表格保留二维结构 |
| **Excel**（XLSX / XLS / XLSM） | POI 逐 Sheet 渲染成表格文本（业务台账、字段口径、映射对照） |
| **图片**（PNG / JPEG / WebP / GIF） | base64 编码作为多模态 attachment 给 LLM |
| **音频** | Whisper 兼容端点转写为正文（支持 ASR 术语热词） |
| **视频**（MP4 等） | ffmpeg 抽音轨压成语音级 MP3 → 转写；未装 ffmpeg 则原样直发 |
| **SQL / DDL 脚本** | `SqlLineageExtractor` 确定性血缘解析：INSERT…SELECT / CTAS / VIEW / MERGE / UPDATE…FROM → 表级 `flows_to` 边（置信度 1.0，不经 LLM）；原文仍送 LLM 抽业务语义互补 |
| **网页 URL** | Jsoup 拉静态 HTML，启发式去掉 nav/footer/script，优先取 article/main 正文；文本 < 500 字时回落 Playwright headless Chromium 重抓（应对 Vue/React SPA） |
| **文本类**（TXT / MD / CSV / JSON …） | 直接拼入 prompt（兜底） |

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

### 5.11 多 LLM 配置与连接测试

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

对话框头部提供模型下拉选择，支持 `configId`（指定预置配置）或 `modelOverride`（覆盖模型名）。

---

### 5.12 对话历史与数据源回看

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

### 5.13 导出与共享

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

### 5.14 版本管理与回滚

后端在每次保存模型时自动创建版本快照：

| 功能 | 说明 |
|---|---|
| 自动备份 | 每次 `PUT /api/ontology-models/{id}` 时自动创建版本快照 |
| 版本列表 | 顶栏「版本」按钮展开下拉，显示每个版本的时间戳、节点数、边数、文件大小 |
| 版本回滚 | 点击某个版本 → 二次确认 → 恢复到该时间点的图谱状态 |
| 存储限制 | 每个模型最多保留 **100 个**历史版本，超出自动淘汰最旧版本 |
| 存储路径 | `~/.tuiyan/ontology-models/versions/<modelId>/` |

---

### 5.15 系统监控

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

### 5.16 用户偏好设置

设置页「偏好」Tab 提供全局配置：

| 偏好项 | 说明 | 默认值 |
|---|---|---|
| 显示边标签 | `showEdgeLabels` | true |
| 自动适应画布 | `autoFit` | true |
| 字体大小 | 图谱节点字体 | 13px |

偏好存储在 `~/.tuiyan/prefs.json`，新版本新增字段会自动合并到旧文件中（向后兼容）。

---

## 六、前端架构详解

### 6.1 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Vue | 3.5.32（Composition API + `<script setup>`） |
| TypeScript | 5.8 |
| Vite | 6.2.0（开发服务器 + 构建） |
| UI 库 | 无（全部自定义 CSS，暗色主题 + 毛玻璃效果） |
| 图标 | Unicode 符号（✦, ◈, ⚡, 📥 等） |

### 6.2 目录结构

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
│   ├── conversations.ts      # 对话历史 CRUD
│   ├── chat.ts               # LLM 对话 + SSE 流式
│   ├── explanations.ts       # 节点解释 SSE 流 + rawPrompt
│   ├── config.ts             # 前端配置
│   ├── models.ts             # LLM 模型列表 + 连接测试
│   ├── prefs.ts              # 用户偏好
├── composables/               # 可复用状态逻辑（16 个）
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
    ├── ImportDialog.vue       # 文档导入对话框
    ├── RawPromptDialog.vue    # 原始 prompt 查看
    ├── SettingsView.vue       # 设置页
    ├── views/
    │   ├── GraphView.vue      # 图谱页容器
    │   └── PreviewView.vue    # 只读预览页
    └── chat/
        ├── ChatMessageList.vue     # 消息列表
        ├── AttachmentChips.vue     # 附件标签
        ├── AttachmentPreview.vue   # 附件预览
```

### 6.3 核心数据类型

```typescript
// 图谱节点
interface OntologyNode {
  id: string;
  label: string;
  type: 'entity' | 'process' | 'event' | 'data' | 'external' | 'rule';
  x?: number; y?: number;
  source?: 'derived' | 'inferred' | 'preset' | 'manual';
  props?: { key: string; value: any; source?: string }[];
  attributes?: Record<string, any>;
  constraints?: Record<string, any>;
  confidence?: number;
  evidence?: string;
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

### 6.4 数据流

```
用户交互 → Composable 方法调用 → Ref 更新 → 模板响应式渲染
                                       ↓
                              1.2s 防抖 → PUT /api → 后端持久化
```

---

## 七、后端架构详解

### 7.1 技术栈

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

### 7.2 目录结构

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
│   ├── ModelController.java
│   ├── OntologyModelController.java
│   ├── PrefsController.java
│   └── SystemController.java
├── model/                            # 9 个领域模型
│   ├── ChatRequest.java
│   ├── ConfigResponse.java
│   ├── Constraint.java               # force / block / probability
│   ├── Conversation.java
│   ├── LlmProvider.java              # 提供商枚举 + 协议检测
│   ├── NodeExplanation.java           # 三段式解释
│   ├── OntologyModel.java
├── service/                          # 11 个业务服务
│   ├── ConversationService.java
│   ├── DocumentExtractionService.java # 多格式文档处理管线
│   ├── GraphTemplateService.java
│   ├── LlmMetricsService.java        # 调用指标收集
│   ├── LlmService.java               # 多提供商 LLM 适配器
│   ├── OntologyModelService.java      # 模型 CRUD + 版本管理
│   ├── PrefsService.java
├── support/                          # 6 个工具类
│   ├── DocxTextExtractor.java
│   ├── FileSniffer.java              # 魔术字节文件类型检测
│   ├── IdSaltRewriter.java           # ID 盐重写（防冲突）
│   ├── PdfTextExtractor.java         # PDF 文字 + 扫描件渲染
│   ├── SqlLineageExtractor.java      # SQL 确定性血缘解析（表级数据流，不经 LLM）
│   └── SsePushUtils.java             # SSE 推送 + 可取消 emitter
└── util/
    └── JsonAtomic.java               # 原子 JSON 写入
```

### 7.3 核心服务详解

#### LlmService — 多提供商 LLM 适配器

| 方法 | 说明 |
|---|---|
| `chat()` / `chatStreaming()` | 对话 + 图谱增量生成 |
| `extractFromDocuments()` | 文档实体抽取 |
| `testModelConnection()` | LLM 连接测试（最小请求 + 15s 超时） |
| `resolveConfig()` | 按 ID 或覆盖解析模型配置 |
| `truncateGraphForContext()` | 上下文窗口感知的图谱裁剪（N-hop 邻居） |
| `mergeExtractionByLabel()` | 跨文档块合并抽取结果 |

协议支持：OpenAI 兼容 + Anthropic 原生，流式 / 非流式双模式。

#### DocumentExtractionService — 文档处理管线

处理流程：
1. 魔术字节文件类型嗅探（不依赖扩展名）
2. PDF → 文字抽取（PDFBox），每页文字 ≤ 200 字符则回落到图片渲染（110 DPI，最多 8 页）
3. DOCX → 段落 + 表格抽取（Apache POI）
4. 图片 → base64 编码
5. 调用 LLM 抽取实体和关系
6. ID 盐重写（防止多次导入时 ID 冲突）

### 7.4 CORS 与安全

- 无认证：单用户本地工具
- CORS 限制：仅允许 `localhost:*` 和 `127.0.0.1:*`
- 全局异常处理：`ResourceNotFoundException` → 404，`IllegalArgumentException` → 400，`IOException` → 500

### 7.5 异步配置

抽取 / 建图 / 探索等 SSE 长任务在独立线程池 `appTaskExecutor` 中运行：
- 核心线程：4
- 最大线程：8
- 队列容量：50
- 关闭等待：20 秒

---

## 八、数据存储

后端无数据库，全部落地到 `~/.tuiyan/`（可通过 `app.data.dir` 配置修改）。

```
~/.tuiyan/
├── ontology-models/          # 一个模型一个 JSON 文件
│   ├── om_xxx.json
│   └── versions/             # 版本快照
│       └── om_xxx/           # 每个模型独立目录（最多 100 个快照）
│           ├── 1716547200000.json
│           └── 1716547300000.json
├── conversations/            # 对话历史
│   └── cv_xxx.json
├── templates/                # 图谱模板
│   └── tpl_xxx.json
└── prefs.json                # 全局偏好
```

**原子写入**：使用 `JsonAtomic` 工具类，先写 `.tmp` 文件再原子 rename，避免半截文件导致数据损坏。若文件系统不支持原子移动，则降级为 `REPLACE_EXISTING`。

**容错**：读取时跳过格式错误的 JSON 文件，不影响其他数据加载。

---

## 九、REST API 完整参考

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
| `POST` | `/api/ontology-models/{id}/build-sources` | 回写建图来源记录（`{sources:[{experienceId, contentHash}]}`，增量建图据此跳过未变更经验） |
| `POST` | `/api/ontology-models/{id}/schema-drift` | Schema 漂移检测（`{dataSourceId}`）：比对图上表/列引用与数据源最新 schema，报告失效血缘 |

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
> 新数据流以经验库为本体血缘图的全量构建入口：在经验库页点击「🧬 构建本体血缘图」，
> 后端把**所选范围（缺省全部）的经验文件**聚合分批，经文档抽取管线
> （`ExperienceOntologyService` → `ExtractionLlmService`）并行抽出节点 / 边（失败批自动重试一次），
> 再 salt 重写后供前端合并 / 另存为模型。DDL 导出经验单独分批走 schema 专用抽取规则；
> 节点/边的 `derived_source` 精确标注到来源经验（单篇批打「经验：标题」）。
> 数据库类数据源不再直接出图（旧的 `POST /api/data-sources/{id}/extract-ontology` 直出链路与
> `SchemaOntologyService` 已移除）：改为在「表」页点「⤓ 导出结构到经验库供血」把 DDL 沉淀成经验文件参与建图。
> 对应建图 SSE 接口 `POST /api/experiences/extract-ontology`
> （事件序列：`step`* → `complete{nodes,edges,reply,salt,sourceCount}`）。
>
> **推断血缘的数据验证**：对无 FK、按命名推断的血缘边，边详情面板「🔬 数据验证」可做值包含检验
> （`POST /api/data-sources/{id}/verify-containment`，只读+采样+超时受限），把匹配率写回边的置信度与证据。
>
> **图体检**：图分析面板「体检」Tab 提供血缘健康度总览、推断边批量验证、
> 冲突检测（方向矛盾边对 / 疑似重复节点 / 血缘环路，前端纯内存计算、可点击定位、人工裁决）、
> Schema 漂移检测（`POST /api/ontology-models/{id}/schema-drift`，比对图上表/列引用与库最新结构）。
>
> **第二阶段·数据供血绑定**：图建好后，在节点详情面板「供血」页把节点绑定到数据源的表（可选 WHERE 过滤），
> 运行时按绑定取数为节点供血。对应 `node_data_binding` 表与 `/api/node-bindings` 端点（含 `/{id}/fetch` 取数）。

索引管线参考 Cursor 的做法（仅经验库启用）：

- **结构感知切块**：按 Markdown 标题层级切小节，每块前置标题面包屑作为上下文（`TextChunker.chunkStructured`）。
- **内容哈希增量**：块按 `content_hash` 比对，未变化的块复用旧向量，只对新增/改动块调 embedding，省调用费。
- **ANN 检索（可选）**：装了 pgvector 扩展时走 HNSW 近似最近邻（`embedding_vec <=> ?::vector`）；
  没装则回退 TEXT 向量的暴力余弦。pgvector 在应用就绪后由 `PgVectorSupport` **尝试性启用**，
  失败不影响启动（故意不放进 `init.sql`，因其 `continue-on-error: false`）。TEXT `embedding` 列始终是真值来源。
- **限流友好**：embedding 调用带 429/5xx 指数退避重试；保存即自动索引与批量/补索引均提交到**有界线程池排队**
  （并发度 `app.embedding.concurrency`，默认 3），大量文件一次性上传也不会瞬间打爆 embedding 服务。
- **启动自动补索引**：应用就绪后 `IndexBackfillRunner` 自动把历史 `index_status≠indexed` 且有正文的经验
  （及 `file_stored` 数据源）排队重建，解决「配置 embedding 晚于内容上传」的存量数据搜不到的问题——
  重启即跟上，无需手动逐条触发。开关 `app.embedding.backfill-on-startup`（默认 `true`）；未配置 embedding 时静默跳过。
  也可随时调 `POST /api/experiences/reindex-all` 手动全量补建，用 `GET /api/experiences/index-summary` 查进度。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/experiences?workspaceId=` | 列出工作空间下经验（按更新时间倒序，含 `indexStatus`） |
| `GET` | `/api/experiences/{id}` | 取单条经验（含正文） |
| `POST` | `/api/experiences` | 新建经验（`{title, content?, tags?}`），自动建索引 |
| `PUT` | `/api/experiences/{id}` | 更新经验（字段可选，null 不改动），自动重建索引 |
| `DELETE` | `/api/experiences/{id}` | 删除经验（索引随外键级联清理） |
| `POST` | `/api/experiences/{id}/reindex` | 手动重建向量索引（embedding 配置变更后补建） |
| `GET` | `/api/experiences/{id}/index-status` | 查询索引状态 + 文本块数量 |
| `POST` | `/api/experiences/reindex-all` | 全量补索引（`?force=true` 连已索引的也重建），后台排队，返回调度概况 |
| `GET` | `/api/experiences/index-summary` | 索引状态汇总（经验总数 + 各 `index_status` 计数，查补索引进度） |
| `POST` | `/api/experiences/file` | 上传文件建经验（PDF/Word/TXT/MD 抽正文，音频走 ASR，视频抽音轨转写） |
| `POST` | `/api/experiences/from-ddl` | 任意数据源抽取为经验（`{dataSourceId}`）：库类导 DDL（origin=ddl，建图走 schema 规则）、文件/音视频取转写正文、HTTP 接口导配置+响应（origin=datasource） |
| `POST` | `/api/experiences/web-research` | **联网调研业务知识（SSE）**：搜索主题→抓取网页→LLM 归纳成《业务知识文档》存为经验（`{topic, maxPages?}`） |
| `POST` | `/api/experiences/extract-ontology` | **聚合经验库构建本体血缘图（SSE）**；体可选 `experienceIds` 指定范围、`incrementalModelId` 增量建图（跳过内容未变经验）、`hint` 额外要求 |
| `GET` | `/api/experiences/{id}/file` | 预览/下载上传原件（`?download` 附件下载，`?wsId` 兜底鉴权） |
| `PUT` | `/api/experiences/{id}/folder` | 把经验移动到文件夹（`{folderId}`，null=根） |

#### 经验库文件夹

工作空间内任意层级归类（`parent_id` 自引用，与数据源文件夹结构平行）；经验通过 `folder_id` 归属文件夹，缺省在根目录。侧栏经验库区段渲染为可折叠的文件夹树，右键可建文件夹 / 移动 / 重命名 / 删除（删文件夹时其中的子文件夹与经验上提到父级，不丢数据）。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/experience-folders?workspaceId=` | 列出工作空间下全部文件夹（扁平，前端拼树） |
| `POST` | `/api/experience-folders` | 新建文件夹（`{name, parentId?}`，parentId 省略=根） |
| `PUT` | `/api/experience-folders/{id}` | 重命名 / 移动（`{name?, parentId?}`；出现 parentId 即视为移动，含环检测） |
| `DELETE` | `/api/experience-folders/{id}` | 删除文件夹（子文件夹与经验上提到父级） |

#### 自动探索系统了解业务（行为式抽取）

让一个「探索智能体」用无头浏览器**像人一样只读操作**一个 web 系统（点菜单、开页面、读表格/表单），自动摸清功能、反推业务，再把功能地图**归纳成一份《业务说明文档》**（业务概述 / 业务模块与功能 / 业务对象与数据字典 / 关键业务流程 / 业务规则；附探索明细）落成一篇 `origin=explore` 的经验，可直接作为业务文件归档并走现有建图。**纯文本驱动**（页面编码成可访问性/DOM 文本快照喂给 LLM，不依赖视觉模型）；**只读护栏**默认拦截「删除/提交/支付/新建」等会改数据的元素。入口在经验库页「🧭 自动探索系统了解业务」。

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/explore/run` | 启动探索（SSE：`step`*→`complete{经验}`）。体：`{baseUrl, maxSteps?, readOnly?(默认 true), storageState?, modelOverride?, configId?}` |

> 前置依赖：需安装 Playwright Chromium 内核——在 `backend/` 执行
> `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`。
> 安全提醒：建议指向**测试/预发环境 + 测试账号**；只读模式尽量保证零副作用，但请勿对生产系统关闭只读。

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
| `complete` | `{ reply, add_nodes, add_edges, questions? }` | 对话完成，含新增节点/边；`questions` 为可选澄清问题组（一次最多 4 个、单题可多选），前端渲染为带选项的问题卡片让用户点选 |
| `error` | 错误描述 | 生成失败 |

---

## 十、快捷键

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

### 特殊标记

| 标记 | 含义 |
|---|---|
| 蓝色节点 | 对比/血缘高亮中 A 独有（上游来源） |
| 橙色节点 | 对比/血缘高亮中 B 独有（下游影响） |
| 紫色节点 | 对比/血缘高亮中共有 |
| 金色轮廓 | 搜索当前定位目标 |

---

## 附录 B：术语缩写

| 缩写 | 含义 |
|---|---|
| SSE | Server-Sent Events，单向流式 HTTP |
| DAG | Directed Acyclic Graph，有向无环图 |
| LR / TB | 布局方向：Left→Right / Top→Bottom |
| TBox | 术语集（terminological box），Schema 层面的类型与属性定义 |

---

*文档版本：v2.0 · 2026-05-24*
