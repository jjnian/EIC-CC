<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import Sidebar from './components/Sidebar.vue';
import SettingsView from './components/SettingsView.vue';
import WelcomeChat from './components/WelcomeChat.vue';
import WorkspacePickerView from './components/WorkspacePickerView.vue';
import PredictDialog from './components/PredictDialog.vue';
import BranchCompareDialog from './components/BranchCompareDialog.vue';
import ImportDialog from './components/ImportDialog.vue';
import GraphView from './components/views/GraphView.vue';
import ChatCenterView from './components/views/ChatCenterView.vue';
import DataSourceDetailView from './components/datasource/DataSourceDetailView.vue';
import DataSourceCreateDialog from './components/DataSourceCreateDialog.vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from './types';
import { toast, mountToastRoot } from './composables/useToast';
import { updateOntology } from './api/ontology';
import { ApiError } from './api/http';
import { useDivider } from './composables/useDivider';
import { useImportFlow } from './composables/useImportFlow';
import { useScenarios } from './composables/useScenarios';
import { usePrediction } from './composables/usePrediction';
import { useOntologyModel } from './composables/useOntologyModel';
import { useGraphActions } from './composables/useGraphActions';
import { useGraphHistory } from './composables/useGraphHistory';
import { useImportMerge } from './composables/useImportMerge';
import { useGraphEditor } from './composables/useGraphEditor';
import { useVersionTemplates } from './composables/useVersionTemplates';
import { useWorkspaces } from './composables/useWorkspaces';
import { NT } from './constants';

const sel = ref<string | null>(null);
const sbExp = ref(true);
const graphRef = ref<any>(null);
const chatRef = ref<any>(null);
const { chatW, startDivider, isDragging } = useDivider(360, () => graphRef.value?.fitView());

const view = ref<'welcome' | 'list' | 'graph' | 'chat' | 'settings' | 'workspace-picker' | 'datasource'>('workspace-picker');
const currentDataSourceId = ref<string | null>(null);
const currentModelTitle = ref('供应链本体图');
const pendingChatSeed = ref<{ text: string; files: File[] } | null>(null);

const wsManager = useWorkspaces();

const currentModelId = ref<string>('');
const compareDialogOpen = ref(false);
const importDialogOpen = ref(false);

// 数据源创建对话框状态
const dsCreateOpen = ref(false);
const onDsCreated = async (id: string) => {
  currentDataSourceId.value = id;
  view.value = 'datasource';
};

