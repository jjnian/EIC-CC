<script setup lang="ts">
import { ref, watch } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import GraphStatsTab from './analysis/GraphStatsTab.vue';
import NodeAnalysisTab from './analysis/NodeAnalysisTab.vue';
import PathQueryTab from './analysis/PathQueryTab.vue';
import LineageHealthTab from './analysis/LineageHealthTab.vue';
import CoverageAuditTab from './analysis/CoverageAuditTab.vue';
import ReviewQueueTab from './analysis/ReviewQueueTab.vue';
import { Button } from '@/components/ui/button';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selectedId: string | null;
  modelId?: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'focus-node', id: string): void;
  (e: 'highlight-diff', data: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
  (e: 'delete-edge', edgeId: string): void;
}>();

// ── 标签页 ──────────────────────────────────────────
// 0=全图统计  1=节点分析  2=路径查询  3=血缘体检  4=覆盖度  5=审核
const tab = ref(0);

watch(() => props.selectedId, (id) => {
  if (id) tab.value = 1;
});
</script>

<template>
  <div class="gap-panel">
    <div class="gap-head">
      <div class="gap-tabs">
        <button :class="['gap-tab', { on: tab === 0 }]" @click="tab = 0">全图统计</button>
        <button :class="['gap-tab', { on: tab === 1 }]" @click="tab = 1">节点分析</button>
        <button :class="['gap-tab', { on: tab === 2 }]" @click="tab = 2">路径查询</button>
        <button :class="['gap-tab', { on: tab === 3 }]" @click="tab = 3">体检</button>
        <button :class="['gap-tab', { on: tab === 4 }]" @click="tab = 4">覆盖度</button>
        <button :class="['gap-tab', { on: tab === 5 }]" @click="tab = 5">审核</button>
      </div>
      <Button variant="ghost" size="icon-sm" @click="emit('close')">×</Button>
    </div>

    <div class="gap-body">
      <GraphStatsTab v-if="tab === 0" :nodes="nodes" :edges="edges" @focus-node="(id) => emit('focus-node', id)" />
      <NodeAnalysisTab v-else-if="tab === 1" :nodes="nodes" :edges="edges" :selected-id="selectedId" @focus-node="(id) => emit('focus-node', id)" @highlight-diff="(d) => emit('highlight-diff', d)" />
      <PathQueryTab v-else-if="tab === 2" :nodes="nodes" :edges="edges" @focus-node="(id) => emit('focus-node', id)" />
      <LineageHealthTab v-else-if="tab === 3" :nodes="nodes" :edges="edges" :model-id="modelId"
                        @focus-node="(id) => emit('focus-node', id)"
                        @update-edge-schema="(id, patch) => emit('update-edge-schema', id, patch)" />
      <CoverageAuditTab v-else-if="tab === 4" :nodes="nodes" :edges="edges"
                        @focus-node="(id) => emit('focus-node', id)" />
      <ReviewQueueTab v-else-if="tab === 5" :nodes="nodes" :edges="edges"
                      @focus-node="(id) => emit('focus-node', id)"
                      @update-edge-schema="(id, patch) => emit('update-edge-schema', id, patch)"
                      @delete-edge="(id) => emit('delete-edge', id)" />
    </div>
  </div>
</template>

<style scoped>
.gap-panel {
  position: absolute;
  top: 0; right: 0;
  width: 420px;
  height: 100%;
  background: #161820;
  border-left: 1px solid rgba(255,255,255,.08);
  display: flex;
  flex-direction: column;
  z-index: 20;
  color: #e8eaed;
  font-size: 13px;
}
.gap-head {
  display: flex;
  align-items: center;
  padding: 0 12px;
  border-bottom: 1px solid rgba(255,255,255,.07);
  flex-shrink: 0;
  gap: 4px;
}
.gap-tabs { display: flex; gap: 2px; flex: 1; }
.gap-tab {
  background: none; border: none;
  padding: 10px 12px;
  color: #888; cursor: pointer; font-size: 13px;
  border-bottom: 2px solid transparent;
  font-family: inherit;
}
.gap-tab.on { color: #e8eaed; border-bottom-color: #4a8df0; }
.gap-close {
  background: none; border: none; color: #888;
  font-size: 18px; cursor: pointer; padding: 4px 6px; line-height: 1;
}
.gap-close:hover { color: #e8eaed; }

.gap-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
