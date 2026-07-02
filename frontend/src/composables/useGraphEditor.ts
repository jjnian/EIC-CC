import { ref, computed, type Ref, type ComputedRef } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { confirm } from './useConfirm';

export interface GraphEditorCtx {
  nodes: Ref<OntologyNode[]>;
  edges: Ref<OntologyEdge[]>;
  sel: Ref<string | null>;
  snapshotHistory: () => void;
  persist: (immediate?: boolean) => void;
}

/** 单个节点/关系的编辑器状态机 + 命令；把 App.vue 里大段对话框逻辑收敛在此。 */
export function useGraphEditor(ctx: GraphEditorCtx) {
  const genId = (prefix: string) =>
    prefix + '_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 7);

  // ===== 在画布空白处右键添加对象节点 =====
  const addNodeAtPosition = (payload: { mode: 'object'; label: string; x: number; y: number; inputs: { nodeId: string; edgeLabel: string }[]; outputs: { nodeId: string; edgeLabel: string }[] }) => {
    ctx.snapshotHistory();
    const nodeId = genId('n');
    const newNode: OntologyNode = { id: nodeId, label: payload.label, type: 'class', x: payload.x, y: payload.y, source: 'manual' };
    ctx.nodes.value.push(newNode);
    for (const inp of payload.inputs) {
      ctx.edges.value.push({ id: genId('e'), from: inp.nodeId, to: nodeId, label: inp.edgeLabel || undefined, rel_type: 'flows_to', source: 'manual' });
    }
    for (const out of payload.outputs) {
      ctx.edges.value.push({ id: genId('e'), from: nodeId, to: out.nodeId, label: out.edgeLabel || undefined, rel_type: 'flows_to', source: 'manual' });
    }
    ctx.sel.value = nodeId;
    ctx.persist();
  };

  // ===== 批量添加关系（笛卡尔积 inputs × outputs）=====
  const addEdgesBatch = (payload: { label: string; inputs: string[]; outputs: string[] }) => {
    ctx.snapshotHistory();
    for (const fromId of payload.inputs) {
      for (const toId of payload.outputs) {
        const exists = ctx.edges.value.some(e => e.from === fromId && e.to === toId && e.label === (payload.label || undefined));
        if (!exists) {
          ctx.edges.value.push({ id: genId('e'), from: fromId, to: toId, label: payload.label || undefined, rel_type: 'flows_to', source: 'manual' });
        }
      }
    }
    ctx.persist();
  };

  // ===== 关系编辑对话框 =====
  const editingRelation = ref<{
    label: string;
    originalLabel: string;
    inputs: string[];
    outputs: string[];
  } | null>(null);

  const openEditRelation = (edgeId: string) => {
    const edge = ctx.edges.value.find(e => e.id === edgeId);
    if (!edge) return;
    const lbl = edge.label || '';
    if (lbl) {
      const related = ctx.edges.value.filter(e => e.label === lbl);
      const inputs = [...new Set(related.map(e => e.from))];
      const outputs = [...new Set(related.map(e => e.to))];
      editingRelation.value = { label: lbl, originalLabel: lbl, inputs, outputs };
    } else {
      editingRelation.value = { label: '', originalLabel: '', inputs: [edge.from], outputs: [edge.to] };
    }
  };

  const toggleRelInput = (nodeId: string) => {
    if (!editingRelation.value) return;
    const idx = editingRelation.value.inputs.indexOf(nodeId);
    if (idx >= 0) {
      editingRelation.value.inputs.splice(idx, 1);
    } else {
      editingRelation.value.inputs.push(nodeId);
      const oi = editingRelation.value.outputs.indexOf(nodeId);
      if (oi >= 0) editingRelation.value.outputs.splice(oi, 1);
    }
  };

  const toggleRelOutput = (nodeId: string) => {
    if (!editingRelation.value) return;
    const idx = editingRelation.value.outputs.indexOf(nodeId);
    if (idx >= 0) {
      editingRelation.value.outputs.splice(idx, 1);
    } else {
      editingRelation.value.outputs.push(nodeId);
      const ii = editingRelation.value.inputs.indexOf(nodeId);
      if (ii >= 0) editingRelation.value.inputs.splice(ii, 1);
    }
  };

  const saveEditRelation = () => {
    if (!editingRelation.value) return;
    ctx.snapshotHistory();
    const { label, originalLabel, inputs, outputs } = editingRelation.value;
    const newLabel = label.trim() || undefined;

    if (originalLabel) {
      ctx.edges.value = ctx.edges.value.filter(e => e.label !== originalLabel);
    } else {
      ctx.edges.value = ctx.edges.value.filter(e => {
        if (e.label) return true;
        return !(editingRelation.value!.inputs.includes(e.from) && editingRelation.value!.outputs.includes(e.to));
      });
    }

    for (const fromId of inputs) {
      for (const toId of outputs) {
        ctx.edges.value.push({ id: genId('e'), from: fromId, to: toId, label: newLabel, source: 'manual' });
      }
    }

    editingRelation.value = null;
    ctx.persist();
  };

  const cancelEditRelation = () => {
    editingRelation.value = null;
  };

  const deleteEditingRelation = async () => {
    if (!editingRelation.value) return;
    const { originalLabel, inputs, outputs } = editingRelation.value;
    const displayName = originalLabel || '(未命名关系)';
    const ok = await confirm({
      title: '删除关系',
      message: `确定删除关系「${displayName}」？相关的所有边都会一并删除。`,
      danger: true,
      confirmLabel: '删除',
    });
    if (!ok) return;
    ctx.snapshotHistory();
    if (originalLabel) {
      ctx.edges.value = ctx.edges.value.filter(e => e.label !== originalLabel);
    } else {
      ctx.edges.value = ctx.edges.value.filter(e => {
        if (e.label) return true;
        return !(inputs.includes(e.from) && outputs.includes(e.to));
      });
    }
    editingRelation.value = null;
    ctx.persist();
  };

  const editRelGraphNodes: ComputedRef<OntologyNode[]> = computed(() => {
    return ctx.nodes.value.filter(n => n.type !== 'attribute' && n.type !== 'constraint');
  });

  // ===== 节点编辑对话框 =====
  const editingNode = ref<OntologyNode | null>(null);
  const editNodeInputs = ref<string[]>([]);
  const editNodeOutputs = ref<string[]>([]);
  const editInputLabels = ref<Record<string, string>>({});
  const editOutputLabels = ref<Record<string, string>>({});

  const SCHEMA_ONLY_TYPES = new Set(['attribute', 'constraint']);
  const editableGraphNodes: ComputedRef<OntologyNode[]> = computed(() => {
    if (!editingNode.value) return [];
    return ctx.nodes.value.filter(n =>
      n.id !== editingNode.value!.id && !SCHEMA_ONLY_TYPES.has(n.type)
    );
  });

  const openEditNode = (id: string) => {
    const n = ctx.nodes.value.find(n => n.id === id);
    if (!n) return;
    editingNode.value = { ...n };
    editNodeInputs.value = ctx.edges.value.filter(e => e.to === id).map(e => e.from);
    editNodeOutputs.value = ctx.edges.value.filter(e => e.from === id).map(e => e.to);
    editInputLabels.value = {};
    editOutputLabels.value = {};
    for (const e of ctx.edges.value) {
      if (e.to === id) editInputLabels.value[e.from] = e.label || '';
      if (e.from === id) editOutputLabels.value[e.to] = e.label || '';
    }
  };

  const toggleEditInput = (nodeId: string) => {
    const idx = editNodeInputs.value.indexOf(nodeId);
    if (idx >= 0) {
      editNodeInputs.value.splice(idx, 1);
      delete editInputLabels.value[nodeId];
    } else {
      editNodeInputs.value.push(nodeId);
      editInputLabels.value[nodeId] = '';
    }
  };

  const toggleEditOutput = (nodeId: string) => {
    const idx = editNodeOutputs.value.indexOf(nodeId);
    if (idx >= 0) {
      editNodeOutputs.value.splice(idx, 1);
      delete editOutputLabels.value[nodeId];
    } else {
      editNodeOutputs.value.push(nodeId);
      editOutputLabels.value[nodeId] = '';
    }
  };

  const saveEditNode = () => {
    if (!editingNode.value) return;
    const idx = ctx.nodes.value.findIndex(n => n.id === editingNode.value!.id);
    if (idx === -1) return;
    ctx.snapshotHistory();
    const nodeId = editingNode.value.id;
    ctx.nodes.value[idx] = { ...ctx.nodes.value[idx], label: editingNode.value.label, type: editingNode.value.type };

    ctx.edges.value = ctx.edges.value.filter(e => {
      if (e.to === nodeId && !editNodeInputs.value.includes(e.from)) return false;
      if (e.from === nodeId && !editNodeOutputs.value.includes(e.to)) return false;
      return true;
    });

    for (const e of ctx.edges.value) {
      if (e.to === nodeId && editInputLabels.value[e.from] !== undefined) {
        e.label = editInputLabels.value[e.from] || undefined;
      }
      if (e.from === nodeId && editOutputLabels.value[e.to] !== undefined) {
        e.label = editOutputLabels.value[e.to] || undefined;
      }
    }

    for (const fromId of editNodeInputs.value) {
      if (!ctx.edges.value.some(e => e.from === fromId && e.to === nodeId)) {
        ctx.edges.value.push({
          id: genId('e'),
          from: fromId, to: nodeId,
          label: editInputLabels.value[fromId] || undefined,
          source: 'manual',
        });
      }
    }

    for (const toId of editNodeOutputs.value) {
      if (!ctx.edges.value.some(e => e.from === nodeId && e.to === toId)) {
        ctx.edges.value.push({
          id: genId('e'),
          from: nodeId, to: toId,
          label: editOutputLabels.value[toId] || undefined,
          source: 'manual',
        });
      }
    }

    editingNode.value = null;
    ctx.persist();
  };

  const cancelEditNode = () => {
    editingNode.value = null;
  };

  // ===== 节点 props / schema =====
  const updateNodeProps = (id: string, newProps: { key: string; value: any; source?: string }[]) => {
    const n = ctx.nodes.value.find(n => n.id === id);
    if (!n) return;
    ctx.snapshotHistory();
    n.props = newProps;
    ctx.persist();
  };

  const updateNodeSchema = (id: string, patch: any) => {
    const i = ctx.nodes.value.findIndex(n => n.id === id);
    if (i === -1) return;
    ctx.snapshotHistory();
    ctx.nodes.value[i] = { ...ctx.nodes.value[i], ...patch };
    ctx.persist();
  };

  const updateEdgeSchema = (id: string, patch: any) => {
    const i = ctx.edges.value.findIndex(e => e.id === id);
    if (i === -1) return;
    ctx.snapshotHistory();
    ctx.edges.value[i] = { ...ctx.edges.value[i], ...patch };
    ctx.persist();
  };

  // ===== 删除：单边 / 整组关系 / 单节点 / 多节点 =====
  const deleteEdge = (edgeId: string) => {
    const idx = ctx.edges.value.findIndex(e => e.id === edgeId);
    if (idx === -1) return;
    ctx.snapshotHistory();
    ctx.edges.value.splice(idx, 1);
    ctx.persist();
  };

  const deleteRelation = async (edgeId: string) => {
    const edge = ctx.edges.value.find(e => e.id === edgeId);
    if (!edge) return;
    const lbl = edge.label || '';
    const targets = lbl ? ctx.edges.value.filter(e => e.label === lbl) : [edge];
    const displayName = lbl || '(未命名关系)';
    const ok = await confirm({
      title: '删除关系',
      message: `确定删除关系「${displayName}」？共有 ${targets.length} 条边会被一并删除。`,
      danger: true,
      confirmLabel: '删除',
    });
    if (!ok) return;
    ctx.snapshotHistory();
    const ids = new Set(targets.map(e => e.id));
    ctx.edges.value = ctx.edges.value.filter(e => !ids.has(e.id));
    ctx.persist();
  };

  const deleteNode = async (id: string) => {
    const n = ctx.nodes.value.find(n => n.id === id);
    if (!n) return;
    const ok = await confirm({
      title: '删除节点',
      message: `确定删除节点「${n.label}」？相关的关系也会一并删除。`,
      danger: true,
      confirmLabel: '删除',
    });
    if (!ok) return;
    ctx.snapshotHistory();
    ctx.nodes.value = ctx.nodes.value.filter(n => n.id !== id);
    ctx.edges.value = ctx.edges.value.filter(e => e.from !== id && e.to !== id);
    if (ctx.sel.value === id) ctx.sel.value = null;
    ctx.persist();
  };

  const deleteNodes = async (ids: string[]) => {
    if (!ids.length) return;
    const ok = await confirm({
      title: '批量删除',
      message: `确定删除选中的 ${ids.length} 个节点？相关关系也会一并删除。`,
      danger: true,
      confirmLabel: '删除',
    });
    if (!ok) return;
    ctx.snapshotHistory();
    const idSet = new Set(ids);
    ctx.nodes.value = ctx.nodes.value.filter(n => !idSet.has(n.id));
    ctx.edges.value = ctx.edges.value.filter(e => !idSet.has(e.from) && !idSet.has(e.to));
    if (ctx.sel.value && idSet.has(ctx.sel.value)) ctx.sel.value = null;
    ctx.persist();
  };

  const clearCanvas = () => {
    if (ctx.nodes.value.length === 0 && ctx.edges.value.length === 0) return;
    ctx.snapshotHistory();
    ctx.nodes.value = [];
    ctx.edges.value = [];
    ctx.sel.value = null;
    ctx.persist(true);
  };

  return {
    addNodeAtPosition,
    addEdgesBatch,
    editingRelation,
    openEditRelation,
    toggleRelInput,
    toggleRelOutput,
    saveEditRelation,
    cancelEditRelation,
    deleteEditingRelation,
    editRelGraphNodes,
    editingNode,
    editNodeInputs,
    editNodeOutputs,
    editInputLabels,
    editOutputLabels,
    editableGraphNodes,
    openEditNode,
    toggleEditInput,
    toggleEditOutput,
    saveEditNode,
    cancelEditNode,
    updateNodeProps,
    updateNodeSchema,
    updateEdgeSchema,
    deleteEdge,
    deleteRelation,
    deleteNode,
    deleteNodes,
    clearCanvas,
  };
}
