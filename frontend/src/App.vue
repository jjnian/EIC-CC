<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import Sidebar from './components/Sidebar.vue';
import GraphCanvas from './components/GraphCanvas.vue';
import NodeInfo from './components/NodeInfo.vue';
import ChatPanel from './components/ChatPanel.vue';
import SettingsView from './components/SettingsView.vue';
import WelcomeChat from './components/WelcomeChat.vue';
import PredictDialog from './components/PredictDialog.vue';
import BranchPicker from './components/BranchPicker.vue';
import BranchCompareDialog from './components/BranchCompareDialog.vue';
import ImportDialog from './components/ImportDialog.vue';
import ScenarioTimeline from './components/ScenarioTimeline.vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from './types';
import { toast, mountToastRoot } from './composables/useToast';
import { confirm } from './composables/useConfirm';
import { updateOntology } from './api/ontology';
import { ApiError } from './api/http';
import { useDivider } from './composables/useDivider';
import { useImportFlow } from './composables/useImportFlow';
import { useScenarios } from './composables/useScenarios';
import { usePrediction } from './composables/usePrediction';
import { useOntologyModel } from './composables/useOntologyModel';

const sel = ref<string | null>(null);
const sbExp = ref(true);
const showSchema = ref(false);
const graphRef = ref<any>(null);
const { chatW, startDivider } = useDivider(360, () => graphRef.value?.fitView());

const view = ref<'welcome' | 'list' | 'graph' | 'settings'>('welcome');
const currentModelTitle = ref('供应链本体图');
const pendingChatSeed = ref<{ text: string; files: File[] } | null>(null);

const currentModelId = ref<string>('');
const compareDialogOpen = ref(false);
const importDialogOpen = ref(false);

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

