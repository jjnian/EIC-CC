import type { OntologyNode, OntologyEdge } from '../types';
import { REVERSE_RELS, NON_LINEAGE_RELS } from '../composables/useLineageTrace';

/**
 * 血缘结构体检（补充 {@link detectConflicts} 未覆盖的结构问题）：在「归一化数据流向图」上做纯计算，
 * 找出建图产物里易漏的结构缺陷——孤立节点与血缘碎片化。环路/方向矛盾/重复节点由
 * {@link detectConflicts} 负责，本模块不重复。
 * <p>方向语义与 {@link traceLineage} 一致：边按 rel_type 归一化成 source→result 流向
 * （REVERSE_RELS 反向、NON_LINEAGE_RELS 不计），再在该有向图上分析。
 */

export interface LineageHealth {
  /** 孤立节点 id：不参与任何血缘流向边的业务节点（只有 associated_with 关联或完全悬空——多为噪声/漏抽）。 */
  isolatedIds: string[];
  /** 参与血缘的节点被分成几个互不连通的子图（弱连通分量数），>1 表示血缘被割裂成多块。 */
  componentCount: number;
}

/** 一条边是否构成血缘流向（纯关联/自环/断头边不算）；构成时返回其两端点。 */
function flowEndpoints(e: OntologyEdge, present: Set<string>): [string, string] | null {
  if (!e.from || !e.to || e.from === e.to) return null;
  if (NON_LINEAGE_RELS.has(e.rel_type || '')) return null;
  if (!present.has(e.from) || !present.has(e.to)) return null;
  const reverse = REVERSE_RELS.has(e.rel_type || '');
  return reverse ? [e.to, e.from] : [e.from, e.to];
}

/**
 * 计算一张图的血缘结构体检结果。
 * @param bizNodes 参与体检的业务节点（调用方已剔除 attribute/constraint 等 schema 节点）
 * @param edges    全部边（内部按端点是否在 bizNodes 中过滤）
 */
export function analyzeLineageHealth(bizNodes: OntologyNode[], edges: OntologyEdge[]): LineageHealth {
  const present = new Set(bizNodes.map(n => n.id));
  const touched = new Set<string>();  // 参与任一血缘流向边的节点

  // 弱连通并查集（把流向边当无向处理）：数血缘子图数
  const parent = new Map<string, string>();
  const find = (x: string): string => {
    let r = x;
    while (parent.get(r) !== r) r = parent.get(r)!;
    while (parent.get(x) !== r) { const nx = parent.get(x)!; parent.set(x, r); x = nx; }
    return r;
  };
  const union = (a: string, b: string) => {
    if (!parent.has(a)) parent.set(a, a);
    if (!parent.has(b)) parent.set(b, b);
    const ra = find(a), rb = find(b);
    if (ra !== rb) parent.set(ra, rb);
  };

  for (const e of edges) {
    const ends = flowEndpoints(e, present);
    if (!ends) continue;
    touched.add(ends[0]);
    touched.add(ends[1]);
    union(ends[0], ends[1]);
  }

  const isolatedIds = bizNodes.filter(n => !touched.has(n.id)).map(n => n.id);
  const roots = new Set<string>();
  for (const id of touched) roots.add(find(id));

  return { isolatedIds, componentCount: roots.size };
}
