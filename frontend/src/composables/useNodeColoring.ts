import { ref, computed } from 'vue';
import { NT } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

export interface NodeColoringCtx {
  getNodes: () => OntologyNode[];
  getEdges: () => OntologyEdge[];
  getSelId: () => string | null;
  getDiffHighlight: () => { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null | undefined;
  getNmap: () => Record<string, OntologyNode>;
}

/**
 * 节点 / 边的着色与筛选:图例类型筛选、热力图概率色阶、分支对比着色、
 * 邻居高亮集合、类型样式查表。从 GraphCanvas.vue 抽出以降低主组件行数。
 */
export function useNodeColoring(ctx: NodeColoringCtx) {
  /* ── 节点类型筛选(图例点击) ── */
  // 当前筛选的节点 type;null 表示不筛选,'_edge_' 表示筛选关系(边)
  const typeFilter = ref<string | null>(null);
  const toggleTypeFilter = (k: string) => {
    typeFilter.value = typeFilter.value === k ? null : k;
  };
  const toggleEdgeFilter = () => {
    typeFilter.value = typeFilter.value === '_edge_' ? null : '_edge_';
  };
  const matchesFilter = (n: any) => {
    if (!typeFilter.value) return true;
    if (typeFilter.value === '_edge_') return false;
    return n.type === typeFilter.value;
  };
  const edgeMatchesFilter = (e: any) => {
    if (!typeFilter.value) return true;
    if (typeFilter.value === '_edge_') return true;
    const nmap = ctx.getNmap();
    const fn = nmap[e.from];
    const tn = nmap[e.to];
    return (fn && fn.type === typeFilter.value) || (tn && tn.type === typeFilter.value);
  };

  /* ── 热力图 / 差异着色 ── */
  const heatmapMode = ref(false);

  /** 热力图模式下,预测节点根据 effectiveProbability 在绿→黄→红之间渐变。 */
  const heatColor = (n: any) => {
    if (!heatmapMode.value || n.source !== 'predicted') return null;
    const p = n.effectiveProbability || n.confidence || 0;
    // HSL 色相:0=红,60=黄,120=绿;线性映射 p∈[0,1] → h∈[0,120]
    const h = p * 120;
    return `hsl(${h}, 80%, 45%)`;
  };

  /** 分支对比着色:蓝=A 独有,橙=B 独有,紫=共同;优先级高于热力图。 */
  const diffColor = (n: any) => {
    const diff = ctx.getDiffHighlight();
    if (!diff) return null;
    if (diff.uniqueAIds.includes(n.id)) return '#3b82f6'; // 蓝色 = A 独有
    if (diff.uniqueBIds.includes(n.id)) return '#f97316'; // 橙色 = B 独有
    if (diff.sharedIds.includes(n.id)) return '#a855f7';  // 紫色 = 共同
    return null;
  };

  const getT = (n: any) => (NT as any)[n.type] || NT.class;

  const neighborIds = computed(() => {
    const selId = ctx.getSelId();
    if (!selId) return null;
    const ids = new Set<string>();
    ids.add(selId);
    for (const e of ctx.getEdges()) {
      if (e.from === selId) ids.add(e.to);
      if (e.to === selId) ids.add(e.from);
    }
    return ids;
  });

  return {
    typeFilter,
    toggleTypeFilter,
    toggleEdgeFilter,
    matchesFilter,
    edgeMatchesFilter,
    heatmapMode,
    heatColor,
    diffColor,
    getT,
    neighborIds,
  };
}
