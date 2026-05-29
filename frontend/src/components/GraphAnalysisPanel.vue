<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { NT } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selectedId: string | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'focus-node', id: string): void;
}>();

// ── 标签页 ──────────────────────────────────────────
// 0=全图统计  1=节点分析  2=路径查询
const tab = ref(0);

watch(() => props.selectedId, (id) => {
  if (id) tab.value = 1;
});

// ── 辅助 ────────────────────────────────────────────
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

const typeColor = (type: string) => (NT as any)[type]?.color || '#42b883';
const typeLabel = (type: string) => (NT as any)[type]?.label || type;

const sourceBadge = (s?: string) => {
  if (s === 'inferred')  return { text: 'AI推理',   color: '#bb77ff' };
  if (s === 'derived')   return { text: '文本提取', color: '#22dd88' };
  if (s === 'manual')    return { text: '手动',     color: '#3d9bff' };
  if (s === 'predicted') return { text: '推演',     color: '#fbbf24' };
  return { text: '预置', color: '#888' };
};

// ── Tab 0: 全图统计 ──────────────────────────────────

// 节点按类型分组
const nodeTypeStats = computed(() => {
  const m = new Map<string, number>();
  for (const n of props.nodes) {
    m.set(n.type, (m.get(n.type) || 0) + 1);
  }
  return [...m.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([type, count]) => ({ type, count, color: typeColor(type), label: typeLabel(type) }));
});