onMounted(() => {
  mountToastRoot();
  loadOntologyModels();
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
const liveAbort = prediction.liveAbort;
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
  showSchema.value = false;
  welcomeResetTick.value++;
};

const openModelById = (id: string) => {
  const m = findModel(id);
  if (m) openModel(m);
};

const selNode = computed(() => nodes.value.find(n => n.id === sel.value) || null);

const onMove = (id: string, x: number, y: number) => {
  const n = nodes.value.find(n => n.id === id);
  if (n) {
    n.x = x;
    n.y = y;
    persistCurrentModel();
  }
};

const onUpdate = (addNodes: OntologyNode[], addEdges: OntologyEdge[]) => {
  nodes.value.push(...addNodes.map(n => ({...n, isNew: true})));
  edges.value.push(...addEdges);
  setTimeout(() => {
    nodes.value.forEach(n => n.isNew = false);
  }, 800);
  persistCurrentModel();
};

const clearCanvas = () => {
  // Flush any pending debounced save with previous state first to keep history honest.
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  nodes.value = [];
  edges.value = [];
  sel.value = null;
  persistCurrentModel(true);
};

const autoLayout = () => {
  if (nodes.value.length === 0) return;

  // --- Step 1: BFS from roots to compute shortest-path levels ---
  const hasIncoming = new Set(edges.value.map(e => e.to));
  const roots = nodes.value.filter(n => !hasIncoming.has(n.id));

  const levels: Record<string, number> = {};
  nodes.value.forEach(n => { levels[n.id] = Infinity; });
  roots.forEach(r => { levels[r.id] = 0; });

  const queue = roots.map(r => r.id);
  while (queue.length > 0) {
    const current = queue.shift()!;
    const currentLvl = levels[current];
    edges.value.filter(e => e.from === current).forEach(e => {
      if (levels[e.to] === Infinity) {
        levels[e.to] = currentLvl + 1;
        queue.push(e.to);
      }
    });
  }
  nodes.value.forEach(n => {
    if (levels[n.id] === Infinity) levels[n.id] = 0;
  });

  // --- Step 2: Group by level, sort by parent barycenter ---
  const groups: Record<number, any[]> = {};
  let maxLevel = 0;
  const isolated: any[] = [];

  nodes.value.forEach(n => {
    const hasEdge = edges.value.some(e => e.from === n.id || e.to === n.id);
    if (!hasEdge) { isolated.push(n); return; }
    const lvl = levels[n.id];
    if (lvl > maxLevel) maxLevel = lvl;
    if (!groups[lvl]) groups[lvl] = [];
    groups[lvl].push(n);
  });

  for (let lvl = 1; lvl <= maxLevel; lvl++) {
    const group = groups[lvl];
    if (!group || group.length <= 1) continue;
    group.sort((a, b) => {
      const avgParentY = (id: string) => {
        const parents = edges.value.filter(e => e.to === id).map(e => e.from);
        if (!parents.length) return Infinity;
        return parents.reduce((s: number, p: string) => {
          const pg = groups[levels[p]];
          return s + (pg ? pg.findIndex(n => n.id === p) : 0);
        }, 0) / parents.length;
      };
      return avgParentY(a.id) - avgParentY(b.id);
    });
  }

  // --- Step 3: Position — compact left→right, top-down within level ---
  const nodeW = 172, nodeH = 44;
  const xGap = 30;
  const yGap = 40;
  const xSpacing = nodeW + xGap; // 202px per level
  const ySpacing = nodeH + yGap; // 84px per node in same level
  const startX = 60;
  const startY = 60;

  for (let lvl = 0; lvl <= maxLevel; lvl++) {
    const group = groups[lvl];
    if (!group) continue;
    group.forEach((n, idx) => {
      n.x = startX + lvl * xSpacing;
      n.y = startY + idx * ySpacing;
    });
  }

  if (isolated.length > 0) {
    isolated.forEach((n, idx) => {
      n.x = startX + (maxLevel + 1) * xSpacing;
      n.y = startY + idx * ySpacing;
    });
  }

  // --- Step 4: Fit view ---
  if (graphRef.value) {
    setTimeout(() => {
      graphRef.value.fitView();
    }, 50);
  }
  persistCurrentModel();
};

// ===== Export / Share (topbar buttons) =====
const exportGraph = () => {
  const m = findModel(currentModelId.value);
  const payload = {
    id: m?.id, title: m?.title || currentModelTitle.value,
    exportedAt: new Date().toISOString(),
    nodes: nodes.value, edges: edges.value
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `${(m?.title || 'graph').replace(/[^\w一-龥-]+/g, '_')}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
};

const shareGraph = async () => {
  const m = findModel(currentModelId.value);
  const summary = `${m?.title || '本体模型'}\n节点 ${nodes.value.length} · 关系 ${edges.value.length}\n${nodes.value.slice(0, 10).map(n => `· ${n.label}（${n.type}）`).join('\n')}`;
  try {
    await navigator.clipboard.writeText(summary);
    toast.success('图谱摘要已复制到剪贴板');
  } catch {
    toast.warn('剪贴板不可用，已输出到控制台');
    console.info('[shareGraph]\n' + summary);
  }
};

const focusNodeInGraph = (id: string) => {
  sel.value = id;
  graphRef.value?.focusNode?.(id);
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
    />
    <div class="main">
      <div class="topbar">
        <div class="tb-title" v-if="view === 'graph'">
          <span class="tb-title-text">{{ currentModelTitle }}</span>
          <span class="bc-star">☆</span>
        </div>
        <div class="tb-tools" v-if="view === 'graph'">
          <BranchPicker
            :branches="branches"
            :activeBranchId="activeBranchId"
            @switch="switchBranch"
            @delete="deleteBranch"
            @migrate="migrateBranches"
          />
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
          <span class="tb-badge ok">● {{ nodes.length }} 节点</span>
          <span class="tb-badge">{{ edges.length }} 关系</span>
          <button class="tb-btn" @click="showSchema = !showSchema">Schema</button>
          <button class="tb-btn" @click="exportGraph" title="下载当前图谱为 JSON">导出</button>
          <button class="tb-btn hi" @click="shareGraph" title="复制图谱摘要到剪贴板">共享</button>
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
      <div class="content" v-else-if="view === 'graph'">
        <div class="graph-area">
          <GraphCanvas
            ref="graphRef"
            :nodes="nodes"
            :edges="edges"
            :selId="sel"
            @move="onMove"
            @select="id => sel = id"
            @auto-layout="autoLayout"
            @clear="clearCanvas"
            @predict-from="openPredictDialog"
          />
          <div v-if="activeBranchId !== 'trunk' && !liveActive" class="branch-banner">
            <span class="bb-icon">⚡</span>
            <span>当前查看推演分支 · 可右键节点从此再次分叉</span>
            <button class="bb-back" @click="switchBranch('trunk')">返回主分支</button>
          </div>
          <NodeInfo :node="selNode" :nodes="nodes" :edges="edges" :isOpen="showSchema" @close="() => { sel = null; showSchema = false; }" />
        </div>
        <div :class="['resize-divider', { dragging: divDrag }]" @mousedown="startDivider" />
        <ScenarioTimeline
          v-if="liveActive"
          :steps="liveSteps"
          :loading="liveLoading"
          :nodes="nodes"
          :intent="liveIntent"
          :style="{ width: chatW + 'px', flexShrink: 0 }"
          @close="closeTimeline"
          @focus-node="focusNodeInGraph"
        />
        <ChatPanel
          v-else
          :nodes="nodes"
          :edges="edges"
          :width="chatW"
          :seed="pendingChatSeed"
          @update="onUpdate"
          @clear-graph="clearCanvas"
          @seed-consumed="pendingChatSeed = null"
        />
      </div>

      <!-- Predict Dialog (modal) -->
      <PredictDialog
        :open="predictDialogOpen"
        :nodes="nodes"
        :initialSeedIds="predictSeeds"
        @close="predictDialogOpen = false"
        @submit="startPrediction"
      />

      <!-- Branch Compare Dialog (modal) -->
      <BranchCompareDialog
        :open="compareDialogOpen"
        :branches="branches"
        @close="compareDialogOpen = false"
      />

      <!-- Import Dialog (modal) -->
      <ImportDialog
        :open="importDialogOpen"
        :hasCurrentModel="!!currentModelId"
        :currentNodes="nodes"
        @close="importDialogOpen = false"
        @commit="onImportCommit"
      />
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
.branch-banner {
  position: absolute;
  top: 78px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(251, 191, 36, 0.12);
  border: 1px solid rgba(251, 191, 36, 0.35);
  color: #fbbf24;
  padding: 6px 14px;
  border-radius: 100px;
  font-size: 12px;
  z-index: 20;
  box-shadow: 0 4px 16px rgba(251, 191, 36, 0.15);
  backdrop-filter: blur(10px);
}
.bb-icon { font-size: 13px; }
.bb-back {
  background: rgba(251, 191, 36, 0.2);
  border: none;
  color: #fbbf24;
  padding: 3px 10px;
  border-radius: 100px;
  font-size: 11px;
  cursor: pointer;
  font-family: inherit;
  font-weight: 500;
}
.bb-back:hover { background: rgba(251, 191, 36, 0.32); }
</style>
