<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue';
import Sidebar from './components/Sidebar.vue';
import SettingsView from './components/SettingsView.vue';
import WelcomeChat from './components/WelcomeChat.vue';
import PredictDialog from './components/PredictDialog.vue';
import BranchCompareDialog from './components/BranchCompareDialog.vue';
import ImportDialog from './components/ImportDialog.vue';
import GraphView from './components/views/GraphView.vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from './types';
import { toast, mountToastRoot } from './composables/useToast';
import { confirm } from './composables/useConfirm';
import { updateOntology, listVersions, restoreVersion, listGraphTemplates, saveGraphTemplate, deleteGraphTemplate } from './api/ontology';
import { ApiError } from './api/http';
import { useDivider } from './composables/useDivider';
import { useImportFlow } from './composables/useImportFlow';
import { useScenarios } from './composables/useScenarios';
import { usePrediction } from './composables/usePrediction';
import { useOntologyModel } from './composables/useOntologyModel';
import { useGraphActions } from './composables/useGraphActions';
import { useGraphHistory } from './composables/useGraphHistory';
import { NT } from './constants';

const sel = ref<string | null>(null);
const sbExp = ref(true);
const graphRef = ref<any>(null);
const { chatW, startDivider, isDragging } = useDivider(360, () => graphRef.value?.fitView());

const view = ref<'welcome' | 'list' | 'graph' | 'settings'>('welcome');
const currentModelTitle = ref('供应链本体图');
const pendingChatSeed = ref<{ text: string; files: File[] } | null>(null);

const currentModelId = ref<string>('');
const compareDialogOpen = ref(false);
const importDialogOpen = ref(false);

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

// Scenarios 与 Prediction 通过 getter 解耦循环依赖:
//   scenarios 需要 prediction.abortLiveStream / resetLiveState
//   prediction 需要 scenarios.activeBranchId / trunkSnapshot / branches / switchBranch
// prediction 通过 getter 访问 scenarios,避免初始化顺序问题。
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
const trunkSnapshot = scenarios.trunkSnapshot;

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
  // 输入框里别抢快捷键
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

