import type { OntologyNode, OntologyEdge } from '../../types';

/** 节点渲染尺寸（与 GraphCanvas 中保持一致）。 */
export const NODE_W = 176;
export const NODE_H = 48;

const LAYER_GAP = 240;
const ROW_GAP = 76;

export interface XY { x: number; y: number }

/**
 * 分层自动布局：按血缘方向做最长路径分层（LR），同层纵向排开。
 * 环安全：用「每条边最多松弛一次」的 BFS 上限避免死循环。
 */
export function layeredLayout(nodes: OntologyNode[], edges: OntologyEdge[]): Map<string, XY> {
  const ids = new Set(nodes.map((n) => n.id));
  const adj = new Map<string, string[]>();
  const indeg = new Map<string, number>();
  for (const n of nodes) { adj.set(n.id, []); indeg.set(n.id, 0); }
  for (const e of edges) {
    if (!ids.has(e.from) || !ids.has(e.to)) continue;
    adj.get(e.from)!.push(e.to);
    indeg.set(e.to, (indeg.get(e.to) || 0) + 1);
  }

  // 最长路径深度（Kahn 拓扑；有环时剩余节点按入度补齐）
  const depth = new Map<string, number>();
  const queue: string[] = [];
  for (const n of nodes) {
    if ((indeg.get(n.id) || 0) === 0) { depth.set(n.id, 0); queue.push(n.id); }
  }
  const indegLeft = new Map(indeg);
  let guard = edges.length + nodes.length + 4;
  while (queue.length && guard-- > 0) {
    const cur = queue.shift()!;
    const d = depth.get(cur) ?? 0;
    for (const nxt of adj.get(cur) || []) {
      depth.set(nxt, Math.max(depth.get(nxt) ?? 0, d + 1));
      const left = (indegLeft.get(nxt) || 1) - 1;
      indegLeft.set(nxt, left);
      if (left <= 0) queue.push(nxt);
    }
  }
  // 环上或未被访问的节点：放到最大深度之后
  let maxDepth = 0;
  for (const d of depth.values()) maxDepth = Math.max(maxDepth, d);
  for (const n of nodes) {
    if (!depth.has(n.id)) depth.set(n.id, maxDepth + 1);
  }

  // 分层归组，层内按父节点平均位置排序（减少交叉）
  const layers = new Map<number, OntologyNode[]>();
  for (const n of nodes) {
    const d = depth.get(n.id) ?? 0;
    if (!layers.has(d)) layers.set(d, []);
    layers.get(d)!.push(n);
  }

  const pos = new Map<string, XY>();
  const sortedDepths = [...layers.keys()].sort((a, b) => a - b);
  const parentsOf = new Map<string, string[]>();
  for (const e of edges) {
    if (!ids.has(e.from) || !ids.has(e.to)) continue;
    if (!parentsOf.has(e.to)) parentsOf.set(e.to, []);
    parentsOf.get(e.to)!.push(e.from);
  }

  for (const d of sortedDepths) {
    const layer = layers.get(d)!;
    layer.sort((a, b) => {
      const pa = (parentsOf.get(a.id) || []).map((p) => pos.get(p)?.y ?? 0);
      const pb = (parentsOf.get(b.id) || []).map((p) => pos.get(p)?.y ?? 0);
      const ma = pa.length ? pa.reduce((s, v) => s + v, 0) / pa.length : 0;
      const mb = pb.length ? pb.reduce((s, v) => s + v, 0) / pb.length : 0;
      return ma - mb || a.label.localeCompare(b.label, 'zh');
    });
    const totalH = (layer.length - 1) * ROW_GAP;
    layer.forEach((n, i) => {
      pos.set(n.id, { x: d * LAYER_GAP, y: i * ROW_GAP - totalH / 2 });
    });
  }
  return pos;
}

/** 计算一组坐标的包围盒。 */
export function boundsOf(pos: Iterable<XY>): { minX: number; minY: number; maxX: number; maxY: number } {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of pos) {
    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
    maxX = Math.max(maxX, p.x + NODE_W); maxY = Math.max(maxY, p.y + NODE_H);
  }
  if (!Number.isFinite(minX)) { minX = 0; minY = 0; maxX = 400; maxY = 300; }
  return { minX, minY, maxX, maxY };
}
