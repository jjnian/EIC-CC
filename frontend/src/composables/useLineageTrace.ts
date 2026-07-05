import type { OntologyEdge } from '../types';

/**
 * 这些受控关系类型里，边的 `from` 是"结果"、`to` 是"来源"（箭头指向上游）。
 * 其余类型（produces / flows_to / triggers / transforms / governs…）
 * 一律视为 from=来源、to=结果，顺箭头即下游。
 * 归一化后，统一按 source→result 的"流向"做可达性遍历，避免逐类型猜方向出错。
 * 注意：必须与后端 EdgeSemantics.REVERSED / NON_LINEAGE 保持一致（供 /lineage 上下游遍历），改一处要同步另一处。
 */
export const REVERSE_RELS = new Set(['derived_from', 'depends_on', 'consumes', 'composed_of']);

/**
 * 无数据流方向的纯关联类型:「A 与 B 相关」不构成上下游派生,
 * 不参与血缘遍历,避免关联噪声把不相干节点卷进上下游高亮。与后端 EdgeSemantics.NON_LINEAGE 对齐。
 */
export const NON_LINEAGE_RELS = new Set(['associated_with']);

export interface LineageResult {
  /** 全部上游来源节点 id（不含种子）。 */
  upstreamIds: string[];
  /** 全部下游影响节点 id（不含种子）。 */
  downstreamIds: string[];
}

const pushAdj = (m: Map<string, string[]>, k: string, v: string) => {
  const arr = m.get(k);
  if (arr) arr.push(v);
  else m.set(k, [v]);
};

const reachable = (start: string, adj: Map<string, string[]>): Set<string> => {
  const seen = new Set<string>();
  const stack = [start];
  while (stack.length) {
    const cur = stack.pop()!;
    for (const nx of adj.get(cur) || []) {
      if (nx !== start && !seen.has(nx)) {
        seen.add(nx);
        stack.push(nx);
      }
    }
  }
  return seen;
};

/** 归一化血缘流向图：fwd = source→results（顺流向=下游），bwd = result→sources（逆流向=上游）。 */
export interface FlowGraph {
  fwd: Map<string, string[]>;
  bwd: Map<string, string[]>;
}

/**
 * 把边集按 rel_type 归一化成 source→result 的流向图（trace / 影响报告 / 体检共用同一套方向语义）。
 * REVERSE_RELS 反向、NON_LINEAGE_RELS 不计、自环/断头边跳过。
 */
export function buildFlowGraph(edges: OntologyEdge[]): FlowGraph {
  const fwd = new Map<string, string[]>(); // source -> results（下游）
  const bwd = new Map<string, string[]>(); // result -> sources（上游）
  for (const e of edges) {
    if (!e.from || !e.to || e.from === e.to) continue;
    if (NON_LINEAGE_RELS.has(e.rel_type || '')) continue; // 纯关联不参与血缘
    const reverse = REVERSE_RELS.has(e.rel_type || '');
    const src = reverse ? e.to : e.from;
    const res = reverse ? e.from : e.to;
    pushAdj(fwd, src, res);
    pushAdj(bwd, res, src);
  }
  return { fwd, bwd };
}

/**
 * 计算某节点的上下游血缘。
 * <p>先把每条边按 rel_type 归一化成 source→result 的流向，再做可达性遍历：
 * 顺流向可达 = 下游影响（改它会波及谁），逆流向可达 = 上游来源（它来自哪里）。
 * 环路场景下同一节点可能既在上游又在下游，各自集合分别保留。
 */
export function traceLineage(seedId: string, edges: OntologyEdge[]): LineageResult {
  const { fwd, bwd } = buildFlowGraph(edges);
  return {
    downstreamIds: [...reachable(seedId, fwd)],
    upstreamIds: [...reachable(seedId, bwd)],
  };
}