// 分支对比差异高亮状态
const diffHighlight = ref<{ sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null>(null);
const onHighlightDiff = (data: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null) => {
  diffHighlight.value = data;
};
const clearDiffHighlight = () => {
  diffHighlight.value = null;
};

const ontology = useOntologyModel({
  currentModelId,
  onDeletedCurrent: () => goWelcome(),
});
const models = ontology.models;
const isCreating = ontology.isCreating;
const loadOntologyModels = ontology.loadOntologyModels;
const findModel = ontology.findModel;
const createOnBackend = ontology.createOnBackend;
const deleteOntologyModel = ontology.deleteOntologyModel;

const nodes = ref<OntologyNode[]>([]);
const edges = ref<OntologyEdge[]>([]);

// Scenarios 与 Prediction 通过 getter 解耦循环依赖
let scenarios: ReturnType<typeof useScenarios>;
const prediction = usePrediction({
  currentModelId,
  nodes,
  edges,
  getActiveBranchId: () => scenarios.activeBranchId.value,
  setActiveBranchId: (id) => { scenarios.activeBranchId.value = id; },
  setTrunkSnapshot: (snap) => { scenarios.trunkSnapshot.value = snap; },
  appendBranch: (s) => { scenarios.branches.value.unshift(s); },
  switchBranch: (id) => scenarios.switchBranch(id),
  fitView: () => graphRef.value?.fitView(),
});

scenarios = useScenarios({
  currentModelId,
  nodes,
  edges,
  findModel,
  abortLiveStream: prediction.abortLiveStream,
  resetLiveState: () => { prediction.resetLiveState(); sel.value = null; },
  fitView: () => graphRef.value?.fitView(),
});

const branches = scenarios.branches;
const activeBranchId = scenarios.activeBranchId;

// 防抖保存:图谱编辑后 1.2 秒无操作 → PUT 到后端
let saveTimer: number | null = null;
const persistCurrentModel = (immediate = false) => {
  if (!currentModelId.value || activeBranchId.value !== 'trunk') return;
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  const targetId = currentModelId.value;
  const run = async () => {
    const m = findModel(targetId);
    if (!m) return;
    m.graphData = { nodes: nodes.value, edges: edges.value };
    try {
      await updateOntology(m.id, m);
    } catch (e) {
      console.error('save failed', e);
      if (e instanceof ApiError) {
        toast.warn('未保存到服务器(HTTP ' + e.status + ')');
      } else {
        toast.warn('未保存到服务器');
      }
    }
  };
  if (immediate) run();
  else saveTimer = window.setTimeout(run, 1200);
};

const history = useGraphHistory({
  nodes,
  edges,
  persist: persistCurrentModel,
});
const canUndo = history.canUndo;
const canRedo = history.canRedo;
const undoGraph = () => { if (history.undo()) { sel.value = null; nextTick(() => graphRef.value?.fitView?.()); } };
const redoGraph = () => { if (history.redo()) { sel.value = null; nextTick(() => graphRef.value?.fitView?.()); } };

const onGlobalKeydown = (e: KeyboardEvent) => {
  if (view.value !== 'graph') return;
  const t = e.target as HTMLElement | null;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  const mod = e.ctrlKey || e.metaKey;
  if (!mod) return;
  const key = e.key.toLowerCase();
  if (key === 'z' && !e.shiftKey) {
    e.preventDefault();
    undoGraph();
  } else if ((key === 'z' && e.shiftKey) || key === 'y') {
    e.preventDefault();
    redoGraph();
  }
};

onMounted(async () => {
  mountToastRoot();
  window.addEventListener('keydown', onGlobalKeydown);
  try {
    const valid = await wsManager.ensureValidCurrent();
    if (!valid) {
      view.value = 'workspace-picker';
      return;
    }
    await loadOntologyModels();
    view.value = 'welcome';
  } catch (e) {
    console.error('init workspace failed', e);
    view.value = 'workspace-picker';
  }
});

const onWorkspaceEntered = async () => {
  try {
    await loadOntologyModels();
  } catch (e) {
    console.error('load models failed', e);
  }
  goWelcome();
};

const onWorkspaceSwitched = (_id: string) => {
  // 切换 ws 后内存中的所有图谱/对话/分支都要重新拉。最简单可靠的做法是整页刷新。
  window.location.reload();
};

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
});

const graphActions = useGraphActions({
  nodes,
  edges,
  currentModel: () => findModel(currentModelId.value),
  currentTitle: () => currentModelTitle.value,
  fitView: () => graphRef.value?.fitView(),
  persist: persistCurrentModel,
});
const autoLayout = () => { history.snapshot(); graphActions.autoLayout(); };
const toggleLayoutDirection = graphActions.toggleLayoutDirection;
const layoutDirection = graphActions.layoutDirection;

const merger = useImportMerge({
  nodes, edges,
  snapshotHistory: () => history.snapshot(),
  placeIncomingNodes: graphActions.placeIncomingNodes,
  autoLayout: graphActions.autoLayout,
  persist: persistCurrentModel,
});
const onUpdate = merger.onUpdate;

const editor = useGraphEditor({
  nodes, edges, sel,
  snapshotHistory: () => history.snapshot(),
  persist: persistCurrentModel,
});
const addNodeAtPosition = editor.addNodeAtPosition;
const addEdgesBatch = editor.addEdgesBatch;
const editingRelation = editor.editingRelation;
const openEditRelation = editor.openEditRelation;
const toggleRelInput = editor.toggleRelInput;
const toggleRelOutput = editor.toggleRelOutput;
const saveEditRelation = editor.saveEditRelation;
const cancelEditRelation = editor.cancelEditRelation;
const deleteEditingRelation = editor.deleteEditingRelation;
const editRelGraphNodes = editor.editRelGraphNodes;
const editingNode = editor.editingNode;
const editNodeInputs = editor.editNodeInputs;
const editNodeOutputs = editor.editNodeOutputs;
const editInputLabels = editor.editInputLabels;
const editOutputLabels = editor.editOutputLabels;
const editableGraphNodes = editor.editableGraphNodes;
const openEditNode = editor.openEditNode;
const toggleEditInput = editor.toggleEditInput;
const toggleEditOutput = editor.toggleEditOutput;
const saveEditNode = editor.saveEditNode;
const cancelEditNode = editor.cancelEditNode;
const updateNodeProps = editor.updateNodeProps;
const updateNodeSchema = editor.updateNodeSchema;
const updateEdgeSchema = editor.updateEdgeSchema;
const deleteEdge = editor.deleteEdge;
const deleteRelation = editor.deleteRelation;
const deleteNode = editor.deleteNode;
const deleteNodes = editor.deleteNodes;
const clearCanvas = editor.clearCanvas;

