# EIC-CC 综合功能开发计划 v2

> 编写日期：2026-05-23
> 来源：综合 `FEATURE-PROPOSALS.md`（全平台视角）与 `PREDICTION_ROADMAP.md`（推演模块视角），结合现有前后端代码结构去重融合。
> 范围：覆盖 6 个月（约 24 周）的功能开发计划 + Phase 1 关键功能的详细设计。

---

## 目录

1. [综合分析与融合策略](#1-综合分析与融合策略)
2. [全面开发计划（4 期 24 周）](#2-全面开发计划4-期-24-周)
3. [Phase 1 关键功能详细设计](#3-phase-1-关键功能详细设计)
4. [横切关注点与技术决策](#4-横切关注点与技术决策)
5. [风险与对策](#5-风险与对策)
6. [交付节奏建议](#6-交付节奏建议)

---

## 1. 综合分析与融合策略

### 1.1 两份文档的关系

| 维度 | FEATURE-PROPOSALS | PREDICTION_ROADMAP |
|---|---|---|
| 视角 | 全平台（建模 + 推演 + 协作 + 基建） | 仅推演模块深耕 |
| 颗粒度 | 大功能群 | 单点细化（含交互草图） |
| 优先级 | 第一期 / 第二期 / 第三期 / 远期 | Now / Near / Later |

**重合点（需合并去重）：**

- 推演可视化增强（FP §2.3） ↔ Sankey / 时间轴 / 热力图（PR §5.1 / §5.2 / §5.3）
- 推演对比增强（FP §3.3） ↔ 多分支对比 + 叠加视图（PR §4.5 / §5.4）
- 模板库（FP §3.4） ↔ 参数化 / 行业模板（PR §7.1 / §7.4）
- 导出丰富化（FP §3.6） ↔ PDF / PPT / PNG（PR §12.1–12.3）
- 推演评估校准（FP §4.6） ↔ 回测 + 校准（PR §6.1 / §6.2）
- 实时协作（FP §4.1） ↔ 步骤评论 + 多人（PR §8.2 / §8.3）

**互补点（彼此缺失）：**

- 仅 FP 有：节点直接编辑、图谱搜索、对话上下文优化、i18n、数据库迁移、用户认证、容器化、自动化测试、性能虚拟化
- 仅 PR 有：多链 beam、蒙特卡洛、反事实推演、单步"为什么/换一个"、分支提升回主干、概率约束、原始 prompt 查看、token 成本统计

### 1.2 融合后的产品主线

围绕三条价值主线编排，避免按文档分别推进造成的重复造轮子：

> **主线 A：把推演价值"带得走"**
> 提升回主干 → 分支链接分享 → PDF/PPT 报告 → 真实结果反馈 → 历史回测闭环

> **主线 B：让用户少依赖 AI、多掌控图谱**
> 节点直接编辑 → 图谱搜索 → 批量操作 → 版本历史 → 参数化模板

> **主线 C：把推演"做深做透"**
> 概率约束 → 多链 beam → 单步重生/解释 → Sankey/时间轴/热力图 → 蒙特卡洛 → Bayesian 更新

---

## 2. 全面开发计划（4 期 24 周）

### Phase 1：核心体验补齐 + 推演闭环（Week 1–6）

| # | 功能 | 来源 | 工期 | 价值 |
|---|---|---|---|---|
| P1-1 | 图谱搜索与定位（Ctrl+F） | FP §2.2 | 2d | ⭐⭐⭐⭐ |
| P1-2 | 模型连接测试 | FP §2.5 | 2d | ⭐⭐⭐⭐ |
| P1-3 | 节点直接编辑 + 属性管理 | FP §2.1 | 5d | ⭐⭐⭐⭐⭐ |
| P1-4 | **分支提升回主干** | PR §4.1 | 4d | ⭐⭐⭐⭐⭐ |
| P1-5 | **分支链接分享** | PR §8.1 | 1d | ⭐⭐⭐⭐ |
| P1-6 | **真实结果标记**（✓/✗/?） | PR §3.6 | 2d | ⭐⭐⭐⭐ |
| P1-7 | 单步"为什么？" | PR §3.2 | 2d | ⭐⭐⭐⭐ |
| P1-8 | 原始 prompt 查看 + token 统计 | PR §13.1 / §13.2 | 2d | ⭐⭐⭐ |
| P1-9 | 参数化模板（`{占位符}`） | PR §7.1 | 3d | ⭐⭐⭐⭐ |
| P1-10 | 概率约束（force/block → probability） | PR §2.1 | 3d | ⭐⭐⭐⭐ |
| P1-11 | 容器化部署（Dockerfile + compose） | FP §5.6 | 3d | ⭐⭐⭐⭐ |

**P1 验收标准：** 用户能完整走通"建图 → 推演 → 标真实结果 → 提升回主干 → 生成分享链接"闭环；任意配置的模型可一键测试。

### Phase 2：推演深度 + 可视化升级（Week 7–12）

| # | 功能 | 来源 | 工期 |
|---|---|---|---|
| P2-1 | **多链 beam（Top-K）** | PR §1.1 | 8d |
| P2-2 | 单步"换一个" | PR §3.3 | 5d |
| P2-3 | LLM 自批判（Self-Critique） | PR §1.6 | 4d |
| P2-4 | **Sankey 流图视图** | PR §5.1 | 6d |
| P2-5 | **置信度热力图** | PR §5.3 + FP §2.3 | 2d |
| P2-6 | 推演动画回放 | FP §2.3 | 3d |
| P2-7 | 图谱版本历史 + 回滚 | FP §3.1 | 8d |
| P2-8 | 批量节点操作（框选/对齐/复制） | FP §3.2 | 5d |
| P2-9 | 分支森林视图 | PR §4.4 | 5d |
| P2-10 | 分支注释 + 标签 + 克隆 | PR §4.2 / §4.6 / §4.7 | 3d |
| P2-11 | PDF 报告导出 | PR §12.1 / FP §3.6 | 6d |
| P2-12 | 对话上下文摘要 | FP §2.4 | 4d |
| P2-13 | 自动化测试基线（JUnit + Vitest + 1 条 E2E） | FP §5.3 | 6d |

### Phase 3：协作前置 + 评估闭环（Week 13–18）

| # | 功能 | 来源 | 工期 |
|---|---|---|---|
| P3-1 | 数据库迁移（SQLite/Postgres） | FP §5.1 | 10d |
| P3-2 | 用户认证（本地账号 + OIDC） | FP §5.2 | 8d |
| P3-3 | 蒙特卡洛多采样 + 经验概率 | PR §1.2 | 6d |
| P3-4 | 反事实推演（do-block） | PR §1.3 | 3d |
| P3-5 | 时间窗约束 + 时间轴视图 | PR §2.4 + §5.2 | 8d |
| P3-6 | 多分支并排对比（3+） | PR §4.5 | 5d |
| P3-7 | 两分支叠加 diff 高亮 | PR §5.4 / FP §3.3 | 4d |
| P3-8 | 步骤评论 | PR §8.2 | 4d |
| P3-9 | 风险告警（邮件/Webhook） | PR §11.3 / §11.4 | 5d |
| P3-10 | 结构化日志 + LLM 调用监控 | FP §5.5 | 5d |
| P3-11 | 行业模板库（5–8 个） | PR §7.4 | 8d |
| P3-12 | 国际化（zh / en） | FP §3.5 | 5d |

### Phase 4 / 远期愿景（Week 19+）

- **历史回测 + 校准曲线**（PR §6.1 / §6.2，依赖 P1-6 真实结果数据积累）
- **Bayesian 增量更新**（PR §1.5，依赖回测）
- **实时多人协作**（FP §4.1 / PR §8.3，依赖 P3-1 / P3-2，需 CRDT/Yjs）
- **定量预测**（PR §10.1，节点 schema 扩展 cost/duration）
- **外部数据源 + 数据驱动触发**（FP §4.5 + PR §11.2）
- **画布虚拟化 + Web Worker 布局**（FP §5.4，触发条件：500+ 节点用户出现）
- **移动端 PWA**（FP §4.7）
- **规则引擎**（FP §4.3，决策视场景需求再启动）

---

## 3. Phase 1 关键功能详细设计

挑选 P1 中影响最大、跨多文件的 6 个功能给出可落地的详细设计。

### 3.1 P1-3 节点直接编辑与属性管理

#### 数据模型

现有 `NodeInfo` 已支持 `props: Map<String, Object>`，无需 schema 改动。

#### 后端

新增 REST 端点（`OntologyModelController`）：

```
PATCH /api/models/{modelId}/nodes/{nodeId}
  body: { label?, type?, description?, props?, position? }
  → 200 NodeDTO

DELETE /api/models/{modelId}/nodes/{nodeId}
  → 204（级联删除相连边）

POST /api/models/{modelId}/edges
  body: { from, to, label, type?, source?, confidence? }
  → 201 EdgeDTO

PATCH /api/models/{modelId}/edges/{edgeId}
DELETE /api/models/{modelId}/edges/{edgeId}
```

**关键点：**

- Service 层加 `validateNotPredicted(nodeId)`：仅允许编辑 `source != predicted` 的节点（推演节点必须先"提升回主干"才能编辑，对应 P1-4）
- 所有写操作返回新 `revision` 号，前端用于乐观锁
- 复用现有防抖 1.2s 自动保存机制；编辑操作走立即保存

#### 前端

- `NodeInfo.vue` 增加"编辑模式"开关；label/type/description 改为 `<input>`，失焦触发 PATCH
- `props` Tab 增加表格编辑器（key/value/类型下拉），支持增删行
- `GraphCanvas.vue` 右键菜单新增三项：`编辑节点 / 删除节点 / 添加关系`
- "添加关系"：点击源节点后进入"连线模式"，鼠标拖出橡皮筋线，目标节点 hover 高亮
- 所有编辑操作通过 `useUndoStack` composable 入栈（已有 50 步 undo）

#### 工期拆分

| 子任务 | 工期 |
|---|---|
| 后端 PATCH/DELETE API + 校验 | 1.5d |
| 后端单测 + 集成测 | 1d |
| NodeInfo 编辑 UI | 1d |
| props 表格编辑器 | 0.5d |
| 画布右键菜单 + 连线模式 | 1d |

---

### 3.2 P1-4 分支提升回主干（Merge Branch to Trunk）

#### 价值

当前推演节点带 `source=predicted` 标记，停留在子分支永远是"假设"。提升后变成可被引用、可被再推演的图谱真实节点，闭合"推演 → 决策 → 入库"环路。

#### 后端设计

```
POST /api/models/{modelId}/branches/{branchId}/promote
  body: {
    nodeIds: string[],     // 要提升的节点
    edgeIds: string[],     // 要提升的边
    targetSource: "derived" | "confirmed",
    overwriteOnConflict: boolean
  }
  → 200 {
    promotedNodes: NodeDTO[],
    promotedEdges: EdgeDTO[],
    skipped: { id, reason }[],
    newTrunkRevision: number
  }
```

**算法（`BranchPromotionService`）：**

1. 加载分支 + 祖先链合并出"分支完整快照"
2. 按依赖拓扑排序：先节点，再边；边的 from/to 必须都在 trunk 或在本次提升集合中
3. 同名/同 id 冲突处理：
   - 节点：若 trunk 已存在同 `canonicalKey`（label+type 归一化）→ 合并 props（trunk 优先），不创建新节点
   - 边：若 trunk 已存在 `from-to-label` 三元组 → 跳过
4. 写入 trunk，`source` 改为 `derived`，保留 `promotedFromBranch`、`promotedAt`、`originalPredictionId` 元数据
5. 在被提升的分支节点上打 `promotedTo=trunk` 标记（不删除分支，保留审计）
6. 触发 trunk 自动保存 + 创建版本快照（与 P2-7 联动）

#### 前端

- `BranchPanel.vue` 选中分支后：节点列表前出现复选框；底部新增 `⬆ 提升到主干` 按钮
- 弹窗预览：
  - 待提升节点/边树形列表
  - 冲突预警（红/黄标）
  - `targetSource` 单选（derived / confirmed）
  - 「确认提升」后调用 API，成功后 toast 跳转到 trunk

#### 工期：4d（后端 2.5d + 前端 1.5d）

---

### 3.3 P1-5 分支链接分享

#### 设计要点

复用已有 `PreviewView`，扩展 URL 参数：

```
/preview/{modelId}?branch={branchId}&focus={nodeId}&readonly=1
```

- 后端 `OntologyModelController.getModel()` 增加可选 `branchId` 参数：合并祖先链后返回完整快照
- 前端 PreviewView 解析 query → 调对应 API → 渲染时锁定为只读 + 默认聚焦
- 顶部菜单增加 `🔗 分享此分支` 按钮 → 复制 URL 到剪贴板

**注意：** 在没有用户认证（Phase 3）之前，URL 可被任何人访问。在 Phase 3 引入 `share_token` 机制（短 token + 可设置有效期/密码）。

#### 工期：1d

---

### 3.4 P1-6 真实结果标记 + P1-7 单步"为什么？"

#### 数据模型扩展

在推演节点（`PredictionStep` / `PredictionNode`）增加：

```java
public enum Outcome { UNKNOWN, OCCURRED, NOT_OCCURRED }

public class PredictionStep {
  // ... existing fields
  private Outcome outcome = Outcome.UNKNOWN;
  private Instant outcomeMarkedAt;
  private String outcomeNote;          // 用户备注
  private String explanationCache;     // §3.2 解释缓存
}
```

#### 后端

```
PATCH /api/predictions/{predictionId}/steps/{stepId}/outcome
  body: { outcome, note? }
  → 200

POST /api/predictions/{predictionId}/steps/{stepId}/explain
  body: { modelOverride? }
  → SSE 流，逐 token 返回解释，结束时写入 explanationCache
```

**解释 prompt 模板：**

```
你是一个推演分析助手。在以下因果链上下文中，请简要解释为什么"{当前步骤标签}"会发生：
- 触发节点：{triggered_by 列表 + 各自的 label}
- 前置链路：{step 1..i-1 摘要}
- 当前置信度：{confidence}
- 本体相关规则：{相邻 rule_driven 边}
请用 3-5 句话说明：依据是什么，假设有哪些，可能的反例是什么。
```

#### 前端

- `ScenarioTimeline.vue` 每个步骤卡片右上角加三个状态按钮：`✓ / ✗ / ?`
- 卡片底部加 `🔍 为什么？` 链接 → 展开 SSE 流式区域
- 已有解释缓存时直接显示，不再调 LLM
- 数据看板（P3 阶段）：统计某模型/某分支的 outcome 命中率

#### 工期：P1-6 = 2d，P1-7 = 2d

---

### 3.5 P1-9 参数化模板

#### 模板 schema 扩展

```java
public class PredictionTemplate {
  // ... existing
  private List<TemplateVariable> variables;
}

public class TemplateVariable {
  private String key;          // e.g. "supplier"
  private String label;        // e.g. "供应商名称"
  private String type;         // text / number / select / nodeRef
  private String defaultValue;
  private List<String> options;  // type=select 时
  private String description;
}
```

`prompt` 字段支持 `{{key}}` 占位符（Mustache 风格，避免与 LLM JSON 冲突）。

#### 后端

- `PredictionTemplateService.renderPrompt(template, vars)`：替换占位符，未提供的变量用 default；缺失必填变量返回 400
- 创建模板时自动扫描 prompt 中的 `{{...}}` 提取变量列表，UI 上让用户补充类型与默认值

#### 前端

- `TemplateLibraryDialog.vue` 选中含变量的模板 → 弹出 `TemplateVariableForm.vue` 填表
- 表单组件根据 `type` 渲染不同输入控件（`nodeRef` 类型从当前图谱节点中选）
- "应用"后填入推演面板的 prompt 字段，用户仍可微调

#### 工期：3d

---

### 3.6 P1-10 概率约束

#### 数据模型

已存在的 `Constraint.mode` 增加枚举值 `PROBABILITY`，新增 `probability: double (0–1)` 字段。

#### 推演 prompt 注入

原 system prompt 中约束段落：

```
约束:
- {节点A}: 必然发生
- {节点B}: 禁止出现
```

扩展为：

```
约束:
- {节点A}: 必然发生 (force)
- {节点B}: 禁止出现 (block)
- {节点C}: 先验概率 0.70 ± 0.10  (probability)
请在推演时把先验概率作为初值，结合上下游证据用贝叶斯式更新。
```

#### 前端

- `PredictionConfig.vue` 约束编辑器：模式下拉新增"概率"选项 → 显示滑动条 + 数值输入框
- 视觉：约束卡片底部用 mini 进度条展示概率值

#### 工期：3d（含后端模型迁移、prompt 调整、UI、回归测试）

---

## 4. 横切关注点与技术决策

### 4.1 数据兼容性

- Phase 1 仍用 `~/.tuiyan/*.json` 文件存储；所有新字段加 `@JsonInclude(NON_NULL)` 保证旧文件能读
- Phase 3 数据库迁移时提供 `JsonFileToDbMigrator` 一次性导入工具

### 4.2 SSE 协议演进

现有事件类型：`prediction.step / prediction.done / prediction.error`。Phase 2 多链 beam 引入新事件：

```
beam.candidate { chainId, stepIndex, ... }
beam.commit    { chainId }       // 用户选定主链
```

向后兼容：客户端按 `event` 字段路由，旧客户端忽略 `beam.*`。

### 4.3 模型抽象

当前 `LlmService` 有 `chat()` 与 `stream()`。Phase 2 增加：

- `chatBatch(N)`：并发跑 N 次同请求（用于蒙特卡洛 / 自一致）
- `chatWithCritic(primaryModel, criticModel)`：两阶段（自批判）

抽象提取到 `LlmOrchestrator`，避免散落在 controller 里。

### 4.4 性能预警

- P2-1 多链 beam 会让 token 消耗 ×K；P3-3 蒙特卡洛 ×N。两个一起开会 ×K×N。**UI 必须显示预估成本，并强制用户确认**。
- 大图谱（500+ 节点）的画布性能问题在 Phase 4 才动手；P2 阶段先在 `GraphCanvas` 加 FPS 监控埋点收集数据。

### 4.5 测试策略（P2-13 起强制）

| 层级 | 工具 | 覆盖目标 |
|---|---|---|
| 后端单测 | JUnit 5 + Mockito | `BranchPromotionService`、`PredictionAggregator`、`TemplateRenderer` |
| 后端集成 | Spring Boot Test + WebTestClient | 所有新 REST + SSE 端点 |
| 前端单测 | Vitest | composables、纯函数 |
| 前端组件 | Vue Test Utils | NodeInfo 编辑、约束编辑器 |
| E2E | Playwright | 闭环主路径：建图 → 推演 → 标结果 → 提升 → 分享 |

CI（GitHub Actions）在 PR 触发 lint + 单测 + 关键 E2E；main 合并触发完整 E2E。

---

## 5. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 多链 beam + 蒙特卡洛叠加导致成本爆炸 | 高 | P2 默认 K=1；P3 才放开蒙特卡洛；预估成本预算护栏 |
| 数据库迁移破坏现有用户数据 | 高 | 双写过渡期 2 个版本；提供回滚脚本；自动备份 |
| 节点直接编辑与 AI 同时改图引发冲突 | 中 | 引入 revision 乐观锁；冲突时 UI 弹出 diff 让用户决策 |
| 真实结果数据量不足，回测无法落地 | 中 | P1-6 上线后埋点观察标注率，<5% 时延后 Phase 4 |
| 实时协作设计复杂度被低估 | 高 | Phase 4 评估 Yjs/Automerge 现成方案；最差降级为"乐观锁 + 冲突提醒" |

---

## 6. 交付节奏建议

- **每周 demo + 用户访谈**：每个 Phase 末邀 3–5 个真实用户演示，根据反馈调整下个 Phase 的优先级
- **埋点门禁**：每个上线功能 3 个月内使用率 <5% 自动进入"下架候选"列表
- **文档同步**：每完成一个 P1/P2 项更新 `README.md` 的能力矩阵
- **版本号节奏**：Phase 1 = v1.0，Phase 2 = v1.5，Phase 3 = v2.0，Phase 4 = v3.0

---

*文档版本：v2.0 · 基于 FEATURE-PROPOSALS.md 与 PREDICTION_ROADMAP.md 综合编写*
