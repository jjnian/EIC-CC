import type { Ref } from 'vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from '../types';
import { toast } from './useToast';

export interface GraphActionsCtx {
  nodes: Ref<OntologyNode[]>;
  edges: Ref<OntologyEdge[]>;
  /** 当前模型,导出/共享时取标题用。 */
  currentModel: () => OntologyModel | undefined;
  /** 当前模型的展示标题(回退值)。 */
  currentTitle: () => string;
  /** auto-layout 完成后调用,等待画布拟合视野。 */
  fitView?: () => void;
  /** 写入后触发持久化保存。 */
  persist: (immediate?: boolean) => void;
}

const NODE_W = 172;
const NODE_H = 44;
const X_GAP = 90;
const Y_GAP = 28;
const X_SPACING = NODE_W + X_GAP;
const Y_SPACING = NODE_H + Y_GAP;
const ORIGIN_X = 80;
const ORIGIN_Y = 80;

/**
 * 图谱顶栏的三个动作:auto-layout、exportGraph、shareGraph。
 */
export function useGraphActions(ctx: GraphActionsCtx) {
  /**
   * 血缘分层布局(Sugiyama 风格简化版):
   *   1. 检测 DAG 中的回边,布局时忽略掉(避免无限层级)。
   *   2. 用「最长路径」给每个节点定层 lvl(L→R 表示因果/血缘流向)。
   *   3. 多轮 barycenter 排序减少线交叉。
   *   4. 每一列按节点数竖直居中,整体观感平衡。
   *   5. 孤立节点放到底部,按 4 列网格平铺。
   */
  const autoLayout = () => {
    const nodes = ctx.nodes.value;
    const edges = ctx.edges.value;
    if (nodes.length === 0) return;

    // 1. 邻接表
    const outgoing: Record<string, string[]> = {};
    const incoming: Record<string, string[]> = {};
    nodes.forEach(n => { outgoing[n.id] = []; incoming[n.id] = []; });
    edges.forEach(e => {
      if (outgoing[e.from] && incoming[e.to]) {
        outgoing[e.from].push(e.to);
        incoming[e.to].push(e.from);
      }
    });

    // 2. DFS 标记回边(back edge),不参与分层
    const reverseEdges = new Set<string>();
    const edgeKey = (from: string, to: string) => from + '→' + to;
    const color: Record<string, 0 | 1 | 2> = {};
    nodes.forEach(n => { color[n.id] = 0; });
    const dfs = (id: string) => {
      color[id] = 1;
      for (const next of outgoing[id]) {
        if (color[next] === 1) {
          // 沿 id → next 的边形成环,把它视作回边
          reverseEdges.add(edgeKey(id, next));
        } else if (color[next] === 0) {
          dfs(next);
        }
      }
      color[id] = 2;
    };
    for (const n of nodes) {
      if (color[n.id] === 0) dfs(n.id);
    }

    const effectiveParents = (id: string) =>
      incoming[id].filter(p => !reverseEdges.has(edgeKey(p, id)));
    const effectiveChildren = (id: string) =>
      outgoing[id].filter(c => !reverseEdges.has(edgeKey(id, c)));

    // 3. 最长路径分层(后向 DP,带 memo)
    const levels: Record<string, number> = {};
    const computeLevel = (id: string, stack: Set<string>): number => {
      if (id in levels) return levels[id];
      if (stack.has(id)) return 0; // 防御:残留环
      stack.add(id);
      let max = 0;
      for (const parent of effectiveParents(id)) {
        max = Math.max(max, computeLevel(parent, stack) + 1);
      }
      stack.delete(id);
      levels[id] = max;
      return max;
    };
    nodes.forEach(n => computeLevel(n.id, new Set()));

    // 4. 区分孤立 vs 已连通节点
    const groups: Record<number, OntologyNode[]> = {};
    const isolated: OntologyNode[] = [];
    let maxLevel = 0;
    for (const n of nodes) {
      const connected = outgoing[n.id].length > 0 || incoming[n.id].length > 0;
      if (!connected) { isolated.push(n); continue; }
      const lvl = levels[n.id];
      maxLevel = Math.max(maxLevel, lvl);
      (groups[lvl] = groups[lvl] || []).push(n);
    }

    // 5. barycenter 排序:多轮 down/up 扫描降低交叉数
    const indexInLevel = (id: string): number => {
      const lvl = levels[id];
      const g = groups[lvl];
      if (!g) return 0;
      const i = g.findIndex(n => n.id === id);
      return i < 0 ? 0 : i;
    };
    const sweep = (direction: 'down' | 'up') => {
      const start = direction === 'down' ? 1 : maxLevel - 1;
      const end = direction === 'down' ? maxLevel : 0;
      const step = direction === 'down' ? 1 : -1;
      for (let lvl = start; direction === 'down' ? lvl <= end : lvl >= end; lvl += step) {
        const group = groups[lvl];
        if (!group || group.length <= 1) continue;
        const refOf = direction === 'down' ? effectiveParents : effectiveChildren;
        const decorated = group.map((n, originalIdx) => {
          const refs = refOf(n.id);
          const score = refs.length
            ? refs.reduce((s, id) => s + indexInLevel(id), 0) / refs.length
            : originalIdx; // 没有上/下游连接时保持稳定
          return { n, score, originalIdx };
        });
        decorated.sort((a, b) => a.score - b.score || a.originalIdx - b.originalIdx);
        groups[lvl] = decorated.map(d => d.n);
      }
    };
    for (let iter = 0; iter < 4; iter++) {
      sweep('down');
      sweep('up');
    }

    // 6. 计算每列高度,整体竖直居中
    let maxLayerCount = 1;
    for (let lvl = 0; lvl <= maxLevel; lvl++) {
      const g = groups[lvl];
      if (g && g.length > maxLayerCount) maxLayerCount = g.length;
    }
    const totalHeight = (maxLayerCount - 1) * Y_SPACING;

    for (let lvl = 0; lvl <= maxLevel; lvl++) {
      const group = groups[lvl];
      if (!group) continue;
      const groupHeight = (group.length - 1) * Y_SPACING;
      const yOffset = (totalHeight - groupHeight) / 2;
      group.forEach((n, idx) => {
        n.x = ORIGIN_X + lvl * X_SPACING;
        n.y = ORIGIN_Y + yOffset + idx * Y_SPACING;
      });
    }

    // 7. 孤立节点:底部 4 列网格
    if (isolated.length > 0) {
      const cols = Math.min(4, isolated.length);
      const isoStartY = ORIGIN_Y + totalHeight + Y_SPACING * 2;
      isolated.forEach((n, idx) => {
        n.x = ORIGIN_X + (idx % cols) * X_SPACING;
        n.y = isoStartY + Math.floor(idx / cols) * Y_SPACING;
      });
    }

    setTimeout(() => ctx.fitView?.(), 50);
    ctx.persist();
  };

  /**
   * 给一批"刚从对话中提取出来"的新节点选一个合理初始位置:
   * 若画布已经有节点,放到现有图右侧的"暂存区";否则原点处摆开。
   * 实际美化由后续 autoLayout 完成。
   */
  const placeIncomingNodes = (newNodes: OntologyNode[]) => {
    if (newNodes.length === 0) return;
    const existing = ctx.nodes.value;
    if (existing.length === 0) {
      newNodes.forEach((n, idx) => {
        n.x = ORIGIN_X + (idx % 4) * X_SPACING;
        n.y = ORIGIN_Y + Math.floor(idx / 4) * Y_SPACING;
      });
      return;
    }
    let maxX = -Infinity;
    let minY = Infinity;
    for (const n of existing) {
      if (n.x > maxX) maxX = n.x;
      if (n.y < minY) minY = n.y;
    }
    const baseX = maxX + X_SPACING;
    const baseY = minY;
    const cols = Math.max(1, Math.min(2, Math.ceil(newNodes.length / 6)));
    newNodes.forEach((n, idx) => {
      n.x = baseX + (idx % cols) * X_SPACING;
      n.y = baseY + Math.floor(idx / cols) * Y_SPACING;
    });
  };

  /** 把当前图谱导出为 JSON 文件下载。 */
  const exportGraph = () => {
    const m = ctx.currentModel();
    const payload = {
      id: m?.id,
      title: m?.title || ctx.currentTitle(),
      exportedAt: new Date().toISOString(),
      nodes: ctx.nodes.value,
      edges: ctx.edges.value,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${(m?.title || 'graph').replace(/[^\w一-龥-]+/g, '_')}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  /** 把当前图谱摘要复制到剪贴板。 */
  const shareGraph = async () => {
    const m = ctx.currentModel();
    const top = ctx.nodes.value.slice(0, 10).map(n => `· ${n.label}(${n.type})`).join('\n');
    const summary = `${m?.title || '本体模型'}\n节点 ${ctx.nodes.value.length} · 关系 ${ctx.edges.value.length}\n${top}`;
    try {
      await navigator.clipboard.writeText(summary);
      toast.success('图谱摘要已复制到剪贴板');
    } catch {
      toast.warn('剪贴板不可用,已输出到控制台');
      console.info('[shareGraph]\n' + summary);
    }
  };

  return { autoLayout, placeIncomingNodes, exportGraph, shareGraph };
}