const openModel = async (m: OntologyModel, targetView: 'graph' | 'chat' = 'graph') => {
  if (currentModelId.value && currentModelId.value !== m.id) {
    persistCurrentModel(true);
  }
  prediction.abortLiveStream();
  currentModelTitle.value = m.title || m.name || '';
  currentModelId.value = m.id;
  nodes.value = m.graphData.nodes;
  edges.value = m.graphData.edges;
  sel.value = null;
  activeBranchId.value = 'trunk';
  prediction.resetLiveState();
  view.value = targetView;
  history.reset(nodes.value, edges.value);
  await scenarios.loadBranches(m.id);
};

const versionTpl = useVersionTemplates({
  currentModelId,
  nodes,
  edges,
  models,
  isCreating,
  findModel,
  resetHistory: (n, e) => history.reset(n, e),
  createOnBackend,
  openModel,
  persistImmediate: () => persistCurrentModel(true),
});
const showVersionMenu = versionTpl.showVersionMenu;
const versions = versionTpl.versions;
const versionsLoading = versionTpl.versionsLoading;
const toggleVersionMenu = versionTpl.toggleVersionMenu;
const doRestoreVersion = versionTpl.doRestoreVersion;
const showTemplates = versionTpl.showTemplates;
const templates = versionTpl.templates;
const templatesLoading = versionTpl.templatesLoading;
const openTemplates = versionTpl.openTemplates;
const saveAsTemplate = versionTpl.saveAsTemplate;
const createFromTemplate = versionTpl.createFromTemplate;
const removeTemplate = versionTpl.removeTemplate;
const closeTemplates = versionTpl.closeTemplates;
const openPreview = versionTpl.openPreview;

// 模板别名（保留向下兼容）
const loadBranches = scenarios.loadBranches;
const switchBranch = scenarios.switchBranch;
const predictDialogOpen = prediction.predictDialogOpen;
const predictSeeds = prediction.predictSeeds;
const liveSteps = prediction.liveSteps;
const liveLoading = prediction.liveLoading;
const liveActive = prediction.liveActive;
const liveIntent = prediction.liveIntent;
const livePruneDetails = prediction.livePruneDetails;
const liveSeeds = prediction.liveSeeds;
const livePrompt = prediction.livePrompt;
const liveName = prediction.liveName;
const liveBranchId = prediction.liveBranchId;
const liveError = prediction.liveError;
const liveStatus = prediction.liveStatus;
const openPredictDialog = prediction.openPredictDialog;
const startPrediction = prediction.startPrediction;
const closeTimeline = prediction.closeTimeline;

// v1.0 导入提交:抽到 useImportFlow composable
const importFlow = useImportFlow({
  nodes,
  edges,
  activeBranchId,
  switchToTrunk: () => switchBranch('trunk'),
  persistCurrentModel,
  createNewModel: createOnBackend,
  registerAndOpenModel: async (saved) => {
    models.value.unshift(saved);
    await openModel(saved);
  },
  fitView: () => graphRef.value?.fitView(),
});
const onImportCommit = async (payload: {
  mode: 'merge' | 'new';
  name: string;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}) => {
  importDialogOpen.value = false;
  if (payload.mode === 'merge') history.snapshot();
  await importFlow.onImportCommit(payload);
};

const createNewModel = async () => {
  if (isCreating.value) return;
  isCreating.value = true;
  try {
    const draft: OntologyModel = {
      id: 'om_' + Date.now(),
      title: `新建推演模型 ${models.value.length + 1}`,
      desc: '新创建的空白本体模型画布',
      graphData: { nodes: [], edges: [] }
    };
    const saved = await createOnBackend(draft);
    models.value.unshift(saved);
    await openModel(saved);
  } finally {
    isCreating.value = false;
  }
};

