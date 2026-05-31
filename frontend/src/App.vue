<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import Sidebar from './components/Sidebar.vue';
import SettingsView from './components/SettingsView.vue';
import WorkspacePickerView from './components/WorkspacePickerView.vue';
import PredictDialog from './components/PredictDialog.vue';
import BranchCompareDialog from './components/BranchCompareDialog.vue';
import ImportDialog from './components/ImportDialog.vue';
import GraphView from './components/views/GraphView.vue';
import ChatCenterView from './components/views/ChatCenterView.vue';
import ConversationListView from './components/views/ConversationListView.vue';
import DataSourceDetailView from './components/datasource/DataSourceDetailView.vue';
import DataSourceCreateDialog from './components/DataSourceCreateDialog.vue';
import DataSourcePageView from './components/views/DataSourcePageView.vue';
import NodeEditDialog from './components/dialogs/NodeEditDialog.vue';
import RelationEditDialog from './components/dialogs/RelationEditDialog.vue';
import TemplateLibraryDialog from './components/dialogs/TemplateLibraryDialog.vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from './types';
import './app.css';
import { toast, mountToastRoot } from './composables/useToast';
import { updateOntology, deleteOntology } from './api/ontology';
import { getConversation, updateConversation, deleteConversation as apiDeleteConversation } from './api/conversations';
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
import { useSidebarTree } from './composables/useSidebarTree';
import { getDataSource } from './api/dataSources';

