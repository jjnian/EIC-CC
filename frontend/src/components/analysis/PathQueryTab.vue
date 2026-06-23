<script setup lang="ts">
import { ref, computed } from 'vue';
import type { OntologyNode, OntologyEdge } from '../../types';
import { Button } from '@/components/ui/button';
import BaseSelect from '../form/BaseSelect.vue';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
}>();

// ── 辅助 ────────────────────────────────────────────
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));
const nodeOptions = computed(() => props.nodes.map(n => ({ value: n.id, label: n.label })));
const depthOptions = [2, 3, 4, 5, 6, 7, 8].map(d => ({ value: String(d), label: String(d) }));

// ── 路径查询 ──────────────────────────────────────────
const pathFrom = ref('');
const pathTo   = ref('');
const pathResults = ref<{ nodes: OntologyNode[]; edges: OntologyEdge[] }[]>([]);
const pathLoading = ref(false);
const pathError   = ref('');
const pathMaxLen  = ref(5);

// BFS 找所有简单路径（限深度）
const findPaths = () => {
  pathResults.value = [];
  pathError.value   = '';
  const src = pathFrom.value.trim();
  const dst = pathTo.value.trim();
  if (!src || !dst) { pathError.value = '请选择起点和终点'; return; }
  if (src === dst)  { pathError.value = '起点与终点不能相同'; return; }

  pathLoading.value = true;
  // 构建邻接表（有向）
  const adj = new Map<string, { to: string; edge: OntologyEdge }[]>();
  for (const n of props.nodes) adj.set(n.id, []);
  for (const e of props.edges) {
    adj.get(e.from)?.push({ to: e.to, edge: e });
  }

  const results: { nodes: OntologyNode[]; edges: OntologyEdge[] }[] = [];
  const maxPaths = 20;

  // 迭代 DFS（避免递归栈溢出）
  type Frame = { nodeId: string; visited: Set<string>; pathNodes: OntologyNode[]; pathEdges: OntologyEdge[] };
  const stack: Frame[] = [{
    nodeId: src,
    visited: new Set([src]),
    pathNodes: [nmap.value[src]].filter(Boolean) as OntologyNode[],
    pathEdges: [],
  }];

  while (stack.length && results.length < maxPaths) {
    const { nodeId, visited, pathNodes, pathEdges } = stack.pop()!;
    if (pathEdges.length >= pathMaxLen.value) continue;
    for (const { to, edge } of (adj.get(nodeId) || [])) {
      if (visited.has(to)) continue;
      const newNodes = [...pathNodes, nmap.value[to]].filter(Boolean) as OntologyNode[];
      const newEdges = [...pathEdges, edge];
      if (to === dst) {
        results.push({ nodes: newNodes, edges: newEdges });
        if (results.length >= maxPaths) break;
      } else {
        const newVisited = new Set(visited);
        newVisited.add(to);
        stack.push({ nodeId: to, visited: newVisited, pathNodes: newNodes, pathEdges: newEdges });
      }
    }
  }

  pathResults.value = results;
  if (!results.length) pathError.value = '未找到路径（尝试增大最大深度）';
  pathLoading.value = false;
};

// 路径展开/折叠
const expandedPaths = ref(new Set<number>());
const togglePath = (i: number) => {
  const s = new Set(expandedPaths.value);
  s.has(i) ? s.delete(i) : s.add(i);
  expandedPaths.value = s;
};
</script>

<template>
  <div class="gap-section">
    <div class="gap-section-title">路径查询</div>
    <div class="gap-path-form">
      <div class="gap-path-row">
        <span class="gap-path-lbl">起点</span>
        <div class="gap-ctl"><BaseSelect v-model="pathFrom" :options="nodeOptions" placeholder="— 选择起点节点 —" /></div>
      </div>
      <div class="gap-path-row">
        <span class="gap-path-lbl">终点</span>
        <div class="gap-ctl"><BaseSelect v-model="pathTo" :options="nodeOptions" placeholder="— 选择终点节点 —" /></div>
      </div>
      <div class="gap-path-row">
        <span class="gap-path-lbl">最大深度</span>
        <div class="gap-ctl-fixed">
          <BaseSelect :model-value="String(pathMaxLen)" :options="depthOptions" @update:model-value="pathMaxLen = Number($event)" />
        </div>
        <Button class="gap-btn-primary" @click="findPaths" :disabled="pathLoading">
          {{ pathLoading ? '查询中…' : '查询' }}
        </Button>
      </div>
    </div>
    <div v-if="pathError" class="gap-error">{{ pathError }}</div>
  </div>

  <div v-if="pathResults.length" class="gap-section">
    <div class="gap-section-title">找到 {{ pathResults.length }} 条路径</div>
    <div v-for="(path, i) in pathResults" :key="i" class="gap-path-item">
      <div class="gap-path-head" @click="togglePath(i)">
        <span class="gap-path-len">{{ path.edges.length }} 跳</span>
        <span class="gap-path-summary">
          {{ path.nodes.map(n => n.label).join(' → ') }}
        </span>
        <span class="gap-path-caret">{{ expandedPaths.has(i) ? '▾' : '▸' }}</span>
      </div>
      <div v-if="expandedPaths.has(i)" class="gap-path-detail">
        <div v-for="(e, j) in path.edges" :key="e.id" class="gap-path-step">
          <span class="gap-path-node" @click="emit('focus-node', path.nodes[j].id)">{{ path.nodes[j].label }}</span>
          <span class="gap-path-arrow">—<span class="gap-path-rel">{{ e.label || '关系' }}</span>→</span>
          <span class="gap-path-node" @click="emit('focus-node', path.nodes[j + 1].id)">{{ path.nodes[j + 1].label }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gap-section { display: flex; flex-direction: column; gap: 8px; }
.gap-section-title { font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: .04em; font-weight: 600; }

.gap-error { color: tomato; font-size: 12px; padding: 4px 0; }

.gap-path-form { display: flex; flex-direction: column; gap: 8px; }
.gap-path-row { display: flex; align-items: center; gap: 8px; }
.gap-path-lbl { min-width: 56px; font-size: 12px; color: #888; }
.gap-ctl { flex: 1; min-width: 0; }
.gap-ctl-fixed { width: 80px; flex-shrink: 0; }
.gap-btn-primary { flex-shrink: 0; }

.gap-path-item {
  background: rgba(255,255,255,.03); border: 1px solid rgba(255,255,255,.07);
  border-radius: 6px; overflow: hidden;
}
.gap-path-head {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 10px; cursor: pointer;
}
.gap-path-head:hover { background: rgba(255,255,255,.04); }
.gap-path-len {
  font-size: 11px; color: #4a8df0;
  background: rgba(74,141,240,.12); border-radius: 4px; padding: 1px 6px;
  flex-shrink: 0;
}
.gap-path-summary {
  flex: 1; font-size: 12px; color: #ccc;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.gap-path-caret { color: #888; font-size: 11px; flex-shrink: 0; }

.gap-path-detail {
  padding: 8px 10px;
  border-top: 1px solid rgba(255,255,255,.06);
  display: flex; flex-direction: column; gap: 6px;
}
.gap-path-step { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.gap-path-node {
  font-size: 12px; color: #e8eaed; cursor: pointer;
  padding: 2px 8px; background: rgba(255,255,255,.06);
  border-radius: 4px;
}
.gap-path-node:hover { background: rgba(255,255,255,.12); }
.gap-path-arrow { font-size: 12px; color: #fbbf24; }
.gap-path-rel { font-size: 11px; padding: 0 4px; }
</style>
