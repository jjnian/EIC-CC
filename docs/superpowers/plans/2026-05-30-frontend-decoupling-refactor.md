# 前端解耦重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将前端 6 个过大文件按单一职责拆分为更小的组件与 composable，降低耦合、提升扩展性；重构完成后统一检查并修复 bug。

**Architecture:** 纯结构重构（行为不变）。逐文件抽取 composable（业务逻辑）与子组件（template 片段），并把巨型 `<style>` 移到独立 CSS。由易到难推进：SettingsView → GraphAnalysisPanel → PredictDialog → ImportDialog → GraphCanvas → App，最后做 bug 检查。

**Tech Stack:** Vue 3.5 `<script setup lang="ts">`、Vite 6、TypeScript（`tsc --noEmit`）。无测试框架。

**验证方式（每个 Task 通用）：** 无单元测试，靠 `npm run lint`（= `tsc --noEmit`）+ `npm run build`（= `vite build`）+ 用户手测。每个 Task 完成后必须两条命令全绿才提交。

**重构铁律：**
- 抽 composable 时严格保持参数 / 返回值签名一致，调用方只是"换地方调用"，行为零变化。
- 不顺手改任何业务逻辑。发现 bug → 追加到本文件末尾「疑似 Bug 清单」，留到 Task 7。
- 一个 Task 一个 commit，commit message 用 `refactor(<file>): ...`。
- 删除 alias 透传（App.vue）是最易错步骤，改完重点手测。

---

## Task 1: 拆分 SettingsView.vue（807 → ~130）

**Files:**
- Create: `frontend/src/composables/useSystemMonitor.ts`
- Create: `frontend/src/components/settings/settings-view.css`
- Modify: `frontend/src/components/SettingsView.vue`

- [ ] **Step 1: 抽 useSystemMonitor composable**

把 `SettingsView.vue` 当前的监控逻辑（`healthData` / `metricsData` ref 与 `loadMonitor` 函数，见 SettingsView.vue:46-56）原样搬进新文件。签名：

```ts
// frontend/src/composables/useSystemMonitor.ts
import { ref } from 'vue';
import { request } from '../api/http';

export function useSystemMonitor() {
  const healthData = ref<any>(null);
  const metricsData = ref<any>(null);

  const loadMonitor = async () => {
    try {
      healthData.value = await request('/api/system/health');
      metricsData.value = await request('/api/system/metrics/llm');
    } catch (e) {
      console.error('Failed to load monitor data', e);
    }
  };

  return { healthData, metricsData, loadMonitor };
}
```

- [ ] **Step 2: 把 SettingsView 的 `<style>` 整段移到 settings-view.css**

把 SettingsView.vue 第 123 行之后的整个 `<style> ... </style>` 内容剪切到 `settings-view.css`（去掉 `<style>` 标签）。在 `<script setup>` 顶部用 `import './settings/settings-view.css';` 引入。注意：原 `<style>` 若是全局（无 `scoped`），CSS 文件 import 同样是全局，行为一致。

- [ ] **Step 3: 改 SettingsView.vue 使用 composable**

删除 SettingsView.vue:46-56 的本地 `healthData`/`metricsData`/`loadMonitor`，替换为：

```ts
import { useSystemMonitor } from '../composables/useSystemMonitor';
const { healthData, metricsData, loadMonitor } = useSystemMonitor();
```

template 中 `:health-data="healthData"`、`:metrics-data="metricsData"`、`@refresh="loadMonitor"` 引用不变。`watch(activeTab, ...)` 中的 `loadMonitor()` 调用不变。

- [ ] **Step 4: 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: 两条命令均成功，无类型错误、构建产物生成。

- [ ] **Step 5: 手测提示（交给用户）**

