<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue';
import GraphCanvas from '../GraphCanvas.vue';
import NodeInfo from '../NodeInfo.vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from '../../types';
import { getOntology } from '../../api/ontology';
import { useGraphActions } from '../../composables/useGraphActions';
import { Button } from '@/components/ui/button';

const params = new URLSearchParams(window.location.search);
const modelId = params.get('preview') || '';

const model = ref<OntologyModel | null>(null);
const nodes = ref<OntologyNode[]>([]);
const edges = ref<OntologyEdge[]>([]);
const loading = ref(true);
const error = ref('');
const sel = ref<string | null>(null);
const showSchema = ref(false);
const graphRef = ref<any>(null);

const selNode = computed(() => nodes.value.find(n => n.id === sel.value) || null);

const graphActions = useGraphActions({
  nodes, edges,
  currentModel: () => model.value || undefined,
  currentTitle: () => model.value?.title || '本体预览',
  fitView: () => graphRef.value?.fitView(),
  persist: () => { /* readonly: 不持久化 */ },
});
const exportGraph = graphActions.exportGraph;

const layoutDirection = ref<'LR' | 'TB'>('LR');

const load = async () => {
  if (!modelId) {
    error.value = '缺少 preview=<modelId> 参数';
    loading.value = false;
    return;
  }
  try {
    const m = await getOntology(modelId);
    if (!m) { error.value = '本体模型不存在'; return; }
    model.value = m;
    nodes.value = m.graphData?.nodes || [];
    edges.value = m.graphData?.edges || [];
    document.title = `${m.title || '本体预览'} · 推演平台`;
    nextTick(() => graphRef.value?.fitView?.());
  } catch (e: any) {
    error.value = '加载失败: ' + (e?.message || e);
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <div class="pv-root">
    <div class="pv-bar">
      <div class="pv-bar-l">
        <span class="pv-icon">⚡</span>
        <div class="pv-titles">
          <div class="pv-title">{{ model?.title || '本体预览' }}</div>
          <div v-if="model?.desc" class="pv-desc" :title="model.desc">{{ model.desc }}</div>
        </div>
      </div>
      <div class="pv-bar-r" v-if="model">
        <span class="pv-badge pv-badge-ok">● {{ nodes.length }} 节点</span>
        <span class="pv-badge">{{ edges.length }} 关系</span>
        <span class="pv-badge pv-badge-mute">只读预览</span>
        <Button variant="outline" size="sm" @click="graphRef?.fitView?.()" title="适应屏幕">⤢ 适应</Button>
        <Button variant="outline" size="sm" @click="showSchema = !showSchema">Schema</Button>
        <Button variant="outline" size="sm" @click="exportGraph" title="下载 JSON">⤓ 导出</Button>
      </div>
    </div>

    <div v-if="loading" class="pv-state">加载中…</div>
    <div v-else-if="error" class="pv-state pv-state-err">{{ error }}</div>
    <div v-else class="pv-stage">
      <GraphCanvas
        :ref="(el: any) => graphRef = el"
        :nodes="nodes"
        :edges="edges"
        :selId="sel"
        :layoutDirection="layoutDirection"
        :readonly="true"
        @select="(id) => sel = id"
      />
      <NodeInfo
        :node="selNode"
        :nodes="nodes"
        :edges="edges"
        :isOpen="showSchema"
        @close="() => { sel = null; showSchema = false; }"
      />
    </div>

  </div>
</template>

<style scoped>
.pv-root {
  position: fixed; inset: 0;
  display: flex; flex-direction: column;
  background: radial-gradient(ellipse at top, #0f1e36 0%, #050b18 100%);
  color: var(--text-main, #d6e2f0);
  overflow: hidden;
}
.pv-bar {
  height: 56px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px;
  background: rgba(15, 23, 42, 0.4);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(255,255,255,0.06);
  z-index: 5;
}
.pv-bar-l { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; }
.pv-icon { font-size: 20px; color: #fbbf24; }
.pv-titles { display: flex; flex-direction: column; min-width: 0; }
.pv-title { font-size: 15px; font-weight: 600; color: #fff; }
.pv-desc {
  font-size: 11.5px; color: rgba(255,255,255,0.45);
  max-width: 60vw;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pv-bar-r { display: flex; align-items: center; gap: 8px; }
.pv-badge {
  font-size: 12px; padding: 3px 10px; border-radius: 100px;
  background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.65);
  font-family: 'JetBrains Mono', monospace;
}
.pv-badge-ok { background: rgba(34,221,136,0.16); color: #22dd88; }
.pv-badge-mute { background: rgba(251,191,36,0.12); color: #fbbf24; }
.pv-btn {
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main, #d6e2f0); padding: 5px 12px; border-radius: 8px;
  font-size: 12.5px; cursor: pointer; transition: all .15s; font-family: inherit;
}
.pv-btn:hover { background: rgba(255,255,255,0.16); }

.pv-stage { flex: 1; min-height: 0; position: relative; display: flex; }
.pv-state {
  flex: 1; display: flex; align-items: center; justify-content: center;
  color: rgba(255,255,255,0.5); font-size: 14px;
}
.pv-state-err { color: #ff8a8a; }

.pv-foot {
  position: absolute; bottom: 12px; left: 16px;
  font-size: 11px; color: rgba(255,255,255,0.4);
  font-family: 'JetBrains Mono', monospace;
}
</style>