// 边按标签分组
const edgeLabelStats = computed(() => {
  const m = new Map<string, number>();
  for (const e of props.edges) {
    const k = e.label || '(未命名)';
    m.set(k, (m.get(k) || 0) + 1);
  }
  return [...m.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .map(([label, count]) => ({ label, count }));
});

// 边按来源分组
const edgeSourceStats = computed(() => {
  const m = new Map<string, number>();
  for (const e of props.edges) {
    const k = e.source || 'preset';
    m.set(k, (m.get(k) || 0) + 1);
  }
  return [...m.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([src, count]) => ({ src, count, ...sourceBadge(src) }));
});

// 度数排行（入度+出度）
const degreeRank = computed(() => {
  const deg = new Map<string, { in: number; out: number }>();
  for (const n of props.nodes) deg.set(n.id, { in: 0, out: 0 });
  for (const e of props.edges) {
    if (deg.has(e.from)) deg.get(e.from)!.out++;
    if (deg.has(e.to))   deg.get(e.to)!.in++;
  }
  return props.nodes
    .map(n => ({ n, ...deg.get(n.id)! }))
    .sort((a, b) => (b.in + b.out) - (a.in + a.out))
    .slice(0, 10);
});

// 孤立节点（入度=0 且 出度=0）
const isolatedNodes = computed(() => {
  const connected = new Set<string>();
  for (const e of props.edges) {
    connected.add(e.from);
    connected.add(e.to);
  }
  return props.nodes.filter(n => !connected.has(n.id));
});

// ── Tab 1: 节点精确分析 ──────────────────────────────

const selNode = computed(() => props.selectedId ? nmap.value[props.selectedId] || null : null);
const outgoing = computed(() => selNode.value ? props.edges.filter(e => e.from === selNode.value!.id) : []);
const incoming = computed(() => selNode.value ? props.edges.filter(e => e.to === selNode.value!.id) : []);

// 二跳邻居（不含自身和直接邻居）
const twoHopNeighbors = computed(() => {
  if (!selNode.value) return [];
  const direct = new Set<string>();
  direct.add(selNode.value.id);
  for (const e of [...outgoing.value, ...incoming.value]) {
    direct.add(e.from);
    direct.add(e.to);
  }
  const twoHop = new Set<string>();
  for (const e of props.edges) {
    if (direct.has(e.from) && !direct.has(e.to)) twoHop.add(e.to);
    if (direct.has(e.to)   && !direct.has(e.from)) twoHop.add(e.from);
  }
  return [...twoHop].map(id => nmap.value[id]).filter(Boolean) as OntologyNode[];
});

// ── Tab 2: 路径查询 ──────────────────────────────────

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
  <div class="gap-panel">
    <div class="gap-head">
      <div class="gap-tabs">
        <button :class="['gap-tab', { on: tab === 0 }]" @click="tab = 0">全图统计</button>
        <button :class="['gap-tab', { on: tab === 1 }]" @click="tab = 1">节点分析</button>
        <button :class="['gap-tab', { on: tab === 2 }]" @click="tab = 2">路径查询</button>
      </div>
      <button class="gap-close" @click="emit('close')">×</button>
    </div>

    <div class="gap-body">

      <!-- ── Tab 0: 全图统计 ── -->
      <template v-if="tab === 0">
        <!-- 基础数字 -->
        <div class="gap-row-stats">
          <div class="gap-stat-card">
            <div class="gap-stat-n">{{ nodes.length }}</div>
            <div class="gap-stat-l">节点总数</div>
          </div>
          <div class="gap-stat-card">
            <div class="gap-stat-n">{{ edges.length }}</div>
            <div class="gap-stat-l">关系总数</div>
          </div>
          <div class="gap-stat-card">
            <div class="gap-stat-n">{{ isolatedNodes.length }}</div>
            <div class="gap-stat-l">孤立节点</div>
          </div>
          <div class="gap-stat-card">
            <div class="gap-stat-n">{{ new Set(edges.map(e => e.label || '')).size }}</div>
            <div class="gap-stat-l">关系类型数</div>
          </div>
        </div>

        <!-- 节点类型分布 -->
        <div class="gap-section">
          <div class="gap-section-title">节点类型分布</div>
          <div v-for="s in nodeTypeStats" :key="s.type" class="gap-bar-row">
            <span class="gap-bar-dot" :style="{ background: s.color }" />
            <span class="gap-bar-label">{{ s.label }}</span>
            <div class="gap-bar-track">
              <div class="gap-bar-fill" :style="{ width: (s.count / nodes.length * 100) + '%', background: s.color + '88' }" />
            </div>
            <span class="gap-bar-count">{{ s.count }}</span>
          </div>
        </div>

        <!-- 关系来源分布 -->
        <div class="gap-section">
          <div class="gap-section-title">关系来源分布</div>
          <div v-for="s in edgeSourceStats" :key="s.src" class="gap-bar-row">
            <span class="gap-bar-dot" :style="{ background: s.color }" />
            <span class="gap-bar-label">{{ s.text }}</span>
            <div class="gap-bar-track">
              <div class="gap-bar-fill" :style="{ width: (s.count / edges.length * 100) + '%', background: s.color + '88' }" />
            </div>
            <span class="gap-bar-count">{{ s.count }}</span>
          </div>
        </div>

        <!-- 关系标签 Top 20 -->
        <div class="gap-section">
          <div class="gap-section-title">关系标签频次（Top {{ Math.min(20, edgeLabelStats.length) }}）</div>
          <div v-for="s in edgeLabelStats" :key="s.label" class="gap-bar-row">
            <span class="gap-bar-dot" style="background:#fbbf24" />
            <span class="gap-bar-label">{{ s.label }}</span>
            <div class="gap-bar-track">
              <div class="gap-bar-fill" :style="{ width: (s.count / (edgeLabelStats[0]?.count || 1) * 100) + '%', background: 'rgba(251,191,36,0.5)' }" />
            </div>
            <span class="gap-bar-count">{{ s.count }}</span>
          </div>
        </div>

        <!-- 度数排行 -->
        <div class="gap-section">
          <div class="gap-section-title">连接度排行（Top 10）</div>
          <table class="gap-table">
            <thead>
              <tr>
                <th>#</th>
                <th>节点</th>
                <th>类型</th>
                <th>入度</th>
                <th>出度</th>
                <th>总度</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in degreeRank" :key="row.n.id" class="gap-tr" @click="emit('focus-node', row.n.id)">
                <td class="gap-td rank">{{ i + 1 }}</td>
                <td class="gap-td node-name">{{ row.n.label }}</td>
                <td class="gap-td">
                  <span class="gap-chip" :style="{ color: typeColor(row.n.type), borderColor: typeColor(row.n.type) + '55' }">{{ typeLabel(row.n.type) }}</span>
                </td>
                <td class="gap-td num">{{ row.in }}</td>
                <td class="gap-td num">{{ row.out }}</td>
                <td class="gap-td num bold">{{ row.in + row.out }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 孤立节点 -->
        <div v-if="isolatedNodes.length" class="gap-section">
          <div class="gap-section-title">孤立节点（{{ isolatedNodes.length }} 个）</div>
          <div class="gap-chip-list">
            <span v-for="n in isolatedNodes" :key="n.id"
                  class="gap-chip gap-chip-click"
                  :style="{ color: typeColor(n.type), borderColor: typeColor(n.type) + '55' }"
                  @click="emit('focus-node', n.id)">
              {{ n.label }}
            </span>
          </div>
        </div>
      </template>

      <!-- ── Tab 1: 节点分析 ── -->
      <template v-if="tab === 1">
        <div v-if="!selNode" class="gap-hint">
          请在图谱中点击一个节点以查看精确分析
        </div>
        <template v-else>
          <!-- 节点基本信息 -->
          <div class="gap-section">
            <div class="gap-section-title">节点信息</div>
            <div class="gap-kv-list">
              <div class="gap-kv"><span class="gap-k">名称</span><span class="gap-v bold">{{ selNode.label }}</span></div>
              <div class="gap-kv"><span class="gap-k">ID</span><span class="gap-v mono">{{ selNode.id }}</span></div>
              <div class="gap-kv">
                <span class="gap-k">类型</span>
                <span class="gap-chip" :style="{ color: typeColor(selNode.type), borderColor: typeColor(selNode.type) + '55' }">{{ typeLabel(selNode.type) }}</span>
              </div>
              <div class="gap-kv">
                <span class="gap-k">来源</span>
                <span class="gap-badge" :style="{ color: sourceBadge(selNode.source).color }">{{ sourceBadge(selNode.source).text }}</span>
              </div>
              <div v-if="selNode.confidence != null" class="gap-kv">
                <span class="gap-k">置信度</span>
                <span class="gap-v">{{ (selNode.confidence * 100).toFixed(1) }}%</span>
              </div>
            </div>
          </div>

          <!-- 度数精确统计 -->
          <div class="gap-section">
            <div class="gap-section-title">连接度统计</div>
            <div class="gap-row-stats">
              <div class="gap-stat-card">
                <div class="gap-stat-n" style="color:#22dd88">{{ incoming.length }}</div>
                <div class="gap-stat-l">入度（被指向）</div>
              </div>
              <div class="gap-stat-card">
                <div class="gap-stat-n" style="color:#3d9bff">{{ outgoing.length }}</div>
                <div class="gap-stat-l">出度（指向他人）</div>
              </div>
              <div class="gap-stat-card">
                <div class="gap-stat-n">{{ incoming.length + outgoing.length }}</div>
                <div class="gap-stat-l">总度数</div>
              </div>
              <div class="gap-stat-card">
                <div class="gap-stat-n" style="color:#fbbf24">{{ twoHopNeighbors.length }}</div>
                <div class="gap-stat-l">二跳邻居</div>
              </div>
            </div>
          </div>

          <!-- 出边详情 -->
          <div class="gap-section">
            <div class="gap-section-title">出边（{{ outgoing.length }} 条）— 该节点指向的节点</div>
            <div v-if="!outgoing.length" class="gap-empty">无出边</div>
            <table v-else class="gap-table">
              <thead><tr><th>关系</th><th>目标节点</th><th>目标类型</th><th>来源</th></tr></thead>
              <tbody>
                <tr v-for="e in outgoing" :key="e.id" class="gap-tr"
                    @click="nmap[e.to] && emit('focus-node', e.to)">
                  <td class="gap-td amber">{{ e.label || '(未命名)' }}</td>
                  <td class="gap-td node-name">{{ nmap[e.to]?.label || e.to }}</td>
                  <td class="gap-td">
                    <span v-if="nmap[e.to]" class="gap-chip"
                          :style="{ color: typeColor(nmap[e.to].type), borderColor: typeColor(nmap[e.to].type) + '55' }">
                      {{ typeLabel(nmap[e.to].type) }}
                    </span>
                  </td>
                  <td class="gap-td">
                    <span class="gap-badge" :style="{ color: sourceBadge(e.source).color }">{{ sourceBadge(e.source).text }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 入边详情 -->
          <div class="gap-section">
            <div class="gap-section-title">入边（{{ incoming.length }} 条）— 指向该节点的节点</div>
            <div v-if="!incoming.length" class="gap-empty">无入边</div>
            <table v-else class="gap-table">
              <thead><tr><th>来源节点</th><th>来源类型</th><th>关系</th><th>来源</th></tr></thead>
              <tbody>
                <tr v-for="e in incoming" :key="e.id" class="gap-tr"
                    @click="nmap[e.from] && emit('focus-node', e.from)">
                  <td class="gap-td node-name">{{ nmap[e.from]?.label || e.from }}</td>
                  <td class="gap-td">
                    <span v-if="nmap[e.from]" class="gap-chip"
                          :style="{ color: typeColor(nmap[e.from].type), borderColor: typeColor(nmap[e.from].type) + '55' }">
                      {{ typeLabel(nmap[e.from].type) }}
                    </span>
                  </td>
                  <td class="gap-td amber">{{ e.label || '(未命名)' }}</td>
                  <td class="gap-td">
                    <span class="gap-badge" :style="{ color: sourceBadge(e.source).color }">{{ sourceBadge(e.source).text }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 二跳邻居 -->
          <div v-if="twoHopNeighbors.length" class="gap-section">
            <div class="gap-section-title">二跳邻居（{{ twoHopNeighbors.length }} 个）</div>
            <div class="gap-chip-list">
              <span v-for="n in twoHopNeighbors" :key="n.id"
                    class="gap-chip gap-chip-click"
                    :style="{ color: typeColor(n.type), borderColor: typeColor(n.type) + '55' }"
                    @click="emit('focus-node', n.id)">
                {{ n.label }}
              </span>
            </div>
          </div>
        </template>
      </template>

      <!-- ── Tab 2: 路径查询 ── -->
      <template v-if="tab === 2">
        <div class="gap-section">
          <div class="gap-section-title">路径查询</div>
          <div class="gap-path-form">
            <div class="gap-path-row">
              <span class="gap-path-lbl">起点</span>
              <select class="gap-select" v-model="pathFrom">
                <option value="">— 选择起点节点 —</option>
                <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.label }}</option>
              </select>
            </div>
            <div class="gap-path-row">
              <span class="gap-path-lbl">终点</span>
              <select class="gap-select" v-model="pathTo">
                <option value="">— 选择终点节点 —</option>
                <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.label }}</option>
              </select>
            </div>
            <div class="gap-path-row">
              <span class="gap-path-lbl">最大深度</span>
              <select class="gap-select" v-model="pathMaxLen" style="width:80px">
                <option v-for="d in [2,3,4,5,6,7,8]" :key="d" :value="d">{{ d }}</option>
              </select>
              <button class="gap-btn-primary" @click="findPaths" :disabled="pathLoading">
                {{ pathLoading ? '查询中…' : '查询' }}
              </button>
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