打开「平台设置」→ 切到「系统监控」tab，确认 health / metrics 数据正常加载、刷新按钮可用；确认整个设置页样式无变化。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/composables/useSystemMonitor.ts frontend/src/components/settings/settings-view.css frontend/src/components/SettingsView.vue
git commit -m "refactor(SettingsView): 抽 useSystemMonitor 并外移 CSS"
```

---

## Task 2: 拆分 GraphAnalysisPanel.vue（630 → ~250）

**Files:**
- Create: `frontend/src/composables/useGraphStats.ts`
- Create: `frontend/src/components/analysis/GraphStatsTab.vue`
- Create: `frontend/src/components/analysis/NodeAnalysisTab.vue`
- Create: `frontend/src/components/analysis/PathQueryTab.vue`
- Modify: `frontend/src/components/GraphAnalysisPanel.vue`

- [ ] **Step 1: 抽 useGraphStats composable**

把 Tab 0 的统计 computed（`nodeTypeStats` / `edgeLabelStats` / `edgeSourceStats` / `degreeRank` / `isolatedNodes`，见 GraphAnalysisPanel.vue:42-99）与辅助函数（`typeColor` / `typeLabel` / `sourceBadge`，:28-37）搬进 composable。接受 nodes/edges getter：

```ts
// frontend/src/composables/useGraphStats.ts
import { computed } from 'vue';
import { NT } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

export function useGraphStats(
  getNodes: () => OntologyNode[],
  getEdges: () => OntologyEdge[],
) {
  const typeColor = (type: string) => (NT as any)[type]?.color || '#42b883';
  const typeLabel = (type: string) => (NT as any)[type]?.label || type;
  const sourceBadge = (s?: string) => {
    if (s === 'inferred')  return { text: 'AI推理',   color: '#bb77ff' };
    if (s === 'derived')   return { text: '文本提取', color: '#22dd88' };
    if (s === 'manual')    return { text: '手动',     color: '#3d9bff' };
    if (s === 'predicted') return { text: '推演',     color: '#fbbf24' };
    return { text: '预置', color: '#888' };
  };
  // nodeTypeStats / edgeLabelStats / edgeSourceStats / degreeRank / isolatedNodes
  // 逐个搬入，将 props.nodes → getNodes()、props.edges → getEdges()
  // ...（原样搬运，仅替换数据来源）
  return { typeColor, typeLabel, sourceBadge,
           nodeTypeStats, edgeLabelStats, edgeSourceStats, degreeRank, isolatedNodes };
}
```

> 执行时：读 GraphAnalysisPanel.vue:42-99 原文逐个搬运，computed 内部 `props.nodes`/`props.edges` 改为 `getNodes()`/`getEdges()`，逻辑一字不改。

- [ ] **Step 2: 拆 GraphStatsTab.vue（Tab 0 模板）**

把 template 里 `tab === 0` 分支的 DOM 剪进 `GraphStatsTab.vue`。props：`nodes`、`edges`。内部调用 `useGraphStats(() => props.nodes, () => props.edges)` 渲染。emit `focus-node`（degreeRank/isolated 点击跳转用）。把对应的 scoped CSS 一并带过去。

- [ ] **Step 3: 拆 NodeAnalysisTab.vue（Tab 1 模板）**

把 `tab === 1` 分支与其逻辑（`selNode`/`outgoing`/`incoming`/`twoHopNeighbors`，:103-122）搬入。props：`nodes`、`edges`、`selectedId`。emit `focus-node`。

- [ ] **Step 4: 拆 PathQueryTab.vue（Tab 2 模板）**

把 `tab === 2` 分支与路径查询逻辑（`pathFrom`/`pathTo`/`findPaths`/`expandedPaths`/`togglePath`，:126-191）搬入。props：`nodes`、`edges`。emit `focus-node`。

- [ ] **Step 5: 改 GraphAnalysisPanel.vue 为壳组件**

只保留 tab 切换（`tab` ref + `watch(selectedId)`）与三个子组件的条件渲染：

```vue
<GraphStatsTab v-if="tab === 0" :nodes="nodes" :edges="edges" @focus-node="(id) => emit('focus-node', id)" />
<NodeAnalysisTab v-else-if="tab === 1" :nodes="nodes" :edges="edges" :selected-id="selectedId" @focus-node="(id) => emit('focus-node', id)" />
<PathQueryTab v-else-if="tab === 2" :nodes="nodes" :edges="edges" @focus-node="(id) => emit('focus-node', id)" />
```

- [ ] **Step 6: 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: 全绿。

- [ ] **Step 7: 手测提示**

打开图谱分析面板，逐个切三个 tab：全图统计数字正确、点击度数排行能聚焦节点；选中节点后自动跳到「节点分析」且出入边/二跳邻居正确；路径查询能找到路径、展开/折叠正常。

- [ ] **Step 8: Commit**

```bash
git add frontend/src/composables/useGraphStats.ts frontend/src/components/analysis/ frontend/src/components/GraphAnalysisPanel.vue
git commit -m "refactor(GraphAnalysisPanel): 拆 3 个 Tab 子组件 + useGraphStats"
```

---

## Task 3: 拆分 PredictDialog.vue（768 → ~300）

**Files:**
- Create: `frontend/src/composables/useHypothesisTemplates.ts`
- Create: `frontend/src/composables/useConstraintConflicts.ts`
- Create: `frontend/src/components/predict/predict-dialog.css`
- Modify: `frontend/src/components/PredictDialog.vue`

- [ ] **Step 1: 抽 useHypothesisTemplates composable**

把模板管理逻辑（`templates` ref、`loadTemplates`/`saveAsTemplate`/`loadFromTemplate`/`removeTemplate`，见 PredictDialog.vue:36-113）搬入。由于这些函数读写组件内的 `seedIds`/`steps`/`intent`/`constraints`/`prompt`/`templateName`，composable 用回调注入当前表单态与回填表单的 setter：

```ts
// frontend/src/composables/useHypothesisTemplates.ts
import { ref } from 'vue';
import { toast } from './useToast';
import { listTemplates, saveTemplate, touchTemplate, deleteTemplate,
         type HypothesisTemplate } from '../api/hypothesisTemplates';

