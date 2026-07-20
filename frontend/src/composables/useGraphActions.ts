import { ref, type Ref } from 'vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from '../types';
import { toast } from './useToast';

export type LayoutDirection = 'LR' | 'TB';

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
const ORIGIN_X = 80;
const ORIGIN_Y = 80;

// 层与层之间(沿因果流向)的间距 / 同层节点(垂直于流向)的间距。
// LR:水平=层间,垂直=层内;TB:垂直=层间,水平=层内。
const LR_LAYER_GAP = NODE_W + 90; // 列间
const LR_INTRA_GAP = NODE_H + 28; // 同列内
const TB_LAYER_GAP = NODE_H + 80; // 行间
const TB_INTRA_GAP = NODE_W + 40; // 同行内

/**
 * 图谱顶栏的动作:auto-layout、exportGraph 等。
 */
export function useGraphActions(ctx: GraphActionsCtx) {
  /** 当前布局方向:LR(左→右,默认)或 TB(上→下)。 */
  const layoutDirection = ref<LayoutDirection>('LR');

  /**
   * 层次分层布局(BFS 风格):
   *   1. 检测 DAG 中的回边,布局时忽略掉(避免无限层级)。
   *   2. 多源 BFS 给每个节点定层 lvl = 到最近根节点的距离。
   *      这样「同一父辈下的子节点」都恰好下沉一层,不会因为某条迂回的长路径被推到更深层。
   *   3. 多轮 barycenter 排序减少线交叉。
   *   4. 每一层垂直于流向方向居中,整体观感平衡。
   *   5. 孤立节点放到末尾,按网格平铺。
   *   6. 方向由 layoutDirection 控制:LR = 横向流动;TB = 纵向流动。
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

    // 3. 多源 BFS 分层:层级 = 距最近根节点(无有效父节点)的最短距离。
    //    这样一个节点不会因为还有一条更长的间接路径就被推到更深的层。
    const levels: Record<string, number> = {};
    const queue: string[] = [];
    for (const n of nodes) {
      if (effectiveParents(n.id).length === 0) {
        levels[n.id] = 0;
        queue.push(n.id);
      }
    }
    while (queue.length > 0) {
      const id = queue.shift()!;
      const lvl = levels[id];
      for (const child of effectiveChildren(id)) {
        if (!(child in levels)) {
          levels[child] = lvl + 1;
          queue.push(child);
        }
      }
    }
    // 兜底:若仍有节点未被覆盖(可能在反向边剥离后形成孤岛),用入度回退按最长路径补一遍。
    const fallbackLevel = (id: string, stack: Set<string>): number => {
      if (id in levels) return levels[id];
      if (stack.has(id)) return 0;
      stack.add(id);
      let max = 0;
      for (const parent of effectiveParents(id)) {
        max = Math.max(max, fallbackLevel(parent, stack) + 1);
      }
      stack.delete(id);
      levels[id] = max;
      return max;
    };
    nodes.forEach(n => { if (!(n.id in levels)) fallbackLevel(n.id, new Set()); });

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

    // 6. 计算每层最大节点数,沿"垂直于流向"方向居中
    let maxLayerCount = 1;
    for (let lvl = 0; lvl <= maxLevel; lvl++) {
      const g = groups[lvl];
      if (g && g.length > maxLayerCount) maxLayerCount = g.length;
    }
    const lr = layoutDirection.value === 'LR';
    const layerGap = lr ? LR_LAYER_GAP : TB_LAYER_GAP;
    const intraGap = lr ? LR_INTRA_GAP : TB_INTRA_GAP;
    const totalIntra = (maxLayerCount - 1) * intraGap;

    for (let lvl = 0; lvl <= maxLevel; lvl++) {
      const group = groups[lvl];
      if (!group) continue;
      const groupIntra = (group.length - 1) * intraGap;
      const offset = (totalIntra - groupIntra) / 2;
      group.forEach((n, idx) => {
        if (lr) {
          n.x = ORIGIN_X + lvl * layerGap;
          n.y = ORIGIN_Y + offset + idx * intraGap;
        } else {
          n.x = ORIGIN_X + offset + idx * intraGap;
          n.y = ORIGIN_Y + lvl * layerGap;
        }
      });
    }

    // 7. 孤立节点:摆在主图之后,按网格平铺
    if (isolated.length > 0) {
      const cols = Math.min(4, isolated.length);
      if (lr) {
        const isoStartY = ORIGIN_Y + totalIntra + intraGap * 2;
        isolated.forEach((n, idx) => {
          n.x = ORIGIN_X + (idx % cols) * (NODE_W + 90);
          n.y = isoStartY + Math.floor(idx / cols) * (NODE_H + 28);
        });
      } else {
        const isoStartX = ORIGIN_X + totalIntra + intraGap * 2;
        isolated.forEach((n, idx) => {
          n.x = isoStartX + (idx % cols) * (NODE_W + 40);
          n.y = ORIGIN_Y + Math.floor(idx / cols) * (NODE_H + 28);
        });
      }
    }

    setTimeout(() => ctx.fitView?.(), 50);
    ctx.persist();
  };

  /** 切换布局方向并立即重新布局。 */
  const toggleLayoutDirection = () => {
    layoutDirection.value = layoutDirection.value === 'LR' ? 'TB' : 'LR';
    autoLayout();
    toast.success(layoutDirection.value === 'LR' ? '已切换为从左向右布局' : '已切换为从上到下布局');
  };

  /**
   * 给一批"刚从对话中提取出来"的新节点选一个合理初始位置:
   * 若画布已经有节点,放到现有图沿当前方向"后面"的暂存区;否则原点处摆开。
   * 实际美化由后续 autoLayout 完成。
   */
  const placeIncomingNodes = (newNodes: OntologyNode[]) => {
    if (newNodes.length === 0) return;
    const lr = layoutDirection.value === 'LR';
    const layerGap = lr ? LR_LAYER_GAP : TB_LAYER_GAP;
    const intraGap = lr ? LR_INTRA_GAP : TB_INTRA_GAP;
    const existing = ctx.nodes.value;
    if (existing.length === 0) {
      const cols = 4;
      newNodes.forEach((n, idx) => {
        if (lr) {
          n.x = ORIGIN_X + (idx % cols) * layerGap;
          n.y = ORIGIN_Y + Math.floor(idx / cols) * intraGap;
        } else {
          n.x = ORIGIN_X + (idx % cols) * intraGap;
          n.y = ORIGIN_Y + Math.floor(idx / cols) * layerGap;
        }
      });
      return;
    }
    let maxX = -Infinity, maxY = -Infinity;
    let minX = Infinity, minY = Infinity;
    for (const n of existing) {
      if (n.x > maxX) maxX = n.x;
      if (n.y > maxY) maxY = n.y;
      if (n.x < minX) minX = n.x;
      if (n.y < minY) minY = n.y;
    }
    if (lr) {
      const baseX = maxX + layerGap;
      const baseY = minY;
      const cols = Math.max(1, Math.min(2, Math.ceil(newNodes.length / 6)));
      newNodes.forEach((n, idx) => {
        n.x = baseX + (idx % cols) * layerGap;
        n.y = baseY + Math.floor(idx / cols) * intraGap;
      });
    } else {
      const baseY = maxY + layerGap;
      const baseX = minX;
      const rows = Math.max(1, Math.min(2, Math.ceil(newNodes.length / 6)));
      newNodes.forEach((n, idx) => {
        n.x = baseX + Math.floor(idx / rows) * intraGap;
        n.y = baseY + (idx % rows) * layerGap;
      });
    }
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

  /** 导出为 Mermaid 语法(.mmd 文件)。 */
  const exportMermaid = () => {
    const lines: string[] = ['graph LR'];
    const sanitize = (s: string) => s.replace(/["\[\](){}]/g, '').replace(/\s+/g, '_');

    for (const n of ctx.nodes.value) {
      const shape = n.type === 'relation_type' ? `((${n.label}))`
        : n.type === 'attribute' ? `[/${n.label}/]`
        : n.type === 'constraint' ? `{{${n.label}}}`
        : `[${n.label}]`;
      lines.push(`  ${sanitize(n.id)}${shape}`);
    }
    for (const e of ctx.edges.value) {
      const label = e.label ? `|${e.label}|` : '';
      lines.push(`  ${sanitize(e.from)} -->${label} ${sanitize(e.to)}`);
    }

    const content = lines.join('\n');
    const blob = new Blob([content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'graph.mmd';
    a.click();
    URL.revokeObjectURL(url);
  };

  /** 导出为 Markdown 报告(.md 文件)。 */
  const exportMarkdown = () => {
    const lines: string[] = [];
    lines.push('# 本体图谱报告\n');
    lines.push(`> 导出时间: ${new Date().toLocaleString()}\n`);
    lines.push(`## 概览\n`);
    lines.push(`- 节点数: ${ctx.nodes.value.length}`);
    lines.push(`- 关系数: ${ctx.edges.value.length}\n`);

    // 按类型分组节点
    const groups: Record<string, OntologyNode[]> = {};
    for (const n of ctx.nodes.value) {
      const t = n.type || 'unknown';
      if (!groups[t]) groups[t] = [];
      groups[t].push(n);
    }

    lines.push('## 节点列表\n');
    for (const [type, groupNodes] of Object.entries(groups)) {
      lines.push(`### ${type} (${groupNodes.length})\n`);
      lines.push('| ID | 名称 | 来源 |');
      lines.push('|---|---|---|');
      for (const n of groupNodes) {
        lines.push(`| ${n.id} | ${n.label} | ${n.source || '-'} |`);
      }
      lines.push('');
    }

    lines.push('## 关系列表\n');
    lines.push('| 起点 | 关系 | 终点 | 规则驱动 |');
    lines.push('|---|---|---|---|');
    const nmap = Object.fromEntries(ctx.nodes.value.map(n => [n.id, n.label]));
    for (const e of ctx.edges.value) {
      lines.push(`| ${nmap[e.from] || e.from} | ${e.label || '-'} | ${nmap[e.to] || e.to} | ${e.rule_driven ? '是' : '-'} |`);
    }

    const content = lines.join('\n');
    const blob = new Blob([content], { type: 'text/markdown' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'graph-report.md';
    a.click();
    URL.revokeObjectURL(url);
  };

  /** 导出为 PNG 截图(Canvas 2D 手绘简化版图谱)。 */
  const exportPng = async () => {
    const el = document.querySelector('.graph-canvas') as HTMLElement;
    if (!el) return;

    try {
      // 使用 Canvas 2D 手绘简化版图谱
      const canvas = document.createElement('canvas');
      const padding = 40;
      const nodeW = 160;
      const nodeH = 52;
      const minX = Math.min(...ctx.nodes.value.map(n => n.x || 0));
      const minY = Math.min(...ctx.nodes.value.map(n => n.y || 0));
      const maxX = Math.max(...ctx.nodes.value.map(n => (n.x || 0) + nodeW));
      const maxY = Math.max(...ctx.nodes.value.map(n => (n.y || 0) + nodeH));

      canvas.width = (maxX - minX) + padding * 2;
      canvas.height = (maxY - minY) + padding * 2;
      const c = canvas.getContext('2d')!;

      // 背景
      c.fillStyle = '#ffffff';
      c.fillRect(0, 0, canvas.width, canvas.height);

      const ox = -minX + padding;
      const oy = -minY + padding;

      // 画边
      c.strokeStyle = '#a1a1aa';
      c.lineWidth = 1.5;
      const nodeMap = Object.fromEntries(ctx.nodes.value.map(n => [n.id, n]));
      for (const e of ctx.edges.value) {
        const fn = nodeMap[e.from];
        const tn = nodeMap[e.to];
        if (!fn || !tn) continue;
        c.beginPath();
        c.moveTo((fn.x || 0) + nodeW + ox, (fn.y || 0) + 26 + oy);
        c.lineTo((tn.x || 0) + ox, (tn.y || 0) + 26 + oy);
        c.stroke();
      }

      // 画节点
      for (const n of ctx.nodes.value) {
        const x = (n.x || 0) + ox;
        const y = (n.y || 0) + oy;
        c.fillStyle = '#ffffff';
        c.strokeStyle = 'rgba(0,0,0,0.18)';
        c.lineWidth = 1;
        c.beginPath();
        c.roundRect(x, y, nodeW, nodeH, 8);
        c.fill();
        c.stroke();
        c.fillStyle = '#18181b';
        c.font = '13px sans-serif';
        c.fillText(n.label || '', x + 12, y + 30, 136);
      }

      // 下载
      canvas.toBlob(blob => {
        if (!blob) return;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'graph.png';
        a.click();
        URL.revokeObjectURL(url);
      }, 'image/png');
    } catch (e) {
      console.error('PNG export failed', e);
    }
  };

  return { autoLayout, toggleLayoutDirection, layoutDirection, placeIncomingNodes, exportGraph, exportMermaid, exportMarkdown, exportPng };
}
