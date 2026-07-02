<script setup lang="ts">
import { computed, ref, watch, type PropType } from 'vue';
import GraphCanvas from '../GraphCanvas.vue';
import NodeInfo from '../NodeInfo.vue';
import EdgeInfo from '../EdgeInfo.vue';
import ChatPanel from '../ChatPanel.vue';
import SchemaPanel from '../SchemaPanel.vue';
import GraphAnalysisPanel from '../GraphAnalysisPanel.vue';
import { Button } from '@/components/ui/button';
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

// 当前在底部抽屉里展示的关系（边）id；与节点选择互斥
const selectedEdgeId = ref<string | null>(null);
const selEdge = computed(() => props.edges.find(e => e.id === selectedEdgeId.value) || null);

const onSelect = (id: string | null) => {
  selectedEdgeId.value = null;
  emit('update:selectedId', id);
};
const onSelectEdge = (id: string) => {
  // 选边时清掉节点选择，避免两个抽屉同时弹
  if (props.selectedId) emit('update:selectedId', null);
  selectedEdgeId.value = id;
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
        @edit-node="(id) => emit('edit-node', id)"
        @delete-node="(id) => emit('delete-node', id)"
        @delete-nodes="(ids) => emit('delete-nodes', ids)"
        @clear-diff="emit('clear-diff')"
        @add-node="(payload) => emit('add-node', payload)"
        @add-edges="(payload) => emit('add-edges', payload)"
        @edit-edge-relation="(id) => emit('edit-edge-relation', id)"
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
      />
      <!-- 图分析按钮（悬浮在画布右上角） -->
      <Button
        :variant="analysisOpen ? 'default' : 'outline'"
        size="sm"
        class="analysis-btn"
        @click="analysisOpen = !analysisOpen"
        title="图谱分析：节点/关系精确统计与路径查询"
      >📊 分析</Button>
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
</style>
