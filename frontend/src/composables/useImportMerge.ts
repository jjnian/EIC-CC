import { type Ref, nextTick } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { toast } from './useToast';

export interface ImportMergeCtx {
  nodes: Ref<OntologyNode[]>;
  edges: Ref<OntologyEdge[]>;
  snapshotHistory: () => void;
  placeIncomingNodes: (newNodes: OntologyNode[]) => void;
  autoLayout: () => void;
  persist: (immediate?: boolean) => void;
}

/**
 * "把 LLM/导入返回的 nodes+edges 合并到当前画布" 这条流程。
 * 包含两层去重（id 命中 / label+type 命中）+ edge 端点重映射 +
 * 重复信息 toast + 自动布局触发。
 *
 * 独立出来便于 ChatPanel 流式回写、ImportDialog 提交、外部脚本注入复用同一套合并逻辑。
 */
export function useImportMerge(ctx: ImportMergeCtx) {

  const dedupeIncoming = (addNodes: OntologyNode[], addEdges: OntologyEdge[]) => {
    const norm = (s?: string) => (s || '').trim().toLowerCase();
    const byId = new Map(ctx.nodes.value.map(n => [n.id, n]));
    const byKey = new Map<string, OntologyNode>();
    ctx.nodes.value.forEach(n => byKey.set(norm(n.label) + '|' + norm(n.type), n));

    const idRemap: Record<string, string> = {};
    const acceptedNodes: OntologyNode[] = [];
    for (const n of addNodes) {
      if (!n || !n.id) continue;
      if (byId.has(n.id)) { idRemap[n.id] = n.id; continue; }
      const k = norm(n.label) + '|' + norm(n.type);
      const hit = byKey.get(k);
      if (hit) { idRemap[n.id] = hit.id; continue; }
      acceptedNodes.push(n);
      byId.set(n.id, n);
      byKey.set(k, n);
    }

    const edgeKey = new Set(ctx.edges.value.map(e => e.from + '→' + e.to + '|' + norm(e.label)));
    const edgeIdSet = new Set(ctx.edges.value.map(e => e.id));
    const acceptedEdges: OntologyEdge[] = [];
    for (const e of addEdges) {
      if (!e) continue;
      const from = idRemap[e.from] || e.from;
      const to = idRemap[e.to] || e.to;
      if (!byId.has(from) || !byId.has(to)) continue;
      const k = from + '→' + to + '|' + norm(e.label);
      if (edgeKey.has(k)) continue;
      let id = e.id;
      if (!id || edgeIdSet.has(id)) id = 'e_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 7);
      acceptedEdges.push({ ...e, id, from, to });
      edgeKey.add(k);
      edgeIdSet.add(id);
    }

    return { nodes: acceptedNodes, edges: acceptedEdges, skipped: {
      nodes: addNodes.length - acceptedNodes.length,
      edges: addEdges.length - acceptedEdges.length,
    }};
  };

  const onUpdate = (addNodes: OntologyNode[], addEdges: OntologyEdge[]) => {
    const { nodes: newNodes, edges: newEdges, skipped } = dedupeIncoming(addNodes, addEdges);
    if (newNodes.length === 0 && newEdges.length === 0) {
      if (skipped.nodes || skipped.edges) {
        toast.info(`已忽略 ${skipped.nodes} 个重复节点 / ${skipped.edges} 条重复关系`);
      }
      return;
    }

    ctx.snapshotHistory();

    const existingIds = new Set(ctx.nodes.value.map(n => n.id));
    const connectsToExisting = newEdges.some(e => existingIds.has(e.from) || existingIds.has(e.to));

    ctx.placeIncomingNodes(newNodes);

    ctx.nodes.value.push(...newNodes.map(n => ({ ...n, isNew: true })));
    ctx.edges.value.push(...newEdges);
    setTimeout(() => {
      ctx.nodes.value.forEach(n => n.isNew = false);
    }, 800);
    ctx.persist();

    if (skipped.nodes || skipped.edges) {
      toast.info(`已合并:+${newNodes.length} 节点 / +${newEdges.length} 关系,跳过 ${skipped.nodes}/${skipped.edges} 个重复项`);
    }

    // 新节点接上了现有血缘，或图本身还很小时，自动跑一次分层布局
    const shouldAutoLayout =
      newNodes.length > 0 && (connectsToExisting || ctx.nodes.value.length <= 12);
    if (shouldAutoLayout) {
      nextTick(() => ctx.autoLayout());
    }
  };

  return { onUpdate, dedupeIncoming };
}
