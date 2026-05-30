import { ref, computed, nextTick } from 'vue';
import type { OntologyNode } from '../types';

export interface CanvasContextMenuCtx {
  getZoom: () => number;
  getCvEl: () => HTMLElement | null;
  getNodes: () => OntologyNode[];
  /** 多选集合(reactive Set 引用,保持响应式) */
  multiSel: Set<string>;
  getReadonly: () => boolean | undefined;
  emitSelect: (id: string | null) => void;
  emitPredictFrom: (id: string) => void;
  emitEditNode: (id: string) => void;
  emitExplainNode: (id: string) => void;
  emitDeleteNode: (id: string) => void;
  emitDeleteNodes: (ids: string[]) => void;
  emitAddNode: (payload: { mode: 'object'; label: string; x: number; y: number; inputs: { nodeId: string; edgeLabel: string }[]; outputs: { nodeId: string; edgeLabel: string }[] }) => void;
  emitAddEdges: (payload: { label: string; inputs: string[]; outputs: string[] }) => void;
}

/**
 * 画布右键菜单 + 添加对象/关系表单:节点右键菜单、画布空白处右键弹出添加表单、
 * 各菜单项触发器。从 GraphCanvas.vue 抽出以降低主组件行数。
 */
export function useCanvasContextMenu(ctx: CanvasContextMenuCtx) {
  /* ── 右键菜单 ── */
  const ctxMenu = ref<{ x: number; y: number; id: string } | null>(null);
  /* ── 添加节点/关系表单 ── */
  const addNodeForm = ref<{
    mode: 'object' | 'relation';
    canvasX: number;
    canvasY: number;
    label: string;
    inputs: string[];
    outputs: string[];
    inputLabels: Record<string, string>;
    outputLabels: Record<string, string>;
  } | null>(null);
  const addNodeInputRef = ref<HTMLInputElement | null>(null);

  const onNodeContext = (e: MouseEvent, id: string) => {
    e.preventDefault();
    e.stopPropagation();
    if (ctx.getReadonly()) return;
    ctx.emitSelect(id);
    addNodeForm.value = null;
    ctxMenu.value = { x: e.clientX, y: e.clientY, id };
  };

  const onCanvasContext = (e: MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (ctx.getReadonly()) return;
    if ((e.target as HTMLElement).closest('.node') || (e.target as HTMLElement).closest('.hud-overlay')) return;
    ctxMenu.value = null;
    const cv = ctx.getCvEl()!;
    const rect = cv.getBoundingClientRect();
    const canvasX = (e.clientX - rect.left + cv.scrollLeft) / ctx.getZoom();
    const canvasY = (e.clientY - rect.top + cv.scrollTop) / ctx.getZoom();
    addNodeForm.value = {
      mode: 'object',
      canvasX, canvasY,
      label: '',
      inputs: [],
      outputs: [],
      inputLabels: {},
      outputLabels: {},
    };
    nextTick(() => addNodeInputRef.value?.focus());
  };

  const closeCtx = () => { ctxMenu.value = null; addNodeForm.value = null; };

  const triggerPredict = () => {
    if (ctxMenu.value) {
      ctx.emitPredictFrom(ctxMenu.value.id);
      ctxMenu.value = null;
    }
  };

  const triggerEdit = () => {
    if (ctxMenu.value) {
      ctx.emitEditNode(ctxMenu.value.id);
      ctxMenu.value = null;
    }
  };

  // P1-7:对预测节点请求详细解释(依据 / 假设 / 反例)
  const triggerExplain = () => {
    if (ctxMenu.value) {
      ctx.emitExplainNode(ctxMenu.value.id);
      ctxMenu.value = null;
    }
  };

  /** 当前右键菜单作用的节点是否为推演节点(用于决定是否显示"为什么"项)。 */
  const ctxNodeIsPredicted = computed(() => {
    if (!ctxMenu.value) return false;
    const node = ctx.getNodes().find(n => n.id === ctxMenu.value!.id);
    return node?.source === 'predicted';
  });

  const triggerDelete = () => {
    if (ctxMenu.value) {
      ctx.emitDeleteNode(ctxMenu.value.id);
      ctxMenu.value = null;
    }
  };

  const triggerBatchDelete = () => {
    if (ctx.multiSel.size > 0) {
      ctx.emitDeleteNodes([...ctx.multiSel]);
      ctx.multiSel.clear();
      ctxMenu.value = null;
    }
  };

  const submitAddNode = () => {
    if (!addNodeForm.value) return;
    const f = addNodeForm.value;
    if (f.mode === 'object') {
      if (!f.label.trim()) return;
      ctx.emitAddNode({
        mode: 'object',
        label: f.label.trim(),
        x: f.canvasX,
        y: f.canvasY,
        inputs: f.inputs.map(id => ({ nodeId: id, edgeLabel: f.inputLabels[id] || '' })),
        outputs: f.outputs.map(id => ({ nodeId: id, edgeLabel: f.outputLabels[id] || '' })),
      });
    } else {
      if (f.inputs.length === 0 || f.outputs.length === 0) return;
      ctx.emitAddEdges({
        label: f.label.trim(),
        inputs: f.inputs,
        outputs: f.outputs,
      });
    }
    addNodeForm.value = null;
  };

  const toggleAddNodeInput = (nodeId: string) => {
    if (!addNodeForm.value) return;
    const idx = addNodeForm.value.inputs.indexOf(nodeId);
    if (idx >= 0) {
      addNodeForm.value.inputs.splice(idx, 1);
      delete addNodeForm.value.inputLabels[nodeId];
    } else {
      addNodeForm.value.inputs.push(nodeId);
      addNodeForm.value.inputLabels[nodeId] = '';
    }
  };

  const toggleAddNodeOutput = (nodeId: string) => {
    if (!addNodeForm.value) return;
    const idx = addNodeForm.value.outputs.indexOf(nodeId);
    if (idx >= 0) {
      addNodeForm.value.outputs.splice(idx, 1);
      delete addNodeForm.value.outputLabels[nodeId];
    } else {
      addNodeForm.value.outputs.push(nodeId);
      addNodeForm.value.outputLabels[nodeId] = '';
    }
  };

  return {
    ctxMenu,
    addNodeForm,
    addNodeInputRef,
    onNodeContext,
    onCanvasContext,
    closeCtx,
    triggerPredict,
    triggerEdit,
    triggerExplain,
    ctxNodeIsPredicted,
    triggerDelete,
    triggerBatchDelete,
    submitAddNode,
    toggleAddNodeInput,
    toggleAddNodeOutput,
  };
}