const onWelcomeSubmit = async (payload: { text: string; files: File[] }) => {
  if (isCreating.value) return;
  isCreating.value = true;
  try {
    const title = payload.text.slice(0, 18).trim() || '新建本体图';
    const draft: OntologyModel = {
      id: 'om_' + Date.now(),
      title: title.length > 16 ? title.slice(0, 16) + '…' : title,
      desc: payload.text || '通过对话生成的本体模型',
      graphData: { nodes: [], edges: [] }
    };
    const saved = await createOnBackend(draft);
    models.value.unshift(saved);
    pendingChatSeed.value = payload;
    // 全程居中:分析过程留在中心聊天视图,生成的图存到血缘图文件夹由用户主动点击打开
    await openModel(saved, 'chat');
  } finally {
    isCreating.value = false;
  }
};

const welcomeResetTick = ref(0);
const goWelcome = () => {
  view.value = 'welcome';
  sel.value = null;
  welcomeResetTick.value++;
};

const onOpenConversation = async (id: string) => {
  // 对话默认在中心聊天视图里展示;已经在 graph 视图则不切换布局
  if (view.value !== 'graph' && view.value !== 'chat') {
    const target = currentModelId.value ? findModel(currentModelId.value) : models.value[0];
    if (target) {
      await openModel(target, 'chat');
    } else {
      view.value = 'chat';
    }
  }
  await nextTick();
  chatRef.value?.switchConversation(id);
};

const onNewConversation = async () => {
  if (view.value !== 'graph' && view.value !== 'chat') {
    const target = currentModelId.value ? findModel(currentModelId.value) : models.value[0];
    if (target) {
      await openModel(target, 'chat');
    } else {
      view.value = 'chat';
    }
  }
  await nextTick();
  chatRef.value?.newConversation();
};

const onOpenOntologyModel = async (id: string) => {
  const m = findModel(id);
  if (!m) return;
  await openModel(m, 'graph');
};

const openModelById = (id: string) => {
  const m = findModel(id);
  if (m) openModel(m);
};

const onDragStart = (_id: string) => {
  history.snapshot();
};

const onMove = (id: string, x: number, y: number) => {
  const n = nodes.value.find(n => n.id === id);
  if (n) {
    n.x = x;
    n.y = y;
    persistCurrentModel();
  }
};

// 导出菜单状态
const showExportMenu = ref(false);

// Schema 面板开关
const schemaOpen = ref(false);
const toggleSchema = () => { schemaOpen.value = !schemaOpen.value; };

const focusNodeInGraph = (id: string) => {
  sel.value = id;
  graphRef.value?.focusNode?.(id);
};

