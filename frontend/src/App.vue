<script setup lang="ts">
import { ref, computed } from 'vue';
import { INIT_NODES, INIT_EDGES } from './constants';
import Sidebar from './components/Sidebar.vue';
import GraphCanvas from './components/GraphCanvas.vue';
import NodeInfo from './components/NodeInfo.vue';
import ChatPanel from './components/ChatPanel.vue';
import SettingsView from './components/SettingsView.vue';

const sel = ref<string | null>(null);
const sbExp = ref(true);
const showSchema = ref(false);
const chatW = ref(360);
const divDrag = ref<any>(null);
const graphRef = ref<any>(null);

const view = ref<'list' | 'graph' | 'settings'>('list');
const currentModelTitle = ref('供应链本体图');

const models = ref<any[]>([
  { id: '1', title: '供应链本体模型', desc: '包含供应链核心实体与关系的推演模型', updated: '10分钟前', graphData: { nodes: JSON.parse(JSON.stringify(INIT_NODES)), edges: JSON.parse(JSON.stringify(INIT_EDGES)) } },
  { id: '2', title: '财务追踪模型', desc: '用于企业财务审批及资金流向追踪', updated: '2小时前', graphData: { nodes: [], edges: [] } },
  { id: '3', title: '组织架构解析', desc: '部门架构与人员编制分析本体', updated: '昨天', graphData: { nodes: [], edges: [] } }
]);

const nodes = ref<any[]>(models.value[0].graphData.nodes);
const edges = ref<any[]>(models.value[0].graphData.edges);

const openModel = (m: any) => {
  currentModelTitle.value = m.title;
  nodes.value = m.graphData.nodes;
  edges.value = m.graphData.edges;
  sel.value = null; // reset selection
  view.value = 'graph';
};

const createNewModel = () => {
  const newModel = {
    id: Date.now().toString(),
    title: `新建推演模型 ${models.value.length + 1}`,
    desc: '新创建的空白物理模型画布',
    updated: '刚刚',
    graphData: { nodes: [], edges: [] }
  };
  models.value.unshift(newModel);
  openModel(newModel);
};

const selNode = computed(() => nodes.value.find(n => n.id === sel.value) || null);

const onMove = (id: string, x: number, y: number) => {
  const n = nodes.value.find(n => n.id === id);
  if (n) {
    n.x = x;
    n.y = y;
  }
};

const onUpdate = (addNodes: any[], addEdges: any[]) => {
  nodes.value.push(...addNodes.map(n => ({...n, isNew: true})));
  edges.value.push(...addEdges);
  setTimeout(() => {
    nodes.value.forEach(n => n.isNew = false);
  }, 800);
};

const clearCanvas = () => {
  nodes.value = [];
  edges.value = [];
  sel.value = null;
};

const autoLayout = () => {
  if (nodes.value.length === 0) return;

  // Use a topological sort / longest-path level assignment for left-to-right hierarchy layout
  const levels: Record<string, number> = {};
  nodes.value.forEach(n => { levels[n.id] = 0; });

  // Bellman-Ford-like relaxation to compute max depth from roots
  let changed = true;
  let iterations = 0;
  const maxIterations = nodes.value.length + 1; // Prevent infinite loops in cyclic graphs

  while (changed && iterations < maxIterations) {
    changed = false;
    iterations++;
    edges.value.forEach(e => {
      const fromLevel = levels[e.from] ?? 0;
      const toLevel = levels[e.to] ?? 0;
      if (fromLevel + 1 > toLevel) {
        levels[e.to] = fromLevel + 1;
        changed = true;
      }
    });
  }

  // Group nodes by level
  const groups: Record<number, any[]> = {};
  let maxLevel = 0;
  nodes.value.forEach(n => {
    const lvl = levels[n.id];
    if (lvl > maxLevel) maxLevel = lvl;
    if (!groups[lvl]) groups[lvl] = [];
    groups[lvl].push(n);
  });

  // Calculate coordinates: left-to-right cascades
  const xSpacing = 320; // Fixed sensible spacing to avoid overly long lines
  const ySpacing = 160;

  // Calculate total bounding box width and height to center it
  const totalGraphWidth = maxLevel * xSpacing;
  const maxLvlCount = Math.max(...Object.values(groups).map(g => g.length));

  for (let lvl = 0; lvl <= maxLevel; lvl++) {
    const group = groups[lvl];
    if (!group) continue;

    // Sort nodes to minimize edge crossings by putting children closer to their parents' average Y
    if (lvl > 0) {
      group.sort((a, b) => {
        const getAvgY = (nodeId: string) => {
           const incoming = edges.value.filter(e => e.to === nodeId);
           if (incoming.length === 0) return 0;
           let sum = 0; let c = 0;
           incoming.forEach(edge => {
             const parent = nodes.value.find(n => n.id === edge.from);
             if (parent && parent.y !== undefined) { sum += parent.y; c++; }
           });
           return c > 0 ? sum / c : 0;
        };
        return getAvgY(a.id) - getAvgY(b.id);
      });
    }

    // Keep it vertically balanced around Y=1000
    const startY = 1000 - ((group.length - 1) * ySpacing) / 2;

    group.forEach((n, idx) => {
      n.x = 400 + lvl * xSpacing;
      n.y = startY + idx * ySpacing;
    });
  }

  if (graphRef.value) {
    setTimeout(() => {
      // @ts-ignore
      graphRef.value.fitView();
    }, 50);
  }
};