onMounted(() => {
  mountToastRoot();
  loadOntologyModels();
  window.addEventListener('keydown', onGlobalKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
});

const openModel = async (m: OntologyModel) => {
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
  view.value = 'graph';
  history.reset(nodes.value, edges.value);
  await scenarios.loadBranches(m.id);
};

// 暴露给模板的别名(过渡期内,模板还在用这些名字)
const loadBranches = scenarios.loadBranches;
const switchBranch = scenarios.switchBranch;
const migrateBranches = scenarios.migrateBranches;
const deleteBranch = scenarios.deleteBranch;
const collectAncestorChain = scenarios.collectAncestorChain;
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
    await openModel(saved);
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

const openModelById = (id: string) => {
  const m = findModel(id);
  if (m) openModel(m);
};

const onDragStart = (_id: string) => {
  // 一次拖拽只快照一次:落点前的"原状态"先入栈,然后才开始连续 move。
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

/**
 * 把 LLM 返回的新增节点/关系合并到现有图,做两层去重:
 *   1. id 命中(同一节点重复回写) → 丢弃
 *   2. label+type 命中现有节点 → 把新节点视作"已存在",并在 edges 里把对它的引用重写到旧 id
 * edges 同样做 id 去重 + (from,to,label) 去重。
 */
const dedupeIncoming = (addNodes: OntologyNode[], addEdges: OntologyEdge[]) => {
  const norm = (s?: string) => (s || '').trim().toLowerCase();
  const byId = new Map(nodes.value.map(n => [n.id, n]));
  const byKey = new Map<string, OntologyNode>();
  nodes.value.forEach(n => byKey.set(norm(n.label) + '|' + norm(n.type), n));

  const idRemap: Record<string, string> = {};
  const acceptedNodes: OntologyNode[] = [];
  for (const n of addNodes) {
    if (!n || !n.id) continue;
    if (byId.has(n.id)) { idRemap[n.id] = n.id; continue; }
    const k = norm(n.label) + '|' + norm(n.type);
    const hit = byKey.get(k);
    if (hit) { idRemap[n.id] = hit.id; continue; }
    acceptedNodes.push(n);
    byId.set(n.id, n);
    byKey.set(k, n);
  }

  const edgeKey = new Set(edges.value.map(e => e.from + '→' + e.to + '|' + norm(e.label)));
  const edgeIdSet = new Set(edges.value.map(e => e.id));
  const acceptedEdges: OntologyEdge[] = [];
  for (const e of addEdges) {
    if (!e) continue;
    const from = idRemap[e.from] || e.from;
    const to = idRemap[e.to] || e.to;
    if (!byId.has(from) || !byId.has(to)) continue; // 引用的节点不在图里,丢弃
    const k = from + '→' + to + '|' + norm(e.label);
    if (edgeKey.has(k)) continue;
    let id = e.id;
    if (!id || edgeIdSet.has(id)) id = 'e_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 7);
    acceptedEdges.push({ ...e, id, from, to });
    edgeKey.add(k);
    edgeIdSet.add(id);
  }

  return { nodes: acceptedNodes, edges: acceptedEdges, skipped: {
    nodes: addNodes.length - acceptedNodes.length,
    edges: addEdges.length - acceptedEdges.length,
  }};
};

const onUpdate = (addNodes: OntologyNode[], addEdges: OntologyEdge[]) => {
  const { nodes: newNodes, edges: newEdges, skipped } = dedupeIncoming(addNodes, addEdges);
  if (newNodes.length === 0 && newEdges.length === 0) {
    if (skipped.nodes || skipped.edges) {
      toast.info(`已忽略 ${skipped.nodes} 个重复节点 / ${skipped.edges} 条重复关系`);
    }
    return;
  }

  history.snapshot();

  const existingIds = new Set(nodes.value.map(n => n.id));
  const connectsToExisting = newEdges.some(e => existingIds.has(e.from) || existingIds.has(e.to));

  // 用 placeIncomingNodes 给新节点选一个不和现有图冲突的初始位置(右侧暂存区)。
  graphActions.placeIncomingNodes(newNodes);

  nodes.value.push(...newNodes.map(n => ({...n, isNew: true})));
  edges.value.push(...newEdges);
  setTimeout(() => {
    nodes.value.forEach(n => n.isNew = false);
  }, 800);
  persistCurrentModel();

  if (skipped.nodes || skipped.edges) {
    toast.info(`已合并:+${newNodes.length} 节点 / +${newEdges.length} 关系,跳过 ${skipped.nodes}/${skipped.edges} 个重复项`);
  }

  // 新节点接上了现有血缘 / 或图本身还很小时,自动跑一次分层布局,让因果链一目了然。
  const shouldAutoLayout =
    newNodes.length > 0 && (connectsToExisting || nodes.value.length <= 12);
  if (shouldAutoLayout) {
    nextTick(() => graphActions.autoLayout());
  }
};

const clearCanvas = () => {
  if (nodes.value.length === 0 && edges.value.length === 0) return;
  history.snapshot();
  // Flush any pending debounced save with previous state first to keep history honest.
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  nodes.value = [];
  edges.value = [];
  sel.value = null;
  persistCurrentModel(true);
};

const graphActions = useGraphActions({
  nodes,
  edges,
  currentModel: () => findModel(currentModelId.value),
  currentTitle: () => currentModelTitle.value,
  fitView: () => graphRef.value?.fitView(),
  persist: persistCurrentModel,
});
const autoLayout = () => { history.snapshot(); graphActions.autoLayout(); };
const exportGraph = graphActions.exportGraph;
const toggleLayoutDirection = graphActions.toggleLayoutDirection;
const layoutDirection = graphActions.layoutDirection;

// 导出菜单状态
const showExportMenu = ref(false);

// Schema 面板开关
const schemaOpen = ref(false);
const toggleSchema = () => { schemaOpen.value = !schemaOpen.value; };

// Schema 面板里改属性/约束 → 落到 nodes/edges 上 + 入历史 + 防抖保存
const updateNodeSchema = (id: string, patch: any) => {
  const idx = nodes.value.findIndex(n => n.id === id);
  if (idx === -1) return;
  history.snapshot();
  nodes.value[idx] = { ...nodes.value[idx], ...patch };
  persistCurrentModel();
};
const updateEdgeSchema = (id: string, patch: any) => {
  const idx = edges.value.findIndex(e => e.id === id);
  if (idx === -1) return;
  history.snapshot();
  edges.value[idx] = { ...edges.value[idx], ...patch };
  persistCurrentModel();
};

// 在画布空白处右键添加节点
const addNodeAtPosition = (type: string, x: number, y: number) => {
  history.snapshot();
  const id = 'n_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 7);
  const label = type === 'class' ? '新对象' : '新关系类型';
  const newNode: OntologyNode = { id, label, type, x, y, source: 'manual' };
  nodes.value.push(newNode);
  sel.value = id;
  editingNode.value = { ...newNode };
  persistCurrentModel();
};

// 节点编辑对话框状态
const editingNode = ref<OntologyNode | null>(null);

const openEditNode = (id: string) => {
  const n = nodes.value.find(n => n.id === id);
  if (n) editingNode.value = { ...n };
};

const saveEditNode = () => {
  if (!editingNode.value) return;
  const idx = nodes.value.findIndex(n => n.id === editingNode.value!.id);
  if (idx === -1) return;
  history.snapshot();
  nodes.value[idx] = { ...nodes.value[idx], label: editingNode.value.label, type: editingNode.value.type };
  editingNode.value = null;
  persistCurrentModel();
};

const cancelEditNode = () => {
  editingNode.value = null;
};

// 更新节点属性
const updateNodeProps = (id: string, newProps: { key: string; value: any; source?: string }[]) => {
  const n = nodes.value.find(n => n.id === id);
  if (!n) return;
  history.snapshot();
  n.props = newProps;
  persistCurrentModel();
};

// 删除单条关系
const deleteEdge = (edgeId: string) => {
  const idx = edges.value.findIndex(e => e.id === edgeId);
  if (idx === -1) return;
  history.snapshot();
  edges.value.splice(idx, 1);
  persistCurrentModel();
};

// 删除节点（含确认弹窗）
const deleteNode = async (id: string) => {
  const n = nodes.value.find(n => n.id === id);
  if (!n) return;
  const ok = await confirm({
    title: '删除节点',
    message: `确定删除节点「${n.label}」？相关的关系也会一并删除。`,
    danger: true,
    confirmLabel: '删除',
  });
  if (!ok) return;
  history.snapshot();
  nodes.value = nodes.value.filter(n => n.id !== id);
  edges.value = edges.value.filter(e => e.from !== id && e.to !== id);
  if (sel.value === id) sel.value = null;
  persistCurrentModel();
};

// 批量删除节点（含确认弹窗）
const deleteNodes = async (ids: string[]) => {
  if (!ids.length) return;
  const ok = await confirm({
    title: '批量删除',
    message: `确定删除选中的 ${ids.length} 个节点？相关关系也会一并删除。`,
    danger: true,
    confirmLabel: '删除',
  });
  if (!ok) return;
  history.snapshot();
  const idSet = new Set(ids);
  nodes.value = nodes.value.filter(n => !idSet.has(n.id));
  edges.value = edges.value.filter(e => !idSet.has(e.from) && !idSet.has(e.to));
  if (sel.value && idSet.has(sel.value)) sel.value = null;
  persistCurrentModel();
};

const focusNodeInGraph = (id: string) => {
  sel.value = id;
  graphRef.value?.focusNode?.(id);
};

// 版本历史下拉
const showVersionMenu = ref(false);
const versions = ref<{ timestamp: number; nodeCount: number; edgeCount: number; fileSize: number }[]>([]);
const versionsLoading = ref(false);

const loadVersions = async () => {
  if (!currentModelId.value) return;
  versionsLoading.value = true;
  try {
    versions.value = await listVersions(currentModelId.value);
  } catch (e) {
    console.error('Failed to load versions', e);
    versions.value = [];
  } finally {
    versionsLoading.value = false;
  }
};

const toggleVersionMenu = async () => {
  if (!currentModelId.value) return;
  showVersionMenu.value = !showVersionMenu.value;
  if (showVersionMenu.value) await loadVersions();
};

const doRestoreVersion = async (timestamp: number) => {
  if (!currentModelId.value) return;
  const ok = await confirm({
    title: '恢复版本',
    message: `确定恢复到 ${new Date(timestamp).toLocaleString()} 的版本？当前版本会自动保存为快照。`,
    confirmLabel: '恢复',
  });
  if (!ok) return;
  try {
    const restored = await restoreVersion(currentModelId.value, timestamp);
    if (restored.graphData) {
      nodes.value = restored.graphData.nodes || [];
      edges.value = restored.graphData.edges || [];
      history.reset(nodes.value, edges.value);
    }
    showVersionMenu.value = false;
    toast.success('已恢复到历史版本');
  } catch (e) {
    toast.error('恢复失败');
  }
};

// 模板库状态与方法
const showTemplates = ref(false);
const templates = ref<any[]>([]);
const templatesLoading = ref(false);

const openTemplates = async () => {
  showTemplates.value = true;
  templatesLoading.value = true;
  try {
    templates.value = await listGraphTemplates();
  } catch (e) {
    console.error('Failed to load templates', e);
    templates.value = [];
  } finally {
    templatesLoading.value = false;
  }
};

const saveAsTemplate = async () => {
  if (!currentModelId.value) return;
  const model = findModel(currentModelId.value);
  if (!model) return;
  const tpl = {
    title: (model.title || '未命名') + ' (模板)',
    desc: model.desc || '',
    graphData: { nodes: nodes.value, edges: edges.value },
  };
  try {
    await saveGraphTemplate(tpl);
    toast.success('已保存为模板');
  } catch (e) {
    toast.error('保存模板失败');
  }
};

const createFromTemplate = async (tpl: any) => {
  if (isCreating.value) return;
  isCreating.value = true;
  try {
    const title = (tpl.title || '未命名').replace(/ \(模板\)$/, '');
    const draft: OntologyModel = {
      id: 'om_' + Date.now(),
      title,
      desc: tpl.desc || '',
      graphData: tpl.graphData || { nodes: [], edges: [] },
    };
    const saved = await createOnBackend(draft);
    models.value.unshift(saved);
    showTemplates.value = false;
    await openModel(saved);
    toast.success('已从模板创建新模型');
  } catch (e) {
    toast.error('从模板创建失败');
  } finally {
    isCreating.value = false;
  }
};

const removeTemplate = async (id: string) => {
  const ok = await confirm({
    title: '删除模板',
    message: '确定删除此模板？',
    danger: true,
    confirmLabel: '删除',
  });
  if (!ok) return;
  try {
    await deleteGraphTemplate(id);
    templates.value = templates.value.filter(t => t.id !== id);
    toast.success('已删除');
  } catch (e) {
    toast.error('删除失败');
  }
};

const closeTemplates = () => { showTemplates.value = false; };

const formatFileSize = (bytes: number) => {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return bytes + ' B';
};

/** 在新标签页打开当前模型的只读预览页(走 ?preview=<id> 路径,main.ts 据此挂载 PreviewView)。 */
const openPreview = () => {
  if (!currentModelId.value) return;
  // 先把当前的修改 flush 一下,避免新 tab 加载到旧数据
  persistCurrentModel(true);
  const url = `${location.origin}${location.pathname}?preview=${encodeURIComponent(currentModelId.value)}`;
  window.open(url, '_blank', 'noopener');
};
</script>

<template>
  <div class="app">
    <Sidebar
      :expanded="sbExp"
      :models="models"
      :currentModelId="currentModelId"
      :view="view"
      @toggle="sbExp = !sbExp"
      @nav="r => { if(r==='welcome') goWelcome(); else if(r==='list') view='list'; else if(r==='settings') view='settings'; }"
      @open-model="openModelById"
      @delete-model="deleteOntologyModel"
    />
    <div class="main">
      <div class="topbar">
        <div class="tb-title" v-if="view === 'graph'">
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

      <!-- Welcome / Chat-first View -->
      <WelcomeChat v-if="view === 'welcome'" :resetTick="welcomeResetTick" @submit="onWelcomeSubmit" />

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
      <SettingsView v-if="view === 'settings'" />

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
        @abort-prediction="closeTimeline"
        @update-node-props="updateNodeProps"
        @delete-edge="deleteEdge"
        @clear-diff="clearDiffHighlight"
        @add-node="addNodeAtPosition"
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

      <!-- 节点编辑对话框 -->
      <div v-if="editingNode" class="modal-mask" @click.self="cancelEditNode">
        <div class="edit-node-dialog">
          <h3>编辑节点</h3>
          <label>名称
            <input v-model="editingNode.label" class="edit-input" @keydown.enter="saveEditNode" />
          </label>
          <label>类型
            <select v-model="editingNode.type" class="edit-input">
              <option v-for="(t, k) in NT" :key="k" :value="k">{{ t.label }}</option>
            </select>
          </label>
          <div class="edit-actions">
            <button class="edit-cancel" @click="cancelEditNode">取消</button>
            <button class="edit-save" @click="saveEditNode">保存</button>
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
