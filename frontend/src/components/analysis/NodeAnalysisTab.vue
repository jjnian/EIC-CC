<script setup lang="ts">
import { computed } from 'vue';
import { NT } from '../../constants';
import type { OntologyNode, OntologyEdge } from '../../types';
import { traceLineage } from '../../composables/useLineageTrace';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selectedId: string | null;
}>();

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
  /** 上下游血缘高亮：复用画布对比高亮通道（蓝=上游/橙=下游/紫=种子）。 */
  (e: 'highlight-diff', data: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null): void;
}>();

// ── 辅助 ────────────────────────────────────────────
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

const typeColor = (type: string) => (NT as any)[type]?.color || '#2f86d6';
const typeLabel = (type: string) => (NT as any)[type]?.label || type;

const sourceBadge = (s?: string) => {
  if (s === 'inferred')  return { text: 'AI推理',   color: '#bb77ff' };
  if (s === 'derived')   return { text: '文本提取', color: '#22dd88' };
  if (s === 'manual')    return { text: '手动',     color: '#3d9bff' };
  if (s === 'predicted') return { text: '推演',     color: '#fbbf24' };
  return { text: '预置', color: '#888' };
};

// ── 节点精确分析 ──────────────────────────────────────
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

// ── 上下游血缘追溯 ────────────────────────────────────
// 按 rel_type 归一化流向后做可达性遍历：上游=来源链，下游=影响链。
const lineage = computed(() =>
  selNode.value ? traceLineage(selNode.value.id, props.edges) : { upstreamIds: [], downstreamIds: [] });
const upstreamNodes = computed(() =>
  lineage.value.upstreamIds.map(id => nmap.value[id]).filter(Boolean) as OntologyNode[]);
const downstreamNodes = computed(() =>
  lineage.value.downstreamIds.map(id => nmap.value[id]).filter(Boolean) as OntologyNode[]);

// 在画布上高亮血缘：复用对比高亮通道。蓝=上游来源、橙=下游影响、紫=当前节点。
const highlightLineage = () => {
  if (!selNode.value) return;
  emit('highlight-diff', {
    sharedIds: [selNode.value.id],
    uniqueAIds: lineage.value.upstreamIds,
    uniqueBIds: lineage.value.downstreamIds,
  });
};
const clearLineage = () => emit('highlight-diff', null);
</script>

<template>
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

    <!-- 上下游血缘追溯 -->
    <div class="gap-section">
      <div class="gap-section-title">血缘追溯</div>
      <div class="gap-row-stats gap-lineage-stats">
        <div class="gap-stat-card">
          <div class="gap-stat-n" style="color:#3b82f6">{{ upstreamNodes.length }}</div>
          <div class="gap-stat-l">上游来源</div>
        </div>
        <div class="gap-stat-card">
          <div class="gap-stat-n" style="color:#f97316">{{ downstreamNodes.length }}</div>
          <div class="gap-stat-l">下游影响</div>
        </div>
      </div>
      <div class="gap-lineage-actions">
        <button class="gap-btn gap-btn-primary"
                :disabled="!upstreamNodes.length && !downstreamNodes.length"
                @click="highlightLineage">🩸 在图上高亮血缘</button>
        <button class="gap-btn" @click="clearLineage">清除</button>
      </div>
      <div class="gap-lineage-legend">
        <span><i class="lg-dot" style="background:#3b82f6"></i>上游来源</span>
        <span><i class="lg-dot" style="background:#f97316"></i>下游影响</span>
        <span><i class="lg-dot" style="background:#a855f7"></i>当前节点</span>
      </div>
      <div v-if="upstreamNodes.length" class="gap-lineage-group">
        <div class="gap-lineage-gt" style="color:#3b82f6">上游来源链（{{ upstreamNodes.length }}）— 它来自哪里</div>
        <div class="gap-chip-list">
          <span v-for="n in upstreamNodes" :key="'up'+n.id" class="gap-chip gap-chip-click"
                :style="{ color: typeColor(n.type), borderColor: typeColor(n.type) + '55' }"
                @click="emit('focus-node', n.id)">{{ n.label }}</span>
        </div>
      </div>
      <div v-if="downstreamNodes.length" class="gap-lineage-group">
        <div class="gap-lineage-gt" style="color:#f97316">下游影响链（{{ downstreamNodes.length }}）— 改它会波及谁</div>
        <div class="gap-chip-list">
          <span v-for="n in downstreamNodes" :key="'down'+n.id" class="gap-chip gap-chip-click"
                :style="{ color: typeColor(n.type), borderColor: typeColor(n.type) + '55' }"
                @click="emit('focus-node', n.id)">{{ n.label }}</span>
        </div>
      </div>
      <div v-if="!upstreamNodes.length && !downstreamNodes.length" class="gap-empty">该节点无上下游血缘连接</div>
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

<style scoped>
.gap-section { display: flex; flex-direction: column; gap: 8px; }
.gap-section-title { font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: .04em; font-weight: 600; }

.gap-row-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.gap-stat-card {
  background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.07);
  border-radius: 8px; padding: 10px 8px; text-align: center;
}
.gap-stat-n { font-size: 22px; font-weight: 700; line-height: 1; }
.gap-stat-l { font-size: 10px; color: #888; margin-top: 4px; }

.gap-table { width: 100%; border-collapse: collapse; }
.gap-table th {
  text-align: left; font-size: 11px; color: #888; font-weight: 500;
  padding: 4px 6px; border-bottom: 1px solid rgba(255,255,255,.06);
}
.gap-tr { cursor: pointer; }
.gap-tr:hover td { background: rgba(255,255,255,.04); }
.gap-td { padding: 5px 6px; font-size: 12px; border-bottom: 1px solid rgba(255,255,255,.04); }
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

/* ── 血缘追溯 ── */
.gap-lineage-stats { grid-template-columns: repeat(2, 1fr); }
.gap-lineage-actions { display: flex; gap: 8px; }
.gap-btn {
  flex: 1; padding: 7px 10px; font-size: 12px; cursor: pointer;
  background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.12);
  color: #e8eaed; border-radius: 7px; font-family: inherit;
  transition: background-color .15s, border-color .15s, opacity .15s;
}
.gap-btn:hover { background: rgba(255,255,255,.1); }
.gap-btn:disabled { opacity: .4; cursor: not-allowed; }
.gap-btn-primary { flex: 2; background: rgba(168,85,247,.16); border-color: rgba(168,85,247,.4); color: #c9a3ff; }
.gap-btn-primary:hover:not(:disabled) { background: rgba(168,85,247,.26); }
.gap-lineage-legend { display: flex; gap: 14px; font-size: 11px; color: #999; }
.gap-lineage-legend span { display: inline-flex; align-items: center; gap: 5px; }
.lg-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.gap-lineage-group { display: flex; flex-direction: column; gap: 6px; margin-top: 2px; }
.gap-lineage-gt { font-size: 11px; font-weight: 600; }
</style>