const formatFileSize = (bytes: number) => {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return bytes + ' B';
};
</script>
<template>
  <div class="app">
    <Sidebar
      :expanded="sbExp"
      :view="view"
      :ontology-models="models"
      @toggle="sbExp = !sbExp"
      @nav="r => { if(r==='welcome') goWelcome(); else if(r==='list') view='list'; else if(r==='settings') view='settings'; }"
      @open-conversation="onOpenConversation"
      @new-conversation="onNewConversation"
      @switch-workspace="onWorkspaceSwitched"
      @open-data-source="(id: string) => { currentDataSourceId = id; view = 'datasource'; }"
      @open-create-data-source="dsCreateOpen = true"
      @open-ontology-model="onOpenOntologyModel"
    />
    <div class="main">
      <div class="topbar">
        <div class="tb-title" v-if="view === 'graph' || view === 'chat'">
          <span class="tb-title-text">{{ currentModelTitle }}</span>
          <span class="bc-star">☆</span>
        </div>
        <div class="tb-tools" v-if="view === 'graph'">
          <button
            v-if="branches.length >= 2"
            class="tb-btn"
            @click="compareDialogOpen = true"
            title="对比两个推演分支"
          >⚖ 对比</button>
          <button
            class="tb-btn"
            @click="importDialogOpen = true"
            title="从 PDF / 图片抽取本体导入"
          >📥 导入</button>
          <button
            :class="['tb-btn', { 'hi': schemaOpen }]"
            @click="toggleSchema"
            title="查看 / 编辑静态本体 Schema (类 / 关系 / 属性 / 约束)"
            :disabled="!currentModelId"
          >⎔ Schema</button>
          <button class="tb-btn" @click="openPreview" title="在新标签页里以只读模式预览整张图谱" :disabled="!currentModelId">预览</button>
          <div class="export-menu-wrap">
            <button class="tb-btn" @click="toggleVersionMenu" title="查看和恢复历史版本" :disabled="!currentModelId">
              🕐 版本
              <svg viewBox="0 0 24 24" width="10" height="10" stroke="currentColor" stroke-width="2" fill="none" style="margin-left:2px"><polyline points="6 9 12 15 18 9"/></svg>
            </button>
            <div v-if="showVersionMenu" class="export-dropdown version-dropdown">
              <div v-if="versionsLoading" class="vm-state">加载中…</div>
              <div v-else-if="versions.length === 0" class="vm-state">暂无历史版本</div>
              <template v-else>
                <button v-for="v in versions" :key="v.timestamp"
                        class="vm-item"
                        @click="doRestoreVersion(v.timestamp); showVersionMenu = false">
                  <div class="vm-time">{{ new Date(v.timestamp).toLocaleString() }}</div>
                  <div class="vm-meta">{{ v.nodeCount }} 节点 · {{ v.edgeCount }} 关系 · {{ formatFileSize(v.fileSize) }}</div>
                </button>
              </template>
            </div>
          </div>
          <button class="tb-btn" @click="openTemplates" title="从模板创建新模型">📋 模板</button>
          <button class="tb-btn" @click="saveAsTemplate" title="将当前模型另存为模板" :disabled="!currentModelId">💾 存为模板</button>
          <div class="export-menu-wrap">
            <button class="tb-btn" @click="showExportMenu = !showExportMenu" title="导出">⬇ 导出</button>
            <div v-if="showExportMenu" class="export-dropdown">
              <button @click="graphActions.exportGraph(); showExportMenu = false">JSON 数据</button>
              <button @click="graphActions.exportPng(); showExportMenu = false">PNG 图片</button>
              <button @click="graphActions.exportMarkdown(); showExportMenu = false">Markdown 报告</button>
              <button @click="graphActions.exportMermaid(); showExportMenu = false">Mermaid 语法</button>
            </div>
          </div>
        </div>
        <div class="tb-tools" v-if="view === 'list'">
          <button class="tb-btn" @click="goWelcome">＋新对话</button>
          <button class="tb-btn hi" @click="createNewModel">＋新建模型</button>
        </div>
      </div>

      <!-- Workspace Picker -->
      <WorkspacePickerView v-if="view === 'workspace-picker'" @enter="onWorkspaceEntered" />

      <!-- Welcome / Chat-first View -->
      <WelcomeChat v-else-if="view === 'welcome'" :resetTick="welcomeResetTick" @submit="onWelcomeSubmit" />

      <!-- List View -->
      <div class="model-list-view" v-if="view === 'list'">
        <div class="ml-header">
          <h2>本体模型管理</h2>
          <p>选择一个已有模型进行编辑拓展，或创建新的推演画布。</p>
        </div>
        <div class="ml-grid">
          <div class="ml-card" v-for="m in models" :key="m.id" @click="openModel(m)">
            <div class="ml-card-head">
              <h3>{{ m.title }}</h3>
              <button class="ml-del" @click.stop="deleteOntologyModel(m.id)" title="删除该本体模型">×</button>
            </div>
            <p class="ml-card-desc">{{ m.desc }}</p>
            <div class="ml-card-foot">
              <span class="ml-stat">节点：{{ m.graphData?.nodes?.length || 0 }}</span>
              <span class="ml-stat">关系：{{ m.graphData?.edges?.length || 0 }}</span>
              <span class="ml-time">{{ m.updated || '' }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Settings View -->
      <SettingsView v-if="view === 'settings'" @switch-workspace="onWorkspaceSwitched" />

      <!-- DataSource Detail View -->
      <DataSourceDetailView v-else-if="view === 'datasource' && currentDataSourceId" :ds-id="currentDataSourceId" />

      <!-- Graph View -->
      <GraphView
        v-else-if="view === 'graph'"
        :nodes="nodes"
        :edges="edges"
        :selected-id="sel"
        :active-branch-id="activeBranchId"
        :live-active="liveActive"
        :live-loading="liveLoading"
        :live-steps="liveSteps"
        :live-intent="liveIntent"
        :live-prune-details="livePruneDetails"
        :live-seeds="liveSeeds"
        :live-prompt="livePrompt"
        :live-name="liveName"
        :live-branch-id="liveBranchId"
        :live-error="liveError"
        :live-status="liveStatus"
        :chat-w="chatW"
        :div-drag-active="isDragging"
        :pending-chat-seed="pendingChatSeed"
        :layout-direction="layoutDirection"
        :diff-highlight="diffHighlight"
        :can-undo="canUndo"
        :can-redo="canRedo"
        :schema-open="schemaOpen"
        @close-schema="schemaOpen = false"
        @update-node-schema="updateNodeSchema"
        @update-edge-schema="updateEdgeSchema"
        @undo="undoGraph"
        @redo="redoGraph"
        @update:selected-id="(id) => sel = id"
        @move="onMove"
        @drag-start="onDragStart"
        @auto-layout="autoLayout"
        @toggle-layout-direction="toggleLayoutDirection"
        @clear="clearCanvas"
        @predict-from="openPredictDialog"
        @edit-node="openEditNode"
        @delete-node="deleteNode"
        @delete-nodes="deleteNodes"
        @switch-branch="switchBranch"
        @close-timeline="closeTimeline"
        @focus-node="focusNodeInGraph"
        @update="onUpdate"
        @seed-consumed="pendingChatSeed = null"
        @start-divider="startDivider"
        @graph-ref="(el) => graphRef = el"
        @chat-ref="(el) => chatRef = el"
        @abort-prediction="closeTimeline"
        @update-node-props="updateNodeProps"
        @delete-edge="deleteEdge"
        @clear-diff="clearDiffHighlight"
        @add-node="addNodeAtPosition"
        @add-edges="addEdgesBatch"
        @edit-edge-relation="openEditRelation"
        @delete-relation="deleteRelation"
      />

      <!-- Chat-Centered View: 全程居中,分析过程在中央显示 -->
      <ChatCenterView
        v-else-if="view === 'chat'"
        :nodes="nodes"
        :edges="edges"
        :live-active="liveActive"
        :live-loading="liveLoading"
        :live-steps="liveSteps"
        :live-intent="liveIntent"
        :live-prune-details="livePruneDetails"
        :live-seeds="liveSeeds"
        :live-prompt="livePrompt"
        :live-name="liveName"
        :live-branch-id="liveBranchId"
        :live-error="liveError"
        :live-status="liveStatus"
        :pending-chat-seed="pendingChatSeed"
        :model-title="currentModelTitle"
        @update="onUpdate"
        @clear-graph="clearCanvas"
        @seed-consumed="pendingChatSeed = null"
        @abort-prediction="closeTimeline"
        @chat-ref="(el) => chatRef = el"
      />

      <!-- Predict Dialog (modal) -->
      <PredictDialog
        :open="predictDialogOpen"
        :nodes="nodes"
        :edges="edges"
        :initialSeedIds="predictSeeds"
        :modelId="currentModelId"
        @close="predictDialogOpen = false"
        @submit="startPrediction"
      />

      <!-- Branch Compare Dialog (modal) -->
      <BranchCompareDialog
        :open="compareDialogOpen"
        :branches="branches"
        @close="compareDialogOpen = false"
        @highlight-diff="onHighlightDiff"
      />

      <!-- Import Dialog (modal) -->
      <ImportDialog
        :open="importDialogOpen"
        :hasCurrentModel="!!currentModelId"
        :currentNodes="nodes"
        @close="importDialogOpen = false"
        @commit="onImportCommit"
      />

      <!-- DataSource Create Dialog (modal) -->
      <DataSourceCreateDialog v-if="dsCreateOpen" @close="dsCreateOpen = false" @created="onDsCreated" />

      <!-- 节点编辑对话框 -->
      <div v-if="editingNode" class="modal-mask" @click.self="cancelEditNode">
        <div class="add-node-dialog">
          <h3>编辑节点</h3>
          <label class="anp-label">名称
            <input v-model="editingNode.label" class="edit-input" @keydown.enter="saveEditNode" />
          </label>
          <label class="anp-label">类型
            <select v-model="editingNode.type" class="edit-input">
              <option v-for="(t, k) in NT" :key="k" :value="k">{{ t.label }}</option>
            </select>
          </label>
          <div v-if="editableGraphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输入连接 <span class="anp-hint">（从哪些节点连入）</span></div>
            <div class="anp-node-list">
              <div v-for="n in editableGraphNodes" :key="'ein-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleEditInput(n.id)">
                  <span :class="['anp-checkbox', { checked: editNodeInputs.includes(n.id) }]">
                    <span v-if="editNodeInputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
                <input v-if="editNodeInputs.includes(n.id)" v-model="editInputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
              </div>
            </div>
          </div>
          <div v-if="editableGraphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输出连接 <span class="anp-hint">（连向哪些节点）</span></div>
            <div class="anp-node-list">
              <div v-for="n in editableGraphNodes" :key="'eout-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleEditOutput(n.id)">
                  <span :class="['anp-checkbox', { checked: editNodeOutputs.includes(n.id) }]">
                    <span v-if="editNodeOutputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
                <input v-if="editNodeOutputs.includes(n.id)" v-model="editOutputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
              </div>
            </div>
          </div>
          <div class="edit-actions">
            <button class="edit-cancel" @click="cancelEditNode">取消</button>
            <button class="edit-save" @click="saveEditNode">保存</button>
          </div>
        </div>
      </div>

      <!-- 关系编辑对话框 -->
      <div v-if="editingRelation" class="modal-mask" @click.self="cancelEditRelation">
        <div class="add-node-dialog" @click.stop>
          <h3>编辑关系</h3>
          <label class="anp-label">关系名称
            <input v-model="editingRelation.label" class="edit-input" placeholder="输入关系名称…" @keydown.escape="cancelEditRelation" />
          </label>
          <div v-if="editRelGraphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输入节点 <span class="anp-hint">（关系的起始节点，可多选）</span></div>
            <div class="anp-node-list">
              <div v-for="n in editRelGraphNodes.filter(x => !editingRelation!.outputs.includes(x.id))" :key="'ri-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleRelInput(n.id)">
                  <span :class="['anp-checkbox', { checked: editingRelation.inputs.includes(n.id) }]">
                    <span v-if="editingRelation.inputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
              </div>
            </div>
          </div>
          <div v-if="editRelGraphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输出节点 <span class="anp-hint">（关系的目标节点，可多选）</span></div>
            <div class="anp-node-list">
              <div v-for="n in editRelGraphNodes.filter(x => !editingRelation!.inputs.includes(x.id))" :key="'ro-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleRelOutput(n.id)">
                  <span :class="['anp-checkbox', { checked: editingRelation.outputs.includes(n.id) }]">
                    <span v-if="editingRelation.outputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
              </div>
            </div>
          </div>
          <div class="edit-actions">
            <button class="edit-delete" @click="deleteEditingRelation">删除关系</button>
            <button class="edit-cancel" @click="cancelEditRelation">取消</button>
            <button class="edit-save" @click="saveEditRelation" :disabled="editingRelation.inputs.length === 0 || editingRelation.outputs.length === 0">保存</button>
          </div>
        </div>
      </div>

      <!-- 模板库面板 -->
      <div v-if="showTemplates" class="modal-mask" @click.self="closeTemplates">
        <div class="version-panel">
          <div class="vp-header">
            <h3>模板库</h3>
            <button class="vp-close" @click="closeTemplates">&#10005;</button>
          </div>
          <div v-if="templatesLoading" class="vp-loading">加载中…</div>
          <div v-else-if="templates.length === 0" class="vp-empty">暂无模板，可在图谱视图中点击「存为模板」保存当前模型为模板</div>
          <div v-else class="vp-list">
            <div v-for="t in templates" :key="t.id" class="vp-item" style="display:flex;justify-content:space-between;align-items:center">
              <div @click="createFromTemplate(t)" style="flex:1;cursor:pointer">
                <div class="vp-time">{{ t.title || t.name || '未命名' }}</div>
                <div class="vp-meta">
                  {{ t.graphData?.nodes?.length || 0 }} 节点 · {{ t.graphData?.edges?.length || 0 }} 关系
                  {{ t.desc ? ' · ' + t.desc : '' }}
                </div>
              </div>
              <button class="ml-del" @click.stop="removeTemplate(t.id)" title="删除模板">×</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.model-list-view {
  flex: 1;
  padding: 40px;
  overflow-y: auto;
  background: transparent;
}
.ml-header {
  margin-bottom: 32px;
}
.ml-header h2 {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 8px;
  letter-spacing: 0.5px;
}
.ml-header p {
  color: var(--text-dim);
  font-size: 14px;
}
.ml-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
}
.ml-card {
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  padding: 24px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ml-card:hover {
  transform: translateY(-4px);
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(66, 184, 131, 0.4);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.2), 0 0 0 1px rgba(66, 184, 131, 0.1);
}
.ml-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ml-card-head h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
}
.status-dot {
  width: 8px;
  height: 8px;
  background: var(--accent);
  border-radius: 50%;
  box-shadow: 0 0 8px var(--accent);
}
.ml-del {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.08);
  color: rgba(255,255,255,0.4);
  width: 26px; height: 26px;
  border-radius: 50%;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: all 0.15s;
  display: flex; align-items: center; justify-content: center;
}
.ml-del:hover { background: rgba(255, 102, 68, 0.2); border-color: rgba(255, 102, 68, 0.4); color: #ff8a6f; }
.ml-card-desc {
  font-size: 13px;
  color: var(--text-dim);
  line-height: 1.5;
  flex: 1;
}
.ml-card-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px dashed rgba(255, 255, 255, 0.1);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}
.ml-stat {
  background: rgba(255, 255, 255, 0.05);
  padding: 2px 8px;
  border-radius: 4px;
}
.ml-time {
  margin-left: auto;
}
/* 节点编辑对话框 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.edit-node-dialog {
  background: #1a2332;
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 12px;
  padding: 20px;
  width: 340px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.edit-node-dialog h3 { margin: 0; color: #e2e8f0; font-size: 16px; }
.edit-node-dialog label { display: flex; flex-direction: column; gap: 4px; color: rgba(255,255,255,0.6); font-size: 13px; }
.edit-input {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 6px;
  padding: 8px 10px;
  color: #e2e8f0;
  font-size: 14px;
  outline: none;
}
.edit-input:focus { border-color: #42b883; }
.edit-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.edit-cancel {
  background: transparent;
  border: 1px solid rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.6);
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
}
.edit-save {
  background: #42b883;
  border: none;
  color: #fff;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
}
.edit-delete {
  background: transparent;
  border: 1px solid rgba(239, 68, 68, 0.4);
  color: #ef4444;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  margin-right: auto;
  font-family: inherit;
  transition: background-color .15s;
}
.edit-delete:hover { background: rgba(239, 68, 68, 0.12); }

/* 版本历史面板 */
.version-panel {
  background: #1a2332;
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 12px;
  width: 400px;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.vp-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.vp-header h3 { margin: 0; color: #e2e8f0; font-size: 16px; }
.vp-close { background: transparent; border: none; color: rgba(255,255,255,0.5); font-size: 18px; cursor: pointer; }
.vp-close:hover { color: #fff; }
.vp-loading, .vp-empty { padding: 32px; text-align: center; color: rgba(255,255,255,0.4); font-size: 14px; }
.vp-list { overflow-y: auto; padding: 8px; }
.vp-item {
  padding: 12px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.vp-item:hover { background: rgba(255,255,255,0.06); }
.vp-time { color: #e2e8f0; font-size: 14px; }
.vp-meta { color: rgba(255,255,255,0.4); font-size: 12px; margin-top: 4px; }

/* 导出下拉菜单 */
.export-menu-wrap {
  position: relative;
  display: inline-block;
}
.export-dropdown {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  background: rgba(15, 23, 42, 0.95);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 8px;
  padding: 4px;
  display: flex;
  flex-direction: column;
  min-width: 140px;
  backdrop-filter: blur(8px);
  z-index: 60;
}
.export-dropdown button {
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.7);
  padding: 8px 12px;
  text-align: left;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
}
.export-dropdown button:hover { background: rgba(255,255,255,0.08); color: #fff; }

/* 版本下拉 (复用 export-dropdown 的浮层，但内容更密) */
.version-dropdown {
  min-width: 240px;
  max-width: 320px;
  max-height: 360px;
  overflow-y: auto;
}
.vm-state {
  padding: 14px 12px;
  text-align: center;
  color: rgba(255,255,255,0.4);
  font-size: 12px;
}
.vm-item {
  background: transparent;
  border: none;
  text-align: left;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  display: block;
  width: 100%;
}
.vm-item:hover { background: rgba(255,255,255,0.08); }
.vm-time { color: #e2e8f0; font-size: 13px; }
.vm-meta {
  color: rgba(255,255,255,0.45);
  font-size: 11px;
  margin-top: 2px;
  font-family: 'JetBrains Mono', monospace;
}
</style>