export function useHypothesisTemplates(opts: {
  getModelId: () => string | undefined;
  getForm: () => { seeds: string[]; steps: number; intent: 'forward' | 'backward';
                   constraints: any[]; prompt: string };
  applyForm: (t: HypothesisTemplate, existingIds: Set<string>) => void;
  getExistingNodeIds: () => Set<string>;
}) {
  const templates = ref<HypothesisTemplate[]>([]);
  const loadTemplates = async () => { /* 原 :67-72 逻辑，modelId 用 opts.getModelId() */ };
  const saveAsTemplate = async (name: string) => { /* 原 :74-91，表单值用 opts.getForm() */ };
  const loadFromTemplate = async (t: HypothesisTemplate) => { /* 原 :93-105，回填用 opts.applyForm */ };
  const removeTemplate = async (t: HypothesisTemplate) => { /* 原 :107-113 */ };
  return { templates, loadTemplates, saveAsTemplate, loadFromTemplate, removeTemplate };
}
```

> 执行注意：`loadFromTemplate` 里的"部分节点不存在"toast 警告逻辑要保留。回填表单的副作用通过 `applyForm` 回调在组件内完成，保持响应式正确。

- [ ] **Step 2: 抽 useConstraintConflicts composable**

把约束冲突检测（`constraintConflicts` computed 及相关，见 PredictDialog.vue:115 起到检测结束）搬入。签名：

```ts
// frontend/src/composables/useConstraintConflicts.ts
import { computed } from 'vue';
import type { OntologyEdge } from '../types';

type Constraint = { nodeId: string; mode: 'force' | 'block' | 'probability'; probability?: number };

