<script setup lang="ts">
import { computed, ref, watch, type PropType } from 'vue';
import GraphCanvas from '../GraphCanvas.vue';
import NodeInfo from '../NodeInfo.vue';
import EdgeInfo from '../EdgeInfo.vue';
import ChatPanel from '../ChatPanel.vue';
import SchemaPanel from '../SchemaPanel.vue';
import GraphAnalysisPanel from '../GraphAnalysisPanel.vue';
import { Button } from '@/components/ui/button';
import { useDomainCollapse } from '../../composables/useDomainCollapse';
import type { OntologyNode, OntologyEdge, GraphMutation } from '../../types';

const props = defineProps({
  nodes:           { type: Array as PropType<OntologyNode[]>, required: true },
  edges:           { type: Array as PropType<OntologyEdge[]>, required: true },
  selectedId:      { type: String as PropType<string | null>, default: null },
  modelId:         { type: String, default: '' },
  chatW:           { type: Number, required: true },
  divDragActive:   { type: Boolean, default: false },
  pendingChatSeed: { type: Object as PropType<{ text: string; files: File[] } | null>, default: null },
  layoutDirection: { type: String as PropType<'LR' | 'TB'>, default: 'LR' },
  diffHighlight:   { type: Object as PropType<{ sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null>, default: null },
  canUndo:         { type: Boolean, default: false },
  canRedo:         { type: Boolean, default: false },
  schemaOpen:      { type: Boolean, default: false },
});

const emit = defineEmits<{
  (e: 'update:selectedId', id: string | null): void;
  (e: 'move', id: string, x: number, y: number): void;
  (e: 'drag-start', id: string): void;
  (e: 'auto-layout'): void;
  (e: 'toggle-layout-direction'): void;
  (e: 'clear'): void;
  (e: 'focus-node', id: string): void;
  (e: 'update'/* 图谱增删改 */, mutation: GraphMutation): void;
  (e: 'seed-consumed'): void;
  (e: 'start-divider', ev: MouseEvent): void;
  (e: 'graph-ref', el: any): void;
  (e: 'chat-ref', el: any): void;
  (e: 'edit-node', id: string): void;
  (e: 'delete-node', id: string): void;
  (e: 'delete-nodes', ids: string[]): void;
  (e: 'update-node-props', id: string, props: { key: string; value: any; source?: string }[]): void;
  (e: 'delete-edge', edgeId: string): void;
  (e: 'clear-diff'): void;
  (e: 'highlight-diff', data: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null): void;
  (e: 'undo'): void;
  (e: 'redo'): void;
  (e: 'close-schema'): void;
  (e: 'update-node-schema', id: string, patch: any): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
  (e: 'add-node', payload: { mode: 'object'; label: string; x: number; y: number; inputs: { nodeId: string; edgeLabel: string }[]; outputs: { nodeId: string; edgeLabel: string }[] }): void;
  (e: 'add-edges', payload: { label: string; inputs: string[]; outputs: string[] }): void;
  (e: 'edit-edge-relation', edgeId: string): void;
  (e: 'delete-relation', edgeId: string): void;
}>();

const selNode = computed(() => props.nodes.find(n => n.id === props.selectedId) || null);

// ── 领域聚合折叠：折叠域 → 超级节点，仅作用于渲染层，编辑/分析仍走真实图 ──
const collapse = useDomainCollapse(() => props.nodes as OntologyNode[], () => props.edges as OntologyEdge[]);
const domainPanelOpen = ref(false);

// 当前在底部抽屉里展示的关系（边）id；与节点选择互斥
const selectedEdgeId = ref<string | null>(null);
const selEdge = computed(() => props.edges.find(e => e.id === selectedEdgeId.value) || null);

const onSelect = (id: string | null) => {
  // 点击领域超级节点 = 展开该域（不产生真实选择）
  if (id && collapse.isDomainNode(id)) {
    collapse.expand(collapse.domainOfNodeId(id));
    return;
  }
  selectedEdgeId.value = null;
  emit('update:selectedId', id);
};
const onSelectEdge = (id: string) => {
  if (collapse.isDomainEdge(id)) return;   // 聚合边是派生视图，无真实详情可看
  // 选边时清掉节点选择，避免两个抽屉同时弹
  if (props.selectedId) emit('update:selectedId', null);
  selectedEdgeId.value = id;
};

// 超级节点的编辑/删除/移动没有意义：编辑与删除转为展开，移动与拖拽快照直接忽略
const onEditNode = (id: string) => {
  if (collapse.isDomainNode(id)) { collapse.expand(collapse.domainOfNodeId(id)); return; }
  emit('edit-node', id);
};
const onDeleteNode = (id: string) => {
  if (collapse.isDomainNode(id)) { collapse.expand(collapse.domainOfNodeId(id)); return; }
  emit('delete-node', id);
};
const onDeleteNodes = (ids: string[]) => {
  const real = ids.filter(id => !collapse.isDomainNode(id));
  if (real.length) emit('delete-nodes', real);
};
const onMove = (id: string, x: number, y: number) => {
  if (collapse.isDomainNode(id)) return;
  emit('move', id, x, y);
};
const onDragStart = (id: string) => {
  if (collapse.isDomainNode(id)) return;
  emit('drag-start', id);
};
const onEditEdgeRelation = (id: string) => {
  if (collapse.isDomainEdge(id)) return;
  emit('edit-edge-relation', id);
};
const onCloseEdgeInfo = () => { selectedEdgeId.value = null; };
const onCloseInfo = () => {
  emit('update:selectedId', null);
};

// 边被删除或不再存在时，自动收起边抽屉
watch(() => props.edges, (list) => {
  if (selectedEdgeId.value && !list.some(e => e.id === selectedEdgeId.value)) {
    selectedEdgeId.value = null;
  }
});

// 图分析面板
const analysisOpen = ref(false);
</script>

<template>
  <div class="content">
    <div class="graph-area">
      <GraphCanvas
        :ref="(el) => emit('graph-ref', el)"
        :nodes="collapse.displayNodes.value"
        :edges="collapse.displayEdges.value"
        :selId="selectedId"
        :layoutDirection="layoutDirection"
        :diffHighlight="diffHighlight"
        :canUndo="canUndo"
        :canRedo="canRedo"
        @move="onMove"
        @undo="emit('undo')"
        @redo="emit('redo')"
        @drag-start="onDragStart"
        @select="onSelect"
        @auto-layout="emit('auto-layout')"
        @toggle-layout-direction="emit('toggle-layout-direction')"
        @clear="emit('clear')"
        @edit-node="onEditNode"
        @delete-node="onDeleteNode"
        @delete-nodes="onDeleteNodes"
        @clear-diff="emit('clear-diff')"
        @add-node="(payload) => emit('add-node', payload)"
        @add-edges="(payload) => emit('add-edges', payload)"
        @edit-edge-relation="onEditEdgeRelation"
        @select-edge="onSelectEdge"
      />
      <NodeInfo
        :node="selNode"
        :nodes="nodes"
        :edges="edges"
        :isOpen="false"
        :model-id="modelId"
        @close="onCloseInfo"
        @select-node="onSelect"
        @update-node-props="(id, props) => emit('update-node-props', id, props)"
        @delete-edge="(edgeId) => emit('delete-edge', edgeId)"
        @update-node-schema="(id, patch) => emit('update-node-schema', id, patch)"
        @edit-edge-relation="(id) => emit('edit-edge-relation', id)"
        @update-edge-schema="(id, patch) => emit('update-edge-schema', id, patch)"
      />
      <EdgeInfo
        :edge="selEdge"
        :nodes="nodes"
        :edges="edges"
        @close="onCloseEdgeInfo"
        @edit-relation="(id) => emit('edit-edge-relation', id)"
        @delete-edge="(edgeId) => emit('delete-edge', edgeId)"
        @add-edges="(payload) => emit('add-edges', payload)"
        @delete-relation="(id) => emit('delete-relation', id)"
        @update-edge-schema="(id, patch) => emit('update-edge-schema', id, patch)"
      />
      <!-- 图分析面板 -->
      <GraphAnalysisPanel
        v-if="analysisOpen"
        :nodes="nodes"
        :edges="edges"
        :selectedId="selectedId"
        :model-id="modelId"
        @close="analysisOpen = false"
        @focus-node="(id) => emit('focus-node', id)"
        @highlight-diff="(d) => emit('highlight-diff', d)"
        @update-edge-schema="(id, patch) => emit('update-edge-schema', id, patch)"
        @delete-edge="(id) => emit('delete-edge', id)"
      />
      <!-- 图分析按钮（悬浮在画布右上角） -->
      <Button
        :variant="analysisOpen ? 'default' : 'outline'"
        size="sm"
        class="analysis-btn"
        @click="analysisOpen = !analysisOpen"
        title="图谱分析：节点/关系精确统计与路径查询"
      >📊 分析</Button>
      <!-- 领域折叠按钮 + 面板：大图按域折叠成超级节点，复杂而不混乱 -->
      <Button
        v-if="collapse.hasDomains.value"
        :variant="collapse.collapsed.value.size ? 'default' : 'outline'"
        size="sm"
        class="domain-btn"
        @click="domainPanelOpen = !domainPanelOpen"
        title="领域折叠：把整个业务域折叠成一个超级节点（点击超级节点展开）"
      >🗂 领域{{ collapse.collapsed.value.size ? `（折叠 ${collapse.collapsed.value.size}）` : '' }}</Button>
      <div v-if="domainPanelOpen && collapse.hasDomains.value" class="domain-panel">
        <div class="domain-panel-head">
          <span class="domain-panel-title">领域折叠</span>
          <button class="domain-panel-act" @click="collapse.collapseAll()">全部折叠</button>
          <button class="domain-panel-act" @click="collapse.expandAll()">全部展开</button>
          <button class="domain-panel-close" @click="domainPanelOpen = false">×</button>
        </div>
        <div class="domain-panel-list">
          <label v-for="d in collapse.domains.value" :key="d.name" class="domain-row">
            <input type="checkbox" :checked="collapse.collapsed.value.has(d.name)" @change="collapse.toggle(d.name)" />
            <span class="domain-row-name">{{ d.name }}</span>
            <span class="domain-row-count">{{ d.count }}</span>
          </label>
        </div>
        <div class="domain-panel-hint">勾选=折叠为超级节点 · 点击画布上的 🗂 节点可展开</div>
      </div>
    </div>
    <SchemaPanel
      :open="schemaOpen"
      :nodes="nodes"
      :edges="edges"
      @close="emit('close-schema')"
      @focus-node="(id) => emit('focus-node', id)"
      @update-node="(id, patch) => emit('update-node-schema', id, patch)"
      @update-edge="(id, patch) => emit('update-edge-schema', id, patch)"
    />
    <div :class="['resize-divider', { dragging: divDragActive }]" @mousedown="emit('start-divider', $event)" />
    <ChatPanel
      :ref="(el) => emit('chat-ref', el)"
      :nodes="nodes"
      :edges="edges"
      :width="chatW"
      :seed="pendingChatSeed"
      @update="(mutation) => emit('update', mutation)"
      @clear-graph="emit('clear')"
      @seed-consumed="emit('seed-consumed')"
      @focus-node="(id) => emit('focus-node', id)"
    />
  </div>
</template>

<style scoped>
.content { flex: 1; display: flex; min-height: 0; min-width: 0; overflow: hidden; }
.graph-area { flex: 1; min-width: 0; position: relative; }
.resize-divider {
  width: 8px;
  flex-shrink: 0;
  cursor: col-resize;
  background: rgba(255, 255, 255, 0.04);
  transition: background-color 0.15s;
  position: relative;
  z-index: 5;
}
.resize-divider:hover, .resize-divider.dragging {
  background: rgba(74, 144, 226, 0.45);
}
.analysis-btn {
  position: absolute;
  top: 12px; right: 12px;
  z-index: 15;
  backdrop-filter: blur(8px);
}
.domain-btn {
  position: absolute;
  top: 12px; right: 92px;
  z-index: 15;
  backdrop-filter: blur(8px);
}
.domain-panel {
  position: absolute;
  top: 48px; right: 92px;
  z-index: 16;
  width: 240px;
  max-height: 60%;
  display: flex; flex-direction: column;
  background: rgba(15, 23, 42, 0.92);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,.1);
  border-radius: 10px;
  box-shadow: 0 8px 32px rgba(0,0,0,.4);
  color: #dde3ee;
  font-size: 12px;
}
.domain-panel-head {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid rgba(255,255,255,.08);
}
.domain-panel-title { font-weight: 600; flex: 1; }
.domain-panel-act {
  background: none; border: 1px solid rgba(255,255,255,.15); border-radius: 5px;
  color: #9aa3b2; font-size: 11px; padding: 2px 6px; cursor: pointer;
}
.domain-panel-act:hover { color: #dde3ee; border-color: rgba(255,255,255,.35); }
.domain-panel-close {
  background: none; border: none; color: #888; font-size: 15px; cursor: pointer; padding: 0 2px;
}
.domain-panel-close:hover { color: #dde3ee; }
.domain-panel-list { overflow-y: auto; padding: 6px 4px; }
.domain-row {
  display: flex; align-items: center; gap: 8px;
  padding: 5px 8px; border-radius: 6px; cursor: pointer;
}
.domain-row:hover { background: rgba(255,255,255,.05); }
.domain-row-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.domain-row-count { color: #7a8394; font-variant-numeric: tabular-nums; }
.domain-panel-hint {
  padding: 6px 10px 8px; color: #6b7280; font-size: 11px;
  border-top: 1px solid rgba(255,255,255,.06);
}
</style>
