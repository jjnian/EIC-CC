<script setup lang="ts">
import { useGraphStats } from '../../composables/useGraphStats';
import type { OntologyNode, OntologyEdge } from '../../types';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
}>();

const {
  typeColor, typeLabel,
  nodeTypeStats, edgeLabelStats, edgeSourceStats, degreeRank, isolatedNodes,
} = useGraphStats(() => props.nodes, () => props.edges);
</script>

<template>
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
.gap-td.node-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.gap-chip {
  display: inline-block; font-size: 11px; padding: 4px 11px; line-height: 1.4;
  border: 1px solid rgba(255,255,255,.15); border-radius: 100px;
}
.gap-chip-click { cursor: pointer; }
.gap-chip-click:hover { opacity: .75; }
.gap-chip-list { display: flex; flex-wrap: wrap; gap: 6px; }
</style>
