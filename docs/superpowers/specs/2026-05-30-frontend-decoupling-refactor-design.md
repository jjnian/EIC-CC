# 前端解耦重构设计

> 日期：2026-05-30
> 目标：降低前端 6 个大文件的耦合度、提升扩展性，重构完成后统一检查并修复 bug。

## 一、背景与目标

`frontend/src` 共约 18k 行，其中 6 个文件明显过大、职责混杂，阻碍维护与扩展：

| 文件 | 总行 | script | template | style | 主要病灶 |
|---|---|---|---|---|---|
| App.vue | 1346 | 624 | ~370 | 352 | composable 接线 + ~80 行 alias 透传 + 3 个内联弹窗 + 全局 CSS |
| GraphCanvas.vue | 918 | 635 | ~280 | 0 | 全部交互逻辑堆在 script |
| SettingsView.vue | 807 | 61 | 58 | 684 | 几乎全是 CSS，逻辑很轻 |
| ImportDialog.vue | 775 | 299 | 202 | 270 | 文件处理 + SSE 抽取 + 去重三块耦合 |
| PredictDialog.vue | 768 | 227 | 180 | 357 | 模板管理 + 约束检测 + 表单混在一起 |
| GraphAnalysisPanel.vue | 630 | 192 | ~290 | ~150 | 统计 computed 堆叠 + template 长 |

**核心目标**：每个大文件按"单一职责"拆分为更小的组件与 composable，使得：
- 能不读内部实现就理解一个单元做什么；
- 改内部实现不破坏调用方（接口稳定）；
- 后续新增功能时改动面小、定位快。

**非目标**：不重写后端（后端已做过分层重构，相对健康）；不在重构阶段顺手改业务逻辑。

## 二、约束与验证策略

- 前端**无任何测试**，构建用 `vite build`，类型检查用 `npm run lint`（即 `tsc --noEmit`）。
- 采用的验证策略：**类型检查 + 构建 + 手测**。
  - 每拆完一个文件，跑 `npm run lint` + `vite build`，全绿才继续。
  - 关键流程由用户手动点验。
- **重构 = 行为不变地改结构**。每个阶段只做：抽 composable / 拆子组件 / 移 CSS，不改任何业务逻辑。
- **一个文件一个 commit**，出问题可精确回滚。
- **重构阶段绝不顺手修 bug**。发现的疑似 bug 记录到清单（见第五节），留到最后统一处理，保证重构 diff 纯净。

## 三、整体策略

按"由易到难"排序，先用最简单的文件验证拆分流程，把逻辑最密的 GraphCanvas 放中后段，最后处理协调中枢 App.vue。每阶段独立可验证、独立可回滚。

## 四、分阶段方案

### 阶段 1 — SettingsView.vue（807 → ~130）

- **病灶**：684 行 CSS，逻辑仅 60 行。
- **做法**：
  - `<style>` 整段抽到独立 CSS 文件（或下沉到各 Tab 子组件的 scoped style）；
  - `monitor` 数据加载（`loadMonitor` + `healthData`/`metricsData`）抽进 `useSystemMonitor` composable；
  - 本地 toast 逻辑保持不动（已足够小）。
- **目的**：验证拆分流程，风险极低。

### 阶段 2 — GraphAnalysisPanel.vue（630 → ~250）

- **病灶**：全图统计/节点分析/路径查询的 computed 堆在一起，template 长。
- **做法**：
  - 统计计算（nodeTypeStats / edgeLabelStats / edgeSourceStats / degreeRank / isolatedNodes 等）抽成 `useGraphStats` composable；
  - 3 个 tab 的 template 拆成 3 个子组件（全图统计 / 节点分析 / 路径查询）。

### 阶段 3 — PredictDialog.vue（768 → ~300）

- **病灶**：模板 CRUD + 约束冲突检测 + 表单状态混在一起。
- **做法**：
  - 模板增删改查抽 `useHypothesisTemplates`；
  - 约束冲突检测抽 `useConstraintConflicts`；
  - CSS 移出到独立文件。

### 阶段 4 — ImportDialog.vue（775 → ~300）

- **病灶**：文件处理 + SSE 流式抽取 + 去重选择三块逻辑耦合。
- **做法**：
  - 文件拾取/校验/拖拽（addFile / onPick / onDrop / fmtSize）抽 `useImportFiles`；
  - SSE 抽取流程（extractFromFilesStream + buildSteps + sseHandle 生命周期）抽 `useExtractStream`；
  - 去重对照（normLabel + displayedNodes/Edges + 选择集）抽 `useImportDedup`。

### 阶段 5 — GraphCanvas.vue（918 → ~350，最难）

- **病灶**：635 行交互逻辑全堆在 script。
- **做法**：按现有分区注释抽成多个 composable：
  - `useCanvasViewport`：缩放 / 平移 / 滚轮 / 视口裁剪（viewport culling）；
  - `useNodeDrag`：节点拖拽；
  - `useBoxSelect`：框选 / 多选；
  - `useCanvasContextMenu`：右键菜单 + 加节点表单；
  - `useNodeColoring`：热力图 / 差异着色。
  - 搜索已抽出（useGraphSearch），保持不动。
- **目的**：收益最大，扩展性提升最明显。

### 阶段 6 — App.vue（1346 → ~450）

- **病灶**：composable 接线 + ~80 行 alias 透传 + 3 个内联弹窗 + 352 行全局 CSS。
- **做法**：
  - 3 个内联弹窗（节点编辑 / 关系编辑 / 模板库）抽成独立组件；
  - 消除 alias 透传：template 直接引用 `editor.xxx` / `versionTpl.xxx` 等，删除中间 `const xxx = editor.xxx` 行；
  - 352 行全局 `<style>` 移到 `app.css`；
  - 视图导航 + 弹窗开关状态可抽 `useAppShell`（视情况）。

### 阶段 7 — Bug 检查与修复（重构完成后）

- 对**重构后的代码**跑 max-effort bug 审查（5 角度找问题）；
- 合并第五节记录的疑似 bug 清单；
- 统一修复并验证（lint + build + 手测）。

## 五、重构期间记录的疑似 Bug 清单

> 重构阶段只记录、不修复，留到阶段 7 统一处理。

1. **App.vue:581** — `currentModelTitle.value = '渚涘簲閾炬湰浣撳浘'` 是 GBK 乱码，应为 `'供应链本体图'`（参见 App.vue:79 的正常写法）。删除当前图谱后标题会显示乱码。

（后续阶段发现的 bug 继续追加到此清单。）

## 六、风险与回滚

- **最大风险**：无测试网，纯靠 tsc + build + 手测，可能漏掉运行时行为变化（如 Vue 响应式丢失、emit 事件名拼错）。
- **缓解**：
  - 抽 composable 时严格保持返回值/参数签名一致；
  - 每文件单独 commit；
  - alias 删除是最易出错的一步（template 引用需同步改），该步骤后重点手测。
- **回滚**：任一阶段验证失败，`git revert` 对应 commit 即可，不影响其他阶段。
