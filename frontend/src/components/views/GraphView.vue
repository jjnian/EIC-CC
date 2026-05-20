<script setup lang="ts">
import { computed, type PropType } from 'vue';
import GraphCanvas from '../GraphCanvas.vue';
import NodeInfo from '../NodeInfo.vue';
import ChatPanel from '../ChatPanel.vue';
import ScenarioTimeline from '../ScenarioTimeline.vue';
import type { OntologyNode, OntologyEdge, ChainStep } from '../../types';

const props = defineProps({
  nodes:           { type: Array as PropType<OntologyNode[]>, required: true },
  edges:           { type: Array as PropType<OntologyEdge[]>, required: true },
  selectedId:      { type: String as PropType<string | null>, default: null },
  showSchema:      { type: Boolean, default: false },
  activeBranchId:  { type: String, required: true },
  liveActive:      { type: Boolean, required: true },
  liveLoading:     { type: Boolean, required: true },
  liveSteps:       { type: Array as PropType<ChainStep[]>, required: true },
  liveIntent:      { type: String as PropType<'forward' | 'backward'>, required: true },
  chatW:           { type: Number, required: true },
  divDragActive:   { type: Boolean, default: false },
  pendingChatSeed: { type: Object as PropType<{ text: string; files: File[] } | null>, default: null },
});

const emit = defineEmits<{
  (e: 'update:selectedId', id: string | null): void;
  (e: 'update:showSchema', value: boolean): void;
  (e: 'move', id: string, x: number, y: number): void;
  (e: 'auto-layout'): void;
  (e: 'clear'): void;
  (e: 'predict-from', id: string): void;
  (e: 'switch-branch', id: string): void;
  (e: 'close-timeline'): void;
  (e: 'focus-node', id: string): void;
  (e: 'update'/* 图谱增量 */, addNodes: OntologyNode[], addEdges: OntologyEdge[]): void;
  (e: 'seed-consumed'): void;
  (e: 'start-divider', ev: MouseEvent): void;
  (e: 'graph-ref', el: any): void;
}>();

const selNode = computed(() => props.nodes.find(n => n.id === props.selectedId) || null);

const onSelect = (id: string | null) => emit('update:selectedId', id);
const onCloseInfo = () => {
  emit('update:selectedId', null);
  emit('update:showSchema', false);
};
</script>

<template>
  <div class="content">
    <div class="graph-area">
      <GraphCanvas
        :ref="(el) => emit('graph-ref', el)"
        :nodes="nodes"
        :edges="edges"
        :selId="selectedId"
        @move="(id, x, y) => emit('move', id, x, y)"
        @select="onSelect"
        @auto-layout="emit('auto-layout')"
        @clear="emit('clear')"
        @predict-from="(id) => emit('predict-from', id)"
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
        :isOpen="showSchema"
        @close="onCloseInfo"
      />
    </div>
    <div :class="['resize-divider', { dragging: divDragActive }]" @mousedown="emit('start-divider', $event)" />
    <ScenarioTimeline
      v-if="liveActive"
      :steps="liveSteps"
      :loading="liveLoading"
      :nodes="nodes"
      :intent="liveIntent"
      :style="{ width: chatW + 'px', flexShrink: 0 }"
      @close="emit('close-timeline')"
      @focus-node="(id) => emit('focus-node', id)"
    />
    <ChatPanel
      v-else
      :nodes="nodes"
      :edges="edges"
      :width="chatW"
      :seed="pendingChatSeed"
      @update="(addNodes, addEdges) => emit('update', addNodes, addEdges)"
      @clear-graph="emit('clear')"
      @seed-consumed="emit('seed-consumed')"
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
