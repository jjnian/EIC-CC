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

/**
 * 图谱顶栏的三个动作:auto-layout、exportGraph、shareGraph。
 */
export function useGraphActions(ctx: GraphActionsCtx) {
  /** 分层布局:BFS 求最短层级 → barycenter 排序 → 紧凑列布局。 */
  const autoLayout = () => {
    if (ctx.nodes.value.length === 0) return;

    const hasIncoming = new Set(ctx.edges.value.map(e => e.to));
    const roots = ctx.nodes.value.filter(n => !hasIncoming.has(n.id));

    const levels: Record<string, number> = {};
    ctx.nodes.value.forEach(n => { levels[n.id] = Infinity; });
    roots.forEach(r => { levels[r.id] = 0; });

    const queue = roots.map(r => r.id);
    while (queue.length > 0) {
      const current = queue.shift()!;
      const currentLvl = levels[current];
      ctx.edges.value.filter(e => e.from === current).forEach(e => {
        if (levels[e.to] === Infinity) {
          levels[e.to] = currentLvl + 1;
          queue.push(e.to);
        }
      });
    }
    ctx.nodes.value.forEach(n => {
      if (levels[n.id] === Infinity) levels[n.id] = 0;
    });

    const groups: Record<number, OntologyNode[]> = {};
    let maxLevel = 0;
    const isolated: OntologyNode[] = [];

    ctx.nodes.value.forEach(n => {
      const hasEdge = ctx.edges.value.some(e => e.from === n.id || e.to === n.id);
      if (!hasEdge) { isolated.push(n); return; }
      const lvl = levels[n.id];
      if (lvl > maxLevel) maxLevel = lvl;
      if (!groups[lvl]) groups[lvl] = [];
      groups[lvl].push(n);
    });

    for (let lvl = 1; lvl <= maxLevel; lvl++) {
      const group = groups[lvl];
      if (!group || group.length <= 1) continue;
      group.sort((a, b) => {
        const avgParentY = (id: string) => {
          const parents = ctx.edges.value.filter(e => e.to === id).map(e => e.from);
          if (!parents.length) return Infinity;
          return parents.reduce((s: number, p: string) => {
            const pg = groups[levels[p]];
            return s + (pg ? pg.findIndex(n => n.id === p) : 0);
          }, 0) / parents.length;
        };
        return avgParentY(a.id) - avgParentY(b.id);
      });
    }

    const nodeW = 172, nodeH = 44;
    const xGap = 30;
    const yGap = 40;
    const xSpacing = nodeW + xGap;
    const ySpacing = nodeH + yGap;
    const startX = 60;
    const startY = 60;

    for (let lvl = 0; lvl <= maxLevel; lvl++) {
      const group = groups[lvl];
      if (!group) continue;
      group.forEach((n, idx) => {
        n.x = startX + lvl * xSpacing;
        n.y = startY + idx * ySpacing;
      });
    }

    if (isolated.length > 0) {
      isolated.forEach((n, idx) => {
        n.x = startX + (maxLevel + 1) * xSpacing;
        n.y = startY + idx * ySpacing;
      });
    }

    setTimeout(() => ctx.fitView?.(), 50);
    ctx.persist();
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

  return { autoLayout, exportGraph, shareGraph };
}