export function useConstraintConflicts(
  getConstraints: () => Constraint[],
  getEdges: () => OntologyEdge[],
) {
  const constraintConflicts = computed(() => { /* 原检测逻辑，数据来源换 getter */ });
  return { constraintConflicts };
}
```

> 执行时先读 PredictDialog.vue:115-180 区间确认完整检测逻辑边界再搬。

- [ ] **Step 3: 外移 CSS**

把 PredictDialog.vue 第 411 行起的 `<style scoped>` 内容移到 `predict-dialog.css`。**注意原 style 带 `scoped`**——外移成普通 CSS 文件会丢失 scoped 隔离。两个安全选项，择一：
  - (推荐) 保留 `<style scoped>` 在组件内不动（CSS 不外移），本 Task 只抽两个 composable；
  - 或：给所有选择器加统一前缀类（如 `.predict-dialog`）后再外移。

> 默认采用推荐项：**Task 3 不动 CSS**，只做 Step 1-2 的 composable 抽取，避免 scoped 语义风险。删除本 Step 的 CSS 文件创建。

- [ ] **Step 4: 改 PredictDialog.vue 接线 composable**

在组件内引入两个 composable，删除被搬走的本地实现，用回调把表单态接进去：

```ts
const tpl = useHypothesisTemplates({
  getModelId: () => props.modelId,
  getForm: () => ({ seeds: seedIds.value, steps: steps.value, intent: intent.value,
                    constraints: constraints.value, prompt: prompt.value }),
  applyForm: (t, existingIds) => {
    seedIds.value = t.seeds.filter(id => existingIds.has(id));
    steps.value = t.steps;
    intent.value = (t.intent as 'forward' | 'backward') || 'forward';
    constraints.value = (t.constraints || []).filter(c => existingIds.has(c.nodeId));
    prompt.value = t.prompt || '';
  },
  getExistingNodeIds: () => new Set(props.nodes.map(n => n.id)),
});
const { constraintConflicts } = useConstraintConflicts(() => constraints.value, () => props.edges);
```

template 中 `templates` / `constraintConflicts` 等引用改为 `tpl.templates` 等。`saveAsTemplate` 按钮改调 `tpl.saveAsTemplate(templateName.value)`。

- [ ] **Step 5: 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: 全绿。

- [ ] **Step 6: 手测提示**

打开推演对话框：保存模板、从模板加载（含部分节点失效的警告）、删除模板都正常；添加 force + block 冲突约束时冲突警告正确显示。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/composables/useHypothesisTemplates.ts frontend/src/composables/useConstraintConflicts.ts frontend/src/components/PredictDialog.vue
git commit -m "refactor(PredictDialog): 抽 useHypothesisTemplates 与 useConstraintConflicts"
```

---

## Task 4: 拆分 ImportDialog.vue（775 → ~300）

**Files:**
- Create: `frontend/src/composables/useImportFiles.ts`
- Create: `frontend/src/composables/useExtractStream.ts`
- Create: `frontend/src/composables/useImportDedup.ts`
- Modify: `frontend/src/components/ImportDialog.vue`

- [ ] **Step 1: 抽 useImportFiles composable**

把文件处理（`files` ref、`addFile`/`onPick`/`onDrop`/`fmtSize`、URL 解析 `parsedUrls`/`urlOverLimit`/`URL_LIMIT`、`SOURCE_ICONS`/`iconForSource`，见 ImportDialog.vue:39-130 相关片段）搬入：

```ts
// frontend/src/composables/useImportFiles.ts
import { ref, computed } from 'vue';

export function useImportFiles() {
  const files = ref<File[]>([]);
  const urlInput = ref('');
  const errorMsg = ref('');
  const parsedUrls = computed(() => /* 原 :59-60 */);
  const URL_LIMIT = 5;
  const urlOverLimit = computed(() => parsedUrls.value.length > URL_LIMIT);
  const fmtSize = (n: number) => { /* 原 :104-108 */ };
  const addFile = (f: File) => { /* 原 :121-130，errorMsg 用本地 ref */ };
  const onPick = (e: Event) => { /* 原 :110-115 */ };
  const onDrop = (e: DragEvent) => { /* 原 :116-120 */ };
  const iconForSource = (type?: string) => { /* 原 :70 */ };
  const resetFiles = () => { files.value = []; urlInput.value = ''; errorMsg.value = ''; };
  return { files, urlInput, errorMsg, parsedUrls, URL_LIMIT, urlOverLimit,
           fmtSize, addFile, onPick, onDrop, iconForSource, resetFiles };
}
```

- [ ] **Step 2: 抽 useExtractStream composable**

把 SSE 抽取流程（`buildSteps` ref、`sseHandle` 句柄、`extractFromFilesStream` 调用与生命周期 abort、`loading`/`replyText`/`sources`/`extractedRaw`，见 :43-95 与抽取触发函数）搬入。提供 `start(files, urls)` / `abort()` / `reset()`，并在 `onBeforeUnmount` 由组件调用 `abort()`：

```ts
// frontend/src/composables/useExtractStream.ts
import { ref } from 'vue';
import { extractFromFilesStream } from '../api/ontology';
import type { SseHandle } from '../api/http';
import type { SourceMeta } from '../types';

export function useExtractStream() {
  const loading = ref(false);
  const replyText = ref('');
  const sources = ref<SourceMeta[]>([]);
  const extractedRaw = ref<{ nodes: any[]; edges: any[] } | null>(null);
  const buildSteps = ref<{ key: string; label: string; status: 'running'|'done'|'error' }[]>([]);
  let sseHandle: SseHandle | null = null;

  const abort = () => { if (sseHandle) { try { sseHandle.abort(); } catch {} sseHandle = null; } };
  const reset = () => { loading.value=false; replyText.value=''; sources.value=[];
                        extractedRaw.value=null; buildSteps.value=[]; abort(); };
  const start = async (files: File[], urls: string[]) => { /* 原抽取函数逻辑，sseHandle 赋值在此 */ };
  return { loading, replyText, sources, extractedRaw, buildSteps, start, abort, reset };
}
```