const startDivider = (e: MouseEvent) => {
  e.preventDefault();
  divDrag.value = { sx: e.clientX, sw: chatW.value };
  const mv = (ev: MouseEvent) => {
    const delta = divDrag.value.sx - ev.clientX;
    chatW.value = Math.max(0, Math.min(window.innerWidth - 72, divDrag.value.sw + delta));
    if (graphRef.value) graphRef.value.fitView();
  };
  const up = () => {
    divDrag.value = null;
    document.removeEventListener('mousemove', mv);
    document.removeEventListener('mouseup', up);
  };
  document.addEventListener('mousemove', mv);
  document.addEventListener('mouseup', up);
};
</script>

<template>
  <div class="app">
    <Sidebar :expanded="sbExp" @toggle="sbExp = !sbExp" @nav="r => { if(r==='list') view='list'; else if(r==='settings') view='settings'; }" />
    <div class="main">
      <div class="topbar">
        <div class="breadcrumb">
          <span class="bc-dim">推演</span><span class="bc-sep">/</span>
          <span class="bc-muted">模型空间</span><span class="bc-sep">/</span>

          <template v-if="view === 'list'">
            <span class="bc-cur">物理模型</span>
          </template>

          <template v-else-if="view === 'settings'">
            <span class="bc-cur">平台设置</span>
          </template>

          <template v-else-if="view === 'graph'">
            <span class="bc-muted" @click="view = 'list'" style="cursor:pointer; transition: opacity 0.2s" onmouseover="this.style.opacity=1" onmouseout="this.style.opacity=0.8" title="返回物理模型列表">物理模型</span>
            <span class="bc-sep">/</span>
            <span class="bc-cur" style="color: #42b883">{{ currentModelTitle }}</span>
            <span class="bc-star">☆</span>
          </template>
        </div>
        <div class="tb-tools" v-if="view === 'graph'">
          <span class="tb-badge ok">● {{ nodes.length }} 节点</span>
          <span class="tb-badge">{{ edges.length }} 关系</span>
          <button class="tb-btn" @click="showSchema = !showSchema">Schema</button>
          <button class="tb-btn">导出</button>
          <button class="tb-btn hi">共享</button>
        </div>
        <div class="tb-tools" v-if="view === 'list'">
          <button class="tb-btn hi" @click="createNewModel">＋新建模型</button>
        </div>
      </div>

      <!-- List View -->
      <div class="model-list-view" v-if="view === 'list'">
        <div class="ml-header">
          <h2>物理模型管理</h2>
          <p>选择一个已有模型进行编辑拓展，或创建新的推演画布。</p>
        </div>
        <div class="ml-grid">
          <div class="ml-card" v-for="m in models" :key="m.id" @click="openModel(m)">
            <div class="ml-card-head">
              <h3>{{ m.title }}</h3>
              <span class="status-dot"></span>
            </div>
            <p class="ml-card-desc">{{ m.desc }}</p>
            <div class="ml-card-foot">
              <span class="ml-stat">节点：{{ m.nodes }}</span>
              <span class="ml-stat">关系：{{ m.edges }}</span>
              <span class="ml-time">{{ m.updated }}修改</span>
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
          />
          <NodeInfo :node="selNode" :nodes="nodes" :edges="edges" :isOpen="showSchema" @close="() => { sel = null; showSchema = false; }" />
        </div>
        <div :class="['resize-divider', { dragging: divDrag }]" @mousedown="startDivider" />
        <ChatPanel :nodes="nodes" :edges="edges" :width="chatW" @update="onUpdate" />
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
</style>
