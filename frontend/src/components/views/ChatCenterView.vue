<script setup lang="ts">
import { computed, type PropType } from 'vue';
import ChatPanel from '../ChatPanel.vue';
import type { OntologyNode, OntologyEdge, ChainStep } from '../../types';

const props = defineProps({
  nodes:           { type: Array as PropType<OntologyNode[]>, required: true },
  edges:           { type: Array as PropType<OntologyEdge[]>, required: true },
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
  pendingChatSeed: { type: Object as PropType<{ text: string; files: File[] } | null>, default: null },
  modelTitle:      { type: String, default: '' },
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
  (e: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[]): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
  (e: 'abort-prediction'): void;
  (e: 'chat-ref', el: any): void;
}>();
</script>

<template>
  <div class="chat-center">
    <div class="cc-inner">
      <div class="cc-head">
        <span class="cc-title">{{ modelTitle || '推演助手' }}</span>
        <span class="cc-stat">{{ nodes.length }} 节点 · {{ edges.length }} 关系</span>
      </div>
      <ChatPanel
        :ref="(el) => emit('chat-ref', el)"
        :nodes="nodes"
        :edges="edges"
        :width="0"
        :seed="pendingChatSeed"
        :live-prediction="livePrediction"
        class="cc-chat"
        @update="(addNodes, addEdges) => emit('update', addNodes, addEdges)"
        @clear-graph="emit('clear-graph')"
        @seed-consumed="emit('seed-consumed')"
        @abort-prediction="emit('abort-prediction')"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-center {
  flex: 1;
  min-height: 0;
  display: flex;
  justify-content: center;
  background: transparent;
  overflow: hidden;
}
.cc-inner {
  width: 100%;
  max-width: 880px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0 24px 0;
}
.cc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 4px 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.cc-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}
.cc-stat {
  font-size: 12px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.cc-chat {
  flex: 1;
  min-height: 0;
  width: 100% !important;
  background: transparent !important;
  border-left: none !important;
}
.cc-chat :deep(.ch-head) {
  display: none;
}
</style>