> 执行时先读 ImportDialog.vue 完整 script（1-299）定位真正的抽取触发函数与 SSE 回调更新逻辑，原样搬运。

- [ ] **Step 3: 抽 useImportDedup composable**

把去重对照（`normLabel`、`displayedNodes`/`displayedEdges` computed、`selectedNodeIds`/`selectedEdgeIds` 选择集与全选/切换逻辑，结合 mode + currentNodes 去重）搬入：

```ts
// frontend/src/composables/useImportDedup.ts
import { ref, computed } from 'vue';
import type { OntologyNode } from '../types';

export function useImportDedup(opts: {
  getRaw: () => { nodes: any[]; edges: any[] } | null;
  getMode: () => 'merge' | 'new';
  getCurrentNodes: () => OntologyNode[];
}) {
  const normLabel = (s: string) => (s || '').trim().toLowerCase().replace(/\s+/g, ' ');
  const selectedNodeIds = ref<Set<string>>(new Set());
  const selectedEdgeIds = ref<Set<string>>(new Set());
  const displayedNodes = computed(() => { /* 原去重逻辑 */ });
  const displayedEdges = computed(() => { /* 原去重逻辑 */ });
  const selectAll = () => { selectedNodeIds.value = new Set(displayedNodes.value.map(n => n.id));
                            selectedEdgeIds.value = new Set(displayedEdges.value.map(e => e.id)); };
  // toggleNode / toggleEdge 等
  return { normLabel, selectedNodeIds, selectedEdgeIds, displayedNodes, displayedEdges, selectAll };
}
```

- [ ] **Step 4: 改 ImportDialog.vue 接线**

引入三个 composable，删除本地实现。`reset()`（:72-86）改为分别调 `resetFiles()` + `extract.reset()` + dedup 选择集清空 + `mode`/`newName` 本地重置。`watch(props.open)` 与 `watch(mode)` 与 `onBeforeUnmount` 中相应改调 composable 方法。commit 提交时 `emit('commit', ...)` payload 组装逻辑保留在组件内。

- [ ] **Step 5: 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: 全绿。

- [ ] **Step 6: 手测提示**

