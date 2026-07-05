import type { OntologyNode, OntologyEdge } from '../types';
import { buildFlowGraph, REVERSE_RELS, NON_LINEAGE_RELS } from '../composables/useLineageTrace';

/**
 * 血缘影响分析报告：以某节点为中心，生成一份可评审 / 归档的 Markdown。
 * <p>下游影响（blast radius）= 改动该节点会顺流向波及谁；上游来源 = 它依赖谁。
 * 均按「跳数」分层，直观呈现波及范围随距离的衰减，供变更评审判断爆炸半径。
 * 方向语义与 {@link traceLineage} / {@link analyzeLineageHealth} 完全一致（复用 buildFlowGraph）。
 */

/** 从 seed 在 adj 上做 BFS，返回每个可达节点的最短跳数（不含 seed 自身）。 */
function bfsDepth(seed: string, adj: Map<string, string[]>): Map<string, number> {
  const depth = new Map<string, number>();
  let frontier = [seed];
  let d = 0;
  const seen = new Set<string>([seed]);
  while (frontier.length) {
    const next: string[] = [];
    for (const u of frontier) {
      for (const v of adj.get(u) || []) {
        if (seen.has(v)) continue;
        seen.add(v);
        depth.set(v, d + 1);
        next.push(v);
      }
    }
    frontier = next;
    d++;
  }
  return depth;
}

/** 按跳数分组渲染一份节点清单（1 跳、2 跳…），每层内按 label 排序。 */
function renderLayers(depth: Map<string, number>, nmap: Map<string, OntologyNode>): string {
  if (depth.size === 0) return '_（无）_\n';
  const byDepth = new Map<number, OntologyNode[]>();
  for (const [id, d] of depth) {
    const n = nmap.get(id);
    if (!n) continue;
    const arr = byDepth.get(d);
    if (arr) arr.push(n); else byDepth.set(d, [n]);
  }
  const out: string[] = [];
  for (const d of [...byDepth.keys()].sort((a, b) => a - b)) {
    const layer = byDepth.get(d)!.sort((a, b) => (a.label || '').localeCompare(b.label || ''));
    out.push(`- **第 ${d} 跳（${layer.length}）**：` +
      layer.map(n => `${n.label}${n.type ? `\`${n.type}\`` : ''}`).join('、'));
  }
  return out.join('\n') + '\n';
}

/** 一条与 seed 直接相连的边的可读描述（含方向语义与置信度）。 */
function edgeLine(e: OntologyEdge, nmap: Map<string, OntologyNode>): string {
  const from = nmap.get(e.from)?.label || e.from;
  const to = nmap.get(e.to)?.label || e.to;
  const rel = e.rel_type ? `\`${e.rel_type}\`` : (e.label || '关联');
  const conf = typeof e.confidence === 'number' ? ` · 置信 ${(e.confidence * 100).toFixed(0)}%` : '';
  const src = e.source === 'inferred' ? ' · 推断' : e.source === 'derived' ? ' · 派生' : '';
  return `- ${from} —${rel}→ ${to}${conf}${src}`;
}

/**
 * 生成某节点的血缘影响分析报告（Markdown）。
 * @param seed  中心节点
 * @param nodes 全图节点
 * @param edges 全图边
 * @param when  报告生成时间（调用方传入，便于测试；默认取当前时间）
 */
export function buildImpactReport(
  seed: OntologyNode,
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  when: Date = new Date(),
): string {
  const nmap = new Map(nodes.map(n => [n.id, n]));
  const { fwd, bwd } = buildFlowGraph(edges);
  const downstream = bfsDepth(seed.id, fwd);
  const upstream = bfsDepth(seed.id, bwd);

  // seed 的直接关系（含纯关联，供人工判断）；分进/出与关联三类
  const direct = edges.filter(e => e.from === seed.id || e.to === seed.id);
  const assoc = direct.filter(e => NON_LINEAGE_RELS.has(e.rel_type || ''));
  const flowEdges = direct.filter(e => !NON_LINEAGE_RELS.has(e.rel_type || '') && e.from !== e.to);
  // 顺流向"出边"= seed 是上游源；结合 REVERSE 语义判定
  const outEdges = flowEdges.filter(e => {
    const rev = REVERSE_RELS.has(e.rel_type || '');
    return (rev ? e.to : e.from) === seed.id;
  });
  const inEdges = flowEdges.filter(e => {
    const rev = REVERSE_RELS.has(e.rel_type || '');
    return (rev ? e.from : e.to) === seed.id;
  });

  const ts = `${when.getFullYear()}-${String(when.getMonth() + 1).padStart(2, '0')}-${String(when.getDate()).padStart(2, '0')}`;
  const lines: string[] = [];
  lines.push(`# 血缘影响分析：${seed.label}`);
  lines.push('');
  lines.push(`> 中心节点：**${seed.label}**${seed.type ? `（${seed.type}）` : ''} · 生成于 ${ts}`);
  if (seed.derived_source) lines.push(`> 来源：${seed.derived_source}`);
  lines.push('');
  lines.push('## 概览');
  lines.push('');
  lines.push(`| 维度 | 数量 | 含义 |`);
  lines.push(`| --- | --- | --- |`);
  lines.push(`| 下游影响 | ${downstream.size} | 改动本节点会顺血缘波及的节点（爆炸半径） |`);
  lines.push(`| 上游来源 | ${upstream.size} | 本节点的数据/派生来源链 |`);
  lines.push(`| 直接关系 | ${direct.length} | 与本节点直接相连的边（进 ${inEdges.length} · 出 ${outEdges.length} · 关联 ${assoc.length}） |`);
  lines.push('');
  lines.push('## 下游影响范围（blast radius，按跳数分层）');
  lines.push('');
  lines.push('> 变更、下线或口径调整本节点时，下列节点可能受影响，跳数越近影响越直接。');
  lines.push('');
  lines.push(renderLayers(downstream, nmap));
  lines.push('## 上游来源链（按跳数分层）');
  lines.push('');
  lines.push('> 本节点的数据由下列上游派生/供给，排查数据异常时可逆流向上追溯。');
  lines.push('');
  lines.push(renderLayers(upstream, nmap));
  lines.push('## 直接血缘关系');
  lines.push('');
  if (outEdges.length) {
    lines.push('**下游出边（本节点 → 下游）**');
    lines.push('');
    outEdges.forEach(e => lines.push(edgeLine(e, nmap)));
    lines.push('');
  }
  if (inEdges.length) {
    lines.push('**上游入边（上游 → 本节点）**');
    lines.push('');
    inEdges.forEach(e => lines.push(edgeLine(e, nmap)));
    lines.push('');
  }
  if (assoc.length) {
    lines.push('**关联关系（无数据流向）**');
    lines.push('');
    assoc.forEach(e => lines.push(edgeLine(e, nmap)));
    lines.push('');
  }
  if (!direct.length) lines.push('_本节点无任何直接关系（孤立节点）。_\n');
  lines.push('---');
  lines.push('> 本报告由血缘图自动生成，用于变更影响评估与评审；下游影响基于当前图的 rel_type 方向语义推算，边方向存疑时以数据佐证（值包含检验）为准。');
  return lines.join('\n');
}
