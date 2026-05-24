<script setup lang="ts">
import { computed, ref, watch, type PropType } from 'vue';
import GraphCanvas from '../GraphCanvas.vue';
import NodeInfo from '../NodeInfo.vue';
import ChatPanel from '../ChatPanel.vue';
import ExplanationPanel from '../ExplanationPanel.vue';
import SchemaPanel from '../SchemaPanel.vue';
import { toast } from '../../composables/useToast';
import type { OntologyNode, OntologyEdge, ChainStep } from '../../types';

const props = defineProps({
  nodes:           { type: Array as PropType<OntologyNode[]>, required: true },
  edges:           { type: Array as PropType<OntologyEdge[]>, required: true },
  selectedId:      { type: String as PropType<string | null>, default: null },
  activeBranchId:  { type: String, required: true },
  liveActive:      { type: Boolean, required: true },
  liveLoading:     { type: Boolean, required: true },
  liveSteps:       { type: Array as PropType<ChainStep[]>, required: true },
  liveIntent:      { type: String as PropType<'forward' | 'backward'>, required: true },
  livePruneDetails: { type: Array as PropType<{ nodeId: string; label: string; reason: string }[]>, default: () => [] },
  liveSeeds:       { type: Array as PropType<string[]>, default: () => [] },
  livePrompt:      { type: String, default: '' },
  liveName:        { type: String, default: '' },
  liveBranchId:    { type: String, default: '' },
  liveError:       { type: String, default: '' },
  liveStatus:      { type: Number as PropType<0 | 1 | 2 | 3 | 4>, default: 0 },
  chatW:           { type: Number, required: true },
  divDragActive:   { type: Boolean, default: false },
  pendingChatSeed: { type: Object as PropType<{ text: string; files: File[] } | null>, default: null },
  layoutDirection: { type: String as PropType<'LR' | 'TB'>, default: 'LR' },
  diffHighlight:   { type: Object as PropType<{ sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null>, default: null },
  canUndo:         { type: Boolean, default: false },
  canRedo:         { type: Boolean, default: false },
  schemaOpen:      { type: Boolean, default: false },
});

const livePrediction = computed(() => ({
  status: props.liveStatus,
  intent: props.liveIntent,
  seeds: props.liveSeeds,
  prompt: props.livePrompt,
  name: props.liveName,
  steps: props.liveSteps,
  pruneDetails: props.livePruneDetails,
  branchId: props.liveBranchId,
  error: props.liveError,
}));

const emit = defineEmits<{
  (e: 'update:selectedId', id: string | null): void;
  (e: 'move', id: string, x: number, y: number): void;
  (e: 'drag-start', id: string): void;
  (e: 'auto-layout'): void;
  (e: 'toggle-layout-direction'): void;
  (e: 'clear'): void;
  (e: 'predict-from', id: string): void;
  (e: 'switch-branch', id: string): void;
  (e: 'close-timeline'): void;
  (e: 'focus-node', id: string): void;
  (e: 'update'/* 图谱增量 */, addNodes: OntologyNode[], addEdges: OntologyEdge[]): void;
  (e: 'seed-consumed'): void;
  (e: 'start-divider', ev: MouseEvent): void;
  (e: 'graph-ref', el: any): void;
  (e: 'edit-node', id: string): void;
  (e: 'delete-node', id: string): void;
  (e: 'delete-nodes', ids: string[]): void;
  (e: 'abort-prediction'): void;
  (e: 'update-node-props', id: string, props: { key: string; value: any; source?: string }[]): void;
  (e: 'delete-edge', edgeId: string): void;
  (e: 'clear-diff'): void;
  (e: 'undo'): void;
  (e: 'redo'): void;
  (e: 'close-schema'): void;
  (e: 'update-node-schema', id: string, patch: any): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
  (e: 'add-node', type: string, x: number, y: number): void;
}>();

const selNode = computed(() => props.nodes.find(n => n.id === props.selectedId) || null);

const onSelect = (id: string | null) => emit('update:selectedId', id);
const onCloseInfo = () => {
  emit('update:selectedId', null);
};

// P1-7：浮动解释面板栈。最多同时打开 3 个，按 nodeId 唯一。
const MAX_PANELS = 3;
type ExplainPanelEntry = { scenarioId: string; nodeId: string; nodeLabel: string; nodeType?: string; confidence?: number };
const explanationPanels = ref<ExplainPanelEntry[]>([]);

const onExplainNode = (nodeId: string) => {
  const node = props.nodes.find(n => n.id === nodeId);
  if (!node) return;
  if (node.source !== 'predicted') {
    toast.warn('该节点不是推演节点，无需解释');
    return;
  }
  // 解释只对已落库的分支有效；live 阶段还没有 scenarioId
  const branchId = props.activeBranchId;
  if (!branchId || branchId === 'trunk' || branchId === 'live') {
    toast.warn('请先等待推演完成或切换到对应分支');
    return;
  }
  // 已有同 nodeId 的面板 → 关闭重开（视为刷新位置）
  const existed = explanationPanels.value.findIndex(p => p.nodeId === nodeId);
  if (existed >= 0) {
    explanationPanels.value.splice(existed, 1);
  } else if (explanationPanels.value.length >= MAX_PANELS) {
    explanationPanels.value.shift();
  }
  explanationPanels.value.push({
    scenarioId: branchId,
    nodeId,
    nodeLabel: node.label || nodeId,
    nodeType: node.type,
    confidence: node.confidence,
  });
};

const closePanel = (nodeId: string) => {
  explanationPanels.value = explanationPanels.value.filter(p => p.nodeId !== nodeId);
};

// 切换分支时关闭所有解释面板（避免 nodeId 错位）
watch(() => props.activeBranchId, () => {
  explanationPanels.value = [];
});
</script>

<template>
  <div class="content">
    <div class="graph-area">
      <GraphCanvas
        :ref="(el) => emit('graph-ref', el)"
        :nodes="nodes"
        :edges="edges"
        :selId="selectedId"
        :layoutDirection="layoutDirection"
        :diffHighlight="diffHighlight"
        :canUndo="canUndo"
        :canRedo="canRedo"
        @move="(id, x, y) => emit('move', id, x, y)"
        @undo="emit('undo')"
        @redo="emit('redo')"
        @drag-start="(id) => emit('drag-start', id)"
        @select="onSelect"
        @auto-layout="emit('auto-layout')"
        @toggle-layout-direction="emit('toggle-layout-direction')"
        @clear="emit('clear')"
        @predict-from="(id) => emit('predict-from', id)"
        @edit-node="(id) => emit('edit-node', id)"
        @delete-node="(id) => emit('delete-node', id)"
        @delete-nodes="(ids) => emit('delete-nodes', ids)"
        @explain-node="onExplainNode"
        @clear-diff="emit('clear-diff')"
        @add-node="(type, x, y) => emit('add-node', type, x, y)"
      />
      <div v-if="activeBranchId !== 'trunk' && !liveActive" class="branch-banner">
        <span class="bb-icon">⚡</span>
        <span>当前查看推演分支 · 可右键节点从此再次分叉</span>
        <button class="bb-back" @click="emit('switch-branch', 'trunk')">返回主分支</button>
      </div>
      <NodeInfo
        :node="selNode"
        :nodes="nodes"
        :edges="edges"
        :isOpen="false"
        @close="onCloseInfo"
        @update-node-props="(id, props) => emit('update-node-props', id, props)"
        @delete-edge="(edgeId) => emit('delete-edge', edgeId)"
        @update-node-schema="(id, patch) => emit('update-node-schema', id, patch)"
      />
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
      :nodes="nodes"
      :edges="edges"
      :width="chatW"
      :seed="pendingChatSeed"
      :live-prediction="livePrediction"
      @update="(addNodes, addEdges) => emit('update', addNodes, addEdges)"
      @clear-graph="emit('clear')"
      @seed-consumed="emit('seed-consumed')"
      @focus-node="(id) => emit('focus-node', id)"
      @abort-prediction="emit('abort-prediction')"
    />

    <!-- P1-7：浮动解释面板（可堆叠多个） -->
    <ExplanationPanel
      v-for="(p, idx) in explanationPanels"
      :key="p.nodeId"
      :scenario-id="p.scenarioId"
      :node-id="p.nodeId"
      :node-label="p.nodeLabel"
      :node-type="p.nodeType"
      :confidence="p.confidence"
      :stack-index="idx"
      @close="closePanel(p.nodeId)"
    />
  </div>
</template>

<style scoped>
.content { flex: 1; display: flex; min-height: 0; min-width: 0; overflow: hidden; }
.graph-area { flex: 1; min-width: 0; position: relative; }
.resize-divider {
  width: 4px;
  flex-shrink: 0;
  cursor: col-resize;
  background: rgba(255, 255, 255, 0.04);
  transition: background-color 0.15s;
}
.resize-divider:hover, .resize-divider.dragging {
  background: rgba(74, 144, 226, 0.45);
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