const sel = ref<string | null>(null);
const sbExp = ref(true);
const sidebarW = ref(240);
const sbDragging = ref(false);
const SIDEBAR_MIN_W = 72;
const SIDEBAR_EDGE_GAP = 72;
const startSbResize = (e: MouseEvent) => {
  e.preventDefault();
  const startX = e.clientX;
  const startW = sidebarW.value;
  sbDragging.value = true;
  const onMove = (ev: MouseEvent) => {
    const nextW = startW + ev.clientX - startX;
    const maxW = Math.max(SIDEBAR_MIN_W, window.innerWidth - SIDEBAR_EDGE_GAP);
    sidebarW.value = Math.max(SIDEBAR_MIN_W, Math.min(maxW, nextW));
  };
  const onUp = () => {
    sbDragging.value = false;
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};
const graphRef = ref<any>(null);
const chatRef = ref<any>(null);
const { chatW, startDivider, isDragging } = useDivider(
  360,
  () => graphRef.value?.fitView(),
  () => sbExp.value ? sidebarW.value : 72,
);

const view = ref<'list' | 'graph' | 'chat' | 'settings' | 'workspace-picker' | 'datasource' | 'datasource-list' | 'conv-list'>('workspace-picker');

// 左侧顶级菜单导航：把菜单 route 映射到对应的 view
const onNav = (r: string) => {
  if (r === 'welcome') goWelcome();
  else if (r === 'graph-list') view.value = 'list';
  else if (r === 'conv-list') view.value = 'conv-list';
  else if (r === 'datasource') view.value = 'datasource-list';
  else if (r === 'settings') view.value = 'settings';
};
const currentDataSourceId = ref<string | null>(null);
const currentModelTitle = ref('供应链本体图');
const pendingChatSeed = ref<{ text: string; files: File[] } | null>(null);

const wsManager = useWorkspaces();
const sidebarTree = useSidebarTree();

const currentModelId = ref<string>('');
const compareDialogOpen = ref(false);
const importDialogOpen = ref(false);

// 数据源创建对话框状态
const dsCreateOpen = ref(false);
const openDataSourceListForWorkspace = (workspaceId: string) => {
  if (workspaceId && workspaceId !== wsManager.currentId.value) {
    wsManager.setCurrent(workspaceId);
    window.location.reload();
    return;
  }
  currentDataSourceId.value = null;
  view.value = 'datasource-list';
};
const openDataSourceCreateForWorkspace = (workspaceId: string) => {
  if (workspaceId && workspaceId !== wsManager.currentId.value) {
    wsManager.setCurrent(workspaceId);
    window.location.reload();
    return;
  }
  currentDataSourceId.value = null;
  view.value = 'datasource-list';
  dsCreateOpen.value = true;
};
const openDataSourceDetail = (id: string) => {
  currentDataSourceId.value = id;
  view.value = 'datasource';
};
const onDsCreated = async (id: string) => {
  currentDataSourceId.value = id;
  view.value = 'datasource';
  // 创建后立即回填侧栏缓存，让用户不需要手动刷新就能看到新数据源
  try {
    const ds = await getDataSource(id);
    const wsId = wsManager.currentId.value;
    if (wsId) sidebarTree.upsertDataSource(wsId, ds);
  } catch {
    // 回填失败不影响主流程；侧栏展开时会重新懒加载
  }
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
      // 回填侧栏血缘图缓存，让展开时立刻可见
      const wsId = wsManager.currentId.value;
      if (wsId) sidebarTree.upsertOntology(wsId, { id: m.id, name: m.name || m.title || '未命名图谱', updatedAt: Date.now() });
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
    view.value = 'chat';
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
// autoLayout 包了 history.snapshot()，非纯透传，保留包装；layoutDirection 是 ref 别名需保留以供模板解包。
const autoLayout = () => { history.snapshot(); graphActions.autoLayout(); };
const layoutDirection = graphActions.layoutDirection;

const merger = useImportMerge({
  nodes, edges,
  snapshotHistory: () => history.snapshot(),
  placeIncomingNodes: graphActions.placeIncomingNodes,
  autoLayout: graphActions.autoLayout,
  persist: persistCurrentModel,
});

const editor = useGraphEditor({
  nodes, edges, sel,
  snapshotHistory: () => history.snapshot(),
  persist: persistCurrentModel,
});
// 仅保留 ref / computed 别名（模板对“普通对象的嵌套 ref”不会自动解包，必须经顶层 const 解包）；
// 纯函数透传一律改用 editor.xxx 命名空间调用。
const editingRelation = editor.editingRelation;
const editRelGraphNodes = editor.editRelGraphNodes;
const editingNode = editor.editingNode;
const editNodeInputs = editor.editNodeInputs;
const editNodeOutputs = editor.editNodeOutputs;
const editInputLabels = editor.editInputLabels;
const editOutputLabels = editor.editOutputLabels;
const editableGraphNodes = editor.editableGraphNodes;

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
// 仅保留 ref 别名；函数一律改用 versionTpl.xxx 命名空间调用。
const showVersionMenu = versionTpl.showVersionMenu;
const versions = versionTpl.versions;
const versionsLoading = versionTpl.versionsLoading;
const showTemplates = versionTpl.showTemplates;
const templates = versionTpl.templates;
const templatesLoading = versionTpl.templatesLoading;

// 仅保留 ref 别名；函数一律改用 prediction.xxx / scenarios.xxx 命名空间调用。
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

// v1.0 导入提交:抽到 useImportFlow composable
const importFlow = useImportFlow({
  nodes,
  edges,
  activeBranchId,
  switchToTrunk: () => scenarios.switchBranch('trunk'),
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
    const wsId = wsManager.currentId.value;
    if (wsId) sidebarTree.upsertOntology(wsId, { id: saved.id, name: saved.name || saved.title || '未命名图谱', updatedAt: Date.now() });
  } finally {
    isCreating.value = false;
  }
};

/**
 * 「新对话」首次发言时由 ChatPanel 通过 ensure-model 回调进来,惰性创建一个新的
 * 本体模型并把当前模型上下文切到它。并发去重用单例 Promise 兜底。
 */
let ensuringModelPromise: Promise<void> | null = null;
const ensureCurrentModel = (titleHint: string): Promise<void> => {
  if (currentModelId.value) return Promise.resolve();
  if (ensuringModelPromise) return ensuringModelPromise;
  ensuringModelPromise = (async () => {
    try {
      const raw = (titleHint || '').trim();
      const base = raw.slice(0, 18) || '新建本体图';
      const draft: OntologyModel = {
        id: 'om_' + Date.now(),
        title: base.length > 16 ? base.slice(0, 16) + '…' : base,
        desc: raw || '通过对话生成的本体模型',
        graphData: { nodes: [], edges: [] },
      };
      const saved = await createOnBackend(draft);
      models.value.unshift(saved);
      currentModelTitle.value = saved.title || saved.name || '';
      currentModelId.value = saved.id;
      nodes.value = saved.graphData?.nodes || [];
      edges.value = saved.graphData?.edges || [];
      sel.value = null;
      activeBranchId.value = 'trunk';
      history.reset(nodes.value, edges.value);
      await scenarios.loadBranches(saved.id);
      const wsId = wsManager.currentId.value;
      if (wsId) sidebarTree.upsertOntology(wsId, {
        id: saved.id,
        name: saved.name || saved.title || '未命名图谱',
        updatedAt: Date.now(),
      });
    } finally {
      ensuringModelPromise = null;
    }
  })();
  return ensuringModelPromise;
};

/**
 * 「新对话」入口:清空当前本体模型上下文,切到中心聊天视图,并在 ChatPanel 里
 * 新建一个空白会话。欢迎横幅由 ChatCenterView 根据「会话是否还没有用户消息」自动
 * 决定显隐。首条用户消息发送时,ChatPanel 会通过 ensure-model 回调让我们落地新模型。
 */
const goWelcome = async () => {
  if (currentModelId.value && activeBranchId.value === 'trunk') {
    persistCurrentModel(true);
  }
  await chatRef.value?.flushPersist?.();
  prediction.abortLiveStream();
  currentModelId.value = '';
  currentModelTitle.value = '';
  nodes.value = [];
  edges.value = [];
  sel.value = null;
  activeBranchId.value = 'trunk';
  prediction.resetLiveState();
  view.value = 'chat';
  await nextTick();
  chatRef.value?.newConversation?.();
  chatRef.value?.focusInput?.();
};

const findConversationModelId = async (id: string): Promise<string | null> => {
  try {
    const conv = await getConversation(id);
    const msg = [...(conv.msgs || [])].reverse().find(m => m.graphModelId);
    return msg?.graphModelId || null;
  } catch (e) {
    console.warn('load conversation graph model failed', id, e);
    return null;
  }
};

const onOpenConversation = async (id: string) => {
  // 始终切换到中心聊天视图展示对话
  const modelId = await findConversationModelId(id);
  const target = modelId ? findModel(modelId) : (currentModelId.value ? findModel(currentModelId.value) : models.value[0]);
  if (target) {
    await openModel(target, 'chat');
  } else {
    view.value = 'chat';
  }
  await nextTick();
  chatRef.value?.switchConversation(id);
  chatRef.value?.focusInput?.();
};

const renameConversation = async (id: string, title: string) => {
  const wsId = wsManager.currentId.value;
  if (!wsId) return;
  try {
    const current = await getConversation(id);
    const saved = await updateConversation(id, {
      ...current,
      title,
    });
    sidebarTree.renameConversation(wsId, id, saved.title, saved.updatedAt || Date.now());
    if (chatRef.value?.currentConversationId?.() === id) {
      chatRef.value?.setConversationTitle?.(saved.title);
    }
    toast.success('已重命名');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '重命名失败');
  }
};

const deleteConversation = async (id: string) => {
  const wsId = wsManager.currentId.value;
  if (!wsId) return;
  try {
    await apiDeleteConversation(id);
    sidebarTree.removeConversation(wsId, id);
    if (chatRef.value?.currentConversationId?.() === id) {
      await chatRef.value?.newConversation?.();
      goWelcome();
    }
    toast.success('已删除');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
};

const onNewConversation = async () => {
  const target = currentModelId.value ? findModel(currentModelId.value) : models.value[0];
  if (target) {
    await openModel(target, 'chat');
  } else {
    view.value = 'chat';
  }
  await nextTick();
  chatRef.value?.newConversation();
  chatRef.value?.focusInput?.();
};

const onOpenOntologyModel = async (id: string) => {
  const m = findModel(id);
  if (!m) return;
  await chatRef.value?.flushPersist?.();
  chatW.value = 0;
  await openModel(m, 'graph');
};

const backToChat = async () => {
  await chatRef.value?.flushPersist?.();
  if (currentModelId.value) {
    const m = findModel(currentModelId.value);
    if (m) {
      await openModel(m, 'chat');
      chatW.value = 360;
      return;
    }
  }
  view.value = 'chat';
  chatW.value = 360;
};

const renameGraph = async (id: string, title: string) => {
  const wsId = wsManager.currentId.value;
  if (!wsId) return;
  const m = findModel(id);
  if (!m) return;
  try {
    const next = { ...m, title, name: title };
    const saved = await updateOntology(id, next);
    const idx = models.value.findIndex(x => x.id === id);
    if (idx >= 0) models.value[idx] = saved;
    if (currentModelId.value === id) currentModelTitle.value = saved.title || saved.name || title;
    sidebarTree.renameOntology(wsId, id, saved.name || saved.title || title, (saved as any).updatedAt || Date.now());
    toast.success('已重命名');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '重命名失败');
  }
};

const deleteGraph = async (id: string) => {
  const wsId = wsManager.currentId.value;
  if (!wsId) return;
  try {
    await deleteOntology(id);
    models.value = models.value.filter(m => m.id !== id);
    sidebarTree.removeOntology(wsId, id);
    if (currentModelId.value === id) {
      currentModelId.value = '';
      currentModelTitle.value = '';
      goWelcome();
    }
    toast.success('已删除');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
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
      :class="{ dragging: sbDragging }"
      :expanded="sbExp"
      :view="view"
      :style="sbExp ? `--sidebar-w: ${sidebarW}px` : ''"
      @toggle="sbExp = !sbExp"
      @nav="onNav"
      @switch-workspace="onWorkspaceSwitched"
      @open-conversation="onOpenConversation"
      @open-graph="onOpenOntologyModel"
      @open-datasource="openDataSourceDetail"
      @open-datasources="openDataSourceListForWorkspace"
      @add-datasource="openDataSourceCreateForWorkspace"
      @rename-conversation="renameConversation"
      @delete-conversation="deleteConversation"
      @rename-graph="renameGraph"
      @delete-graph="deleteGraph"
    />
    <div
      v-if="sbExp"
      :class="['sb-resizer', { dragging: sbDragging }]"
      @mousedown="startSbResize"
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
          <button class="tb-btn" @click="versionTpl.openPreview" title="在新标签页里以只读模式预览整张图谱" :disabled="!currentModelId">预览</button>
          <div class="export-menu-wrap">
            <button class="tb-btn" @click="versionTpl.toggleVersionMenu" title="查看和恢复历史版本" :disabled="!currentModelId">
              🕐 版本
              <svg viewBox="0 0 24 24" width="10" height="10" stroke="currentColor" stroke-width="2" fill="none" style="margin-left:2px"><polyline points="6 9 12 15 18 9"/></svg>
            </button>
            <div v-if="showVersionMenu" class="export-dropdown version-dropdown">
              <div v-if="versionsLoading" class="vm-state">加载中…</div>
              <div v-else-if="versions.length === 0" class="vm-state">暂无历史版本</div>
              <template v-else>
                <button v-for="v in versions" :key="v.timestamp"
                        class="vm-item"
                        @click="versionTpl.doRestoreVersion(v.timestamp); showVersionMenu = false">
                  <div class="vm-time">{{ new Date(v.timestamp).toLocaleString() }}</div>
                  <div class="vm-meta">{{ v.nodeCount }} 节点 · {{ v.edgeCount }} 关系 · {{ formatFileSize(v.fileSize) }}</div>
                </button>
              </template>
            </div>
          </div>
          <button class="tb-btn" @click="versionTpl.openTemplates" title="从模板创建新模型">📋 模板</button>
          <button class="tb-btn" @click="versionTpl.saveAsTemplate" title="将当前模型另存为模板" :disabled="!currentModelId">💾 存为模板</button>
          <button class="tb-btn hi" v-if="chatW === 0" @click="backToChat" title="回到对话">回到对话</button>
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

      <!-- Conversation List View -->
      <ConversationListView
        v-if="view === 'conv-list'"
        @open="onOpenConversation"
        @new="onNewConversation"
      />

      <!-- Settings View -->
      <SettingsView v-if="view === 'settings'" @switch-workspace="onWorkspaceSwitched" />

      <!-- DataSource Detail View -->
      <DataSourceDetailView v-else-if="view === 'datasource' && currentDataSourceId"
                            :ds-id="currentDataSourceId"
                            :has-current-model="!!currentModelId"
                            @ontology-extracted="onImportCommit" />

      <!-- DataSource List / Add Page -->
      <DataSourcePageView
        v-else-if="view === 'datasource-list'"
        @open="openDataSourceDetail"
      />

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
        @update-node-schema="editor.updateNodeSchema"
        @update-edge-schema="editor.updateEdgeSchema"
        @undo="undoGraph"
        @redo="redoGraph"
        @update:selected-id="(id) => sel = id"
        @move="onMove"
        @drag-start="onDragStart"
        @auto-layout="autoLayout"
        @toggle-layout-direction="graphActions.toggleLayoutDirection"
        @clear="editor.clearCanvas"
        @predict-from="prediction.openPredictDialog"
        @edit-node="editor.openEditNode"
        @delete-node="editor.deleteNode"
        @delete-nodes="editor.deleteNodes"
        @switch-branch="scenarios.switchBranch"
        @close-timeline="prediction.closeTimeline"
        @focus-node="focusNodeInGraph"
        @update="merger.onUpdate"
        @seed-consumed="pendingChatSeed = null"
        @start-divider="startDivider"
        @graph-ref="(el) => graphRef = el"
        @chat-ref="(el) => chatRef = el"
        @abort-prediction="prediction.closeTimeline"
        @update-node-props="editor.updateNodeProps"
        @delete-edge="editor.deleteEdge"
        @clear-diff="clearDiffHighlight"
        @add-node="editor.addNodeAtPosition"
        @add-edges="editor.addEdgesBatch"
        @edit-edge-relation="editor.openEditRelation"
        @delete-relation="editor.deleteRelation"
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
        :model-id="currentModelId"
        :ensure-model="ensureCurrentModel"
        @update="merger.onUpdate"
        @clear-graph="editor.clearCanvas"
        @seed-consumed="pendingChatSeed = null"
        @abort-prediction="prediction.closeTimeline"
        @chat-ref="(el) => chatRef = el"
        @view-graph="onOpenOntologyModel"
      />

      <!-- Predict Dialog (modal) -->
      <PredictDialog
        :open="predictDialogOpen"
        :nodes="nodes"
        :edges="edges"
        :initialSeedIds="predictSeeds"
        :modelId="currentModelId"
        @close="predictDialogOpen = false"
        @submit="prediction.startPrediction"
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
      <NodeEditDialog
        v-if="editingNode"
        :editing-node="editingNode"
        :editable-graph-nodes="editableGraphNodes"
        :edit-node-inputs="editNodeInputs"
        :edit-node-outputs="editNodeOutputs"
        :edit-input-labels="editInputLabels"
        :edit-output-labels="editOutputLabels"
        @save="editor.saveEditNode"
        @cancel="editor.cancelEditNode"
        @toggle-input="editor.toggleEditInput"
        @toggle-output="editor.toggleEditOutput"
      />

      <!-- 关系编辑对话框 -->
      <RelationEditDialog
        v-if="editingRelation"
        :editing-relation="editingRelation"
        :edit-rel-graph-nodes="editRelGraphNodes"
        @save="editor.saveEditRelation"
        @cancel="editor.cancelEditRelation"
        @delete="editor.deleteEditingRelation"
        @toggle-input="editor.toggleRelInput"
        @toggle-output="editor.toggleRelOutput"
      />

      <!-- 模板库面板 -->
      <TemplateLibraryDialog
        v-if="showTemplates"
        :templates="templates"
        :templates-loading="templatesLoading"
        @close="versionTpl.closeTemplates"
        @create-from="versionTpl.createFromTemplate"
        @remove="versionTpl.removeTemplate"
      />
    </div>
  </div>
</template>