.gap-section { display: flex; flex-direction: column; gap: 8px; }
.gap-section-title { font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: .04em; font-weight: 600; }

.gap-row-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.gap-stat-card {
  background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.07);
  border-radius: 8px; padding: 10px 8px; text-align: center;
}
.gap-stat-n { font-size: 22px; font-weight: 700; line-height: 1; }
.gap-stat-l { font-size: 10px; color: #888; margin-top: 4px; }

.gap-bar-row { display: flex; align-items: center; gap: 8px; }
.gap-bar-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.gap-bar-label { min-width: 60px; font-size: 12px; color: #ccc; }
.gap-bar-track { flex: 1; height: 6px; background: rgba(255,255,255,.06); border-radius: 3px; overflow: hidden; }
.gap-bar-fill { height: 100%; border-radius: 3px; transition: width .3s; }
.gap-bar-count { min-width: 28px; text-align: right; font-size: 12px; color: #aaa; }

.gap-table { width: 100%; border-collapse: collapse; }
.gap-table th {
  text-align: left; font-size: 11px; color: #888; font-weight: 500;
  padding: 4px 6px; border-bottom: 1px solid rgba(255,255,255,.06);
}
.gap-tr { cursor: pointer; }
.gap-tr:hover td { background: rgba(255,255,255,.04); }
.gap-td { padding: 5px 6px; font-size: 12px; border-bottom: 1px solid rgba(255,255,255,.04); }
.gap-td.rank { color: #888; width: 24px; }
.gap-td.num { text-align: center; color: #aaa; width: 40px; }
.gap-td.bold { color: #e8eaed; font-weight: 600; }
.gap-td.amber { color: #fbbf24; }
.gap-td.node-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.gap-chip {
  display: inline-block; font-size: 11px; padding: 1px 7px;
  border: 1px solid rgba(255,255,255,.15); border-radius: 100px;
}
.gap-chip-click { cursor: pointer; }
.gap-chip-click:hover { opacity: .75; }
.gap-chip-list { display: flex; flex-wrap: wrap; gap: 6px; }
.gap-badge { font-size: 11px; font-weight: 500; }

.gap-kv-list { display: flex; flex-direction: column; gap: 6px; }
.gap-kv { display: flex; align-items: center; gap: 8px; }
.gap-k { min-width: 60px; font-size: 12px; color: #888; }
.gap-v { font-size: 13px; }
.gap-v.bold { font-weight: 600; }
.gap-v.mono { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: #aaa; }

.gap-hint { color: #888; text-align: center; padding: 40px 16px; font-size: 13px; }
.gap-empty { color: #888; font-size: 12px; padding: 6px 0; }
.gap-error { color: tomato; font-size: 12px; padding: 4px 0; }

.gap-path-form { display: flex; flex-direction: column; gap: 8px; }
.gap-path-row { display: flex; align-items: center; gap: 8px; }
.gap-path-lbl { min-width: 56px; font-size: 12px; color: #888; }
.gap-select {
  flex: 1; background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12);
  border-radius: 6px; padding: 5px 8px; color: #e8eaed; font-size: 12px;
  font-family: inherit;
}
.gap-btn-primary {
  background: #4a8df0; border: none; color: #fff;
  padding: 5px 14px; border-radius: 6px; cursor: pointer; font-size: 12px;
  flex-shrink: 0;
}
.gap-btn-primary:disabled { opacity: .5; cursor: not-allowed; }

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
