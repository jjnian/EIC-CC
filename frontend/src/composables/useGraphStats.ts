import { computed } from 'vue';
import { NT } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

/**
 * 全图统计逻辑：从 GraphAnalysisPanel.vue 的 Tab 0（全图统计）抽出，
 * 行为不变。computed 内部以 getNodes()/getEdges() 取代原 props.nodes/props.edges。
 */
export function useGraphStats(
  getNodes: () => OntologyNode[],
  getEdges: () => OntologyEdge[],
) {
  // ── 辅助 ────────────────────────────────────────────
  const typeColor = (type: string) => (NT as any)[type]?.color || '#059669';
  const typeLabel = (type: string) => (NT as any)[type]?.label || type;

  const sourceBadge = (s?: string) => {
    if (s === 'inferred')  return { text: 'AI推理',   color: '#7c3aed' };
    if (s === 'derived')   return { text: '文本提取', color: '#059669' };
    if (s === 'manual')    return { text: '手动',     color: '#2563eb' };
      return { text: '预置', color: '#a1a1aa' };
  };

  // 节点按类型分组
  const nodeTypeStats = computed(() => {
    const m = new Map<string, number>();
    for (const n of getNodes()) {
      m.set(n.type, (m.get(n.type) || 0) + 1);
    }
    return [...m.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([type, count]) => ({ type, count, color: typeColor(type), label: typeLabel(type) }));
  });

  // 边按标签分组
  const edgeLabelStats = computed(() => {
    const m = new Map<string, number>();
    for (const e of getEdges()) {
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
    for (const e of getEdges()) {
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
    for (const n of getNodes()) deg.set(n.id, { in: 0, out: 0 });
    for (const e of getEdges()) {
      if (deg.has(e.from)) deg.get(e.from)!.out++;
      if (deg.has(e.to))   deg.get(e.to)!.in++;
    }
    return getNodes()
      .map(n => ({ n, ...deg.get(n.id)! }))
      .sort((a, b) => (b.in + b.out) - (a.in + a.out))
      .slice(0, 10);
  });

  // 孤立节点（入度=0 且 出度=0）
  const isolatedNodes = computed(() => {
    const connected = new Set<string>();
    for (const e of getEdges()) {
      connected.add(e.from);
      connected.add(e.to);
    }
    return getNodes().filter(n => !connected.has(n.id));
  });

  return {
    typeColor, typeLabel, sourceBadge,
    nodeTypeStats, edgeLabelStats, edgeSourceStats, degreeRank, isolatedNodes,
  };
}