打开导入对话框：拖拽 / 选择 PDF、图片、docx 文件（含不支持类型的报错）；填 URL（超 5 个的提示）；执行抽取看分步进度；merge/new 切换时去重与全选刷新正确；勾选部分节点后提交，图谱合并结果正确；抽取中关闭对话框能中断 SSE。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/composables/useImportFiles.ts frontend/src/composables/useExtractStream.ts frontend/src/composables/useImportDedup.ts frontend/src/components/ImportDialog.vue
git commit -m "refactor(ImportDialog): 抽 files/extract-stream/dedup 三个 composable"
```

---

## Task 5: 拆分 GraphCanvas.vue（918 → ~350，逻辑最密）

**Files:**
- Create: `frontend/src/composables/useCanvasViewport.ts`
- Create: `frontend/src/composables/useNodeDrag.ts`
- Create: `frontend/src/composables/useBoxSelect.ts`
- Create: `frontend/src/composables/useCanvasContextMenu.ts`
- Create: `frontend/src/composables/useNodeColoring.ts`
- Modify: `frontend/src/components/GraphCanvas.vue`

> 本 Task 风险最高：拖拽/平移/缩放共享 `zoom`、`cvRef`（画布滚动容器引用）、scroll 状态。**抽取时把共享状态集中在 `useCanvasViewport`，其余 composable 通过参数接收 `zoom`/`cvRef` getter，避免状态分裂。** 按子步骤分多次 lint，降低一次性出错面。

- [ ] **Step 1: 抽 useCanvasViewport（缩放/平移/滚轮/视口裁剪）**

搬入 `zoom`、`cvRef`、`updateScroll`/`onScroll`、视口裁剪 `visibleNodes`/`visibleNodeIds`/`visibleEdges`、`bounds`、`fitView`、`onWheel`、平移 `startPan` 中与视口相关部分（见 GraphCanvas.vue:253-602 区间）。导出 `zoom`、`cvRef`、`visibleNodes`、`visibleEdges`、`fitView`、`onWheel`、`onScroll` 等。接收 `getNodes`/`getEdges` getter 与容器尺寸。

Run after step: `cd frontend && npm run lint`（先只验类型，全文件还没改完不跑 build）

- [ ] **Step 2: 抽 useNodeColoring（热力/差异着色）**

搬入 `heatColor`（:95）、`diffColor`（:104）、类型筛选 `typeFilter`/`toggleTypeFilter`/`toggleEdgeFilter`/`matchesFilter`/`edgeMatchesFilter`（:52-72）、`getT`（:602）、`neighborIds`（:604）。纯函数 + 少量 ref，接收 nodes getter 与 `diffHighlight` getter。

Run after step: `cd frontend && npm run lint`

- [ ] **Step 3: 抽 useNodeDrag（节点拖拽）**

搬入 `startDrag`（:390）及其依赖的拖拽态。接收 `zoom` getter、`cvRef` getter、`emit('move')`/`emit('drag-start')` 回调。

Run after step: `cd frontend && npm run lint`

- [ ] **Step 4: 抽 useBoxSelect（框选/多选）**

搬入多选/框选状态（:247 起）、`startPan` 中框选部分、`onWindowMouseMove`/`onWindowMouseUp` 中框选逻辑（:450-512）。接收 `zoom`/`cvRef` getter、`visibleNodes` getter。注意：`onWindowMouseMove`/`onWindowMouseUp` 同时服务拖拽、平移、框选三件事——**保持单一的 window 事件监听器在组件内，内部分派到各 composable 的处理函数**，不要拆成三个互相打架的监听器。

Run after step: `cd frontend && npm run lint`

- [ ] **Step 5: 抽 useCanvasContextMenu（右键菜单 + 加节点表单）**

搬入 `ctxMenu`/`addNodeForm` 状态、`onNodeContext`/`onCanvasContext`/`closeCtx`、`triggerPredict`/`triggerEdit`/`triggerExplain`/`triggerDelete`/`triggerBatchDelete`、`ctxNodeIsPredicted`、`submitAddNode`、`toggleAddNodeInput`/`toggleAddNodeOutput`（:112-247）。接收 `zoom`/`cvRef` getter 与各 emit 回调。

- [ ] **Step 6: 改 GraphCanvas.vue 接线所有 composable**

组件内保留：props/emits 定义、单一 window 鼠标事件监听器（分派给各 composable 的 handler）、`useGraphSearch`（已存在）、template。所有被搬走的引用改为 `viewport.xxx`/`drag.xxx`/`box.xxx`/`ctx.xxx`/`coloring.xxx`。

- [ ] **Step 7: 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: 全绿。

- [ ] **Step 8: 手测提示（最关键）**

逐项验证画布交互：节点拖拽（位置正确不漂移）；空白处平移；Ctrl+滚轮以鼠标为中心缩放；框选多个节点；右键节点菜单（推演/编辑/解释/删除）；右键空白加节点；图例点击筛选类型；大图（≥80 节点）视口裁剪后滚动渲染正常；fitView 自适应；搜索跳转聚焦；只读模式（预览页）禁用拖拽与右键。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/composables/useCanvasViewport.ts frontend/src/composables/useNodeDrag.ts frontend/src/composables/useBoxSelect.ts frontend/src/composables/useCanvasContextMenu.ts frontend/src/composables/useNodeColoring.ts frontend/src/components/GraphCanvas.vue
git commit -m "refactor(GraphCanvas): 交互逻辑拆为 5 个 composable"
```

---

## Task 6: 拆分 App.vue（1346 → ~450）

**Files:**
- Create: `frontend/src/components/dialogs/NodeEditDialog.vue`
- Create: `frontend/src/components/dialogs/RelationEditDialog.vue`
- Create: `frontend/src/components/dialogs/TemplateLibraryDialog.vue`
- Create: `frontend/src/app.css`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 抽 NodeEditDialog.vue（节点编辑弹窗）**

