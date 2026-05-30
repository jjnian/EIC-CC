import { ref, computed } from 'vue';
import type { OntologyNode } from '../types';
import type { ExtractedNode, ExtractedEdge } from './useExtractStream';

export interface ImportDedupOpts {
  /** 原始抽取结果(getter,随 useExtractStream 的 extractedRaw 变化)。 */
  getRaw: () => { nodes: ExtractedNode[]; edges: ExtractedEdge[] } | null;
  /** 当前合并模式(getter)。 */
  getMode: () => 'merge' | 'new';
  /** 当前图谱节点(getter,用于 label 去重对照)。 */
  getCurrentNodes: () => OntologyNode[];
}

/**
 * 文档导入对话框的"抽取结果去重对照与勾选"部分。
 * merge 模式下把与当前图谱 label 命中的节点过滤出去并自动重定向关系端点。
 */
export function useImportDedup(opts: ImportDedupOpts) {
  const selectedNodeIds = ref<Set<string>>(new Set());
  const selectedEdgeIds = ref<Set<string>>(new Set());

  const normLabel = (s: string) => (s || '').trim().toLowerCase().replace(/\s+/g, ' ');

  // 已有图谱标签 → {id,label} 索引（mode 切换时自动失效）
  const existingLabelMap = computed(() => {
    const m = new Map<string, { id: string; label: string }>();
    if (opts.getMode() !== 'merge') return m;
    for (const n of opts.getCurrentNodes()) {
      const k = normLabel(n.label || '');
      if (k) m.set(k, { id: n.id, label: n.label });
    }
    return m;
  });

  // dup 映射：仅 merge 模式下生效。
  const dupRemap = computed<Record<string, string>>(() => {
    const out: Record<string, string> = {};
    const raw = opts.getRaw();
    if (!raw || opts.getMode() !== 'merge') return out;
    for (const n of raw.nodes) {
      const k = normLabel(n.label || '');
      const hit = k ? existingLabelMap.value.get(k) : null;
      if (hit) out[n.id] = hit.id;
    }
    return out;
  });

  const dupList = computed(() => {
    const out: { extractedId: string; extractedLabel: string; existingLabel: string }[] = [];
    const raw = opts.getRaw();
    if (!raw || opts.getMode() !== 'merge') return out;
    for (const n of raw.nodes) {
      const k = normLabel(n.label || '');
      const hit = k ? existingLabelMap.value.get(k) : null;
      if (hit) out.push({ extractedId: n.id, extractedLabel: n.label, existingLabel: hit.label });
    }
    return out;
  });

  // 节点显示列表：merge 模式下过滤掉 dup（在原始数据上派生）
  const displayedNodes = computed<ExtractedNode[]>(() => {
    const raw = opts.getRaw();
    if (!raw) return [];
    if (opts.getMode() !== 'merge') return raw.nodes;
    const dup = dupRemap.value;
    return raw.nodes.filter(n => !(n.id in dup));
  });

  const displayedEdges = computed<ExtractedEdge[]>(() => {
    const raw = opts.getRaw();
    return raw ? raw.edges : [];
  });

  const isExistingId = (id: string) => !!opts.getCurrentNodes().some(n => n.id === id);

  const toggleNode = (id: string) => {
    const s = new Set(selectedNodeIds.value);
    if (s.has(id)) s.delete(id); else s.add(id);
    selectedNodeIds.value = s;
  };
  const toggleEdge = (id: string) => {
    const s = new Set(selectedEdgeIds.value);
    if (s.has(id)) s.delete(id); else s.add(id);
    selectedEdgeIds.value = s;
  };
  const toggleAllNodes = () => {
    const all = displayedNodes.value.map(n => n.id);
    const cur = selectedNodeIds.value;
    const allSelected = all.length > 0 && all.every(id => cur.has(id));
    selectedNodeIds.value = allSelected ? new Set() : new Set(all);
  };
  const toggleAllEdges = () => {
    const all = displayedEdges.value.map(e => e.id);
    const cur = selectedEdgeIds.value;
    const allSelected = all.length > 0 && all.every(id => cur.has(id));
    selectedEdgeIds.value = allSelected ? new Set() : new Set(all);
  };

  // 让可见节点/边重新进入全选状态（抽取完成、或 dup 过滤变化后调用）
  const selectAll = () => {
    selectedNodeIds.value = new Set(displayedNodes.value.map(n => n.id));
    selectedEdgeIds.value = new Set(displayedEdges.value.map(e => e.id));
  };

  const clearSelection = () => {
    selectedNodeIds.value = new Set();
    selectedEdgeIds.value = new Set();
  };

  const validEdges = computed(() => {
    const raw = opts.getRaw();
    if (!raw) return [];
    const dup = dupRemap.value;
    // 仅保留勾选的边；端点用 orig id 判断是否选中，再 apply remap，最终判端点是否落到已存在节点上
    return raw.edges
      .filter(e => selectedEdgeIds.value.has(e.id))
      .map(e => {
        const fromMapped = dup[e.from] || e.from;
        const toMapped = dup[e.to] || e.to;
        return { ...e, from: fromMapped, to: toMapped, _origFrom: e.from, _origTo: e.to };
      })
      .filter(e =>
        (selectedNodeIds.value.has(e._origFrom) || isExistingId(e.from)) &&
        (selectedNodeIds.value.has(e._origTo) || isExistingId(e.to)))
      .map(({ _origFrom, _origTo, ...rest }) => rest as ExtractedEdge);
  });

  const selectedNodes = computed(() =>
    displayedNodes.value.filter(n => selectedNodeIds.value.has(n.id)));

  return {
    selectedNodeIds, selectedEdgeIds,
    normLabel,
    existingLabelMap, dupRemap, dupList,
    displayedNodes, displayedEdges,
    isExistingId,
    toggleNode, toggleEdge, toggleAllNodes, toggleAllEdges,
    selectAll, clearSelection,
    validEdges, selectedNodes,
  };
}
