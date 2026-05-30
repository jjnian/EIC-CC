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
  modelId:         { type: String, default: '' },
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
  (e: 'view-graph', modelId: string): void;
}>();
</script>

<template>
  <div class="chat-center">
    <div class="cc-inner">
      <ChatPanel
        :ref="(el) => emit('chat-ref', el)"
        :nodes="nodes"
        :edges="edges"
        :width="0"
        :seed="pendingChatSeed"
        :live-prediction="livePrediction"
        :model-id="modelId"
        class="cc-chat"
        @update="(addNodes, addEdges) => emit('update', addNodes, addEdges)"
        @clear-graph="emit('clear-graph')"
        @seed-consumed="emit('seed-consumed')"
        @abort-prediction="emit('abort-prediction')"
        @view-graph="(id) => emit('view-graph', id)"
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
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0 24px 0;
}
.cc-chat {
  flex: 1;
  min-height: 0;
  width: 100% !important;
}
.cc-chat.chat-panel {
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  border: none !important;
  box-shadow: none !important;
}
.cc-chat :deep(.ch-head) {
  display: none;
}
/* 去掉对话面板左侧那条渐变竖线(.chat-panel::before 与 cc-chat 同元素) */
.cc-chat::before {
  display: none !important;
}
.cc-chat :deep(.ch-msgs),
.cc-chat :deep(.ch-input-area),
.cc-chat :deep(.att-row) {
  width: min(100%, 1280px);
  margin-left: auto;
  margin-right: auto;
}
.cc-chat :deep(.ch-msgs) {
  padding-left: 32px;
  padding-right: 32px;
}
.cc-chat :deep(.ch-input-area) {
  padding-left: 32px;
  padding-right: 32px;
}
.cc-chat :deep(.msg) {
  max-width: 920px;
}
.cc-chat :deep(.msg-body) {
  max-width: 100%;
}
.cc-chat :deep(.ch-msgs) {
  align-items: center;
}
.cc-chat :deep(.msg-asst .bubble) {
  background: transparent !important;
  border: none;
  box-shadow: none;
  color: #f5f7fb;
  padding: 4px 0;
}
.cc-chat :deep(.msg-asst .bubble strong),
.cc-chat :deep(.msg-asst .bubble em) {
  color: #fff;
}
@media (max-width: 1200px) {
  .cc-chat :deep(.ch-msgs),
  .cc-chat :deep(.ch-input-area),
  .cc-chat :deep(.att-row) {
    width: min(100%, 960px);
  }
  .cc-chat :deep(.msg) {
    max-width: 820px;
  }
}
</style>