把 App.vue:877-923 的节点编辑 modal 模板抽成组件。props 接收 `editingNode`、`editableGraphNodes`、`editNodeInputs`/`editNodeOutputs`、`editInputLabels`/`editOutputLabels`、`NT`。emit `save`/`cancel`/`toggle-input`/`toggle-output`。组件内不持有业务状态，纯展示 + 事件上抛。

- [ ] **Step 2: 抽 RelationEditDialog.vue（关系编辑弹窗）**

把 App.vue:926-966 的关系编辑 modal 抽成组件。props：`editingRelation`、`editRelGraphNodes`、`NT`。emit `save`/`cancel`/`delete`/`toggle-input`/`toggle-output`。

- [ ] **Step 3: 抽 TemplateLibraryDialog.vue（模板库弹窗）**

把 App.vue:968-990 的模板库 modal 抽成组件。props：`templates`、`templatesLoading`。emit `close`/`create-from`/`remove`。

- [ ] **Step 4: 外移全局 CSS**

把 App.vue:995-1346 的 `<style>`（全局，无 scoped）整段移到 `app.css`，在 `<script setup>` 顶部 `import './app.css';`。全局语义不变。

- [ ] **Step 5: 消除 alias 透传**

删除 App.vue:294-321（editor.* alias）、:351-384（versionTpl.* / prediction.* alias）等纯转发 `const xxx = editor.xxx` 行。template 中对应引用改为直接 `editor.xxx` / `versionTpl.xxx` / `prediction.xxx`。

> 这是最易错步骤。**逐个 alias 删除 + 全局替换 template 引用**，删完立刻 `npm run lint` 抓未替换的引用。保留确实需要本地包装的（如 `undoGraph`/`redoGraph` 这种加了额外逻辑的，不是纯 alias，不动）。

- [ ] **Step 6: 接线三个弹窗组件**

template 中用 `<NodeEditDialog :editing-node="editingNode" ... @save="saveEditNode" @cancel="cancelEditNode" .../>` 等替换原内联 modal。`v-if="editingNode"` 等条件移到组件外层或组件内 `v-if`。

- [ ] **Step 7: 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: 全绿。

- [ ] **Step 8: 手测提示**

全流程回归：进入工作空间 → 新建/打开图谱 → 节点编辑弹窗（增删输入输出连接、改类型）→ 关系编辑弹窗（多选起止节点、删除）→ 模板库（创建/删除模板）→ 撤销/重做快捷键 → 导出菜单 → 版本菜单 → 切换 chat/graph 视图。重点确认 alias 删除后无任何引用报错、响应式正常。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/dialogs/ frontend/src/app.css frontend/src/App.vue
git commit -m "refactor(App): 抽 3 个弹窗组件 + 消除 alias 透传 + 外移 CSS"
```

---

## Task 7: Bug 检查与修复

**Files:** 视审查结果而定。

- [ ] **Step 1: 跑全量类型检查与构建基线**

Run: `cd frontend && npm run lint && npm run build`
Expected: 全绿（确认重构后整体健康）。

- [ ] **Step 2: 对重构后代码做 max-effort bug 审查**

对 `git diff` 涉及的所有文件 + 各 composable 跑 5 角度审查（逐行扫描、删除行为审计、跨文件追踪、语言陷阱、wrapper 正确性）。重点：composable 抽取时 getter/回调是否漏接、响应式是否丢失、emit 事件名是否一致、window 监听器分派是否覆盖三种鼠标模式。

- [ ] **Step 3: 合并已知疑似 bug 清单**

把下方「疑似 Bug 清单」与 Step 2 审查结果合并，按严重度排序。

- [ ] **Step 4: 逐个修复并验证**

每修一个 bug：改代码 → `npm run lint && npm run build` → 手测对应流程 → 单独 commit（`fix(<scope>): ...`）。

---

## 疑似 Bug 清单（重构期间只记录，Task 7 处理）

1. **App.vue:581** — `currentModelTitle.value = '渚涘簲閾炬湰浣撳浘'` 是 GBK 乱码，应为 `'供应链本体图'`（对照 App.vue:79 的正常写法）。删除当前正在查看的图谱后，标题栏会显示乱码。修复：改为 `'供应链本体图'`，或更合理地置空字符串（因为已 `goWelcome()`）。

（执行各 Task 期间新发现的疑似 bug，追加到此清单，附 `文件:行号` 与触发场景。）
