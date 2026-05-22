import { ref, computed, type Ref } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';

export interface GraphSnapshot {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}

export interface GraphHistoryCtx {
  nodes: Ref<OntologyNode[]>;
  edges: Ref<OntologyEdge[]>;
  /** 撤销/重做触发后写入持久化(立即写,避免和防抖冲突)。 */
  persist?: (immediate?: boolean) => void;
}

const MAX_HISTORY = 50;

const clone = (s: GraphSnapshot): GraphSnapshot => ({
  nodes: s.nodes.map(n => ({ ...n, props: n.props ? n.props.map(p => ({ ...p })) : undefined,
                              properties: n.properties ? { ...n.properties } : undefined })),
  edges: s.edges.map(e => ({ ...e })),
});

const eqSnap = (a: GraphSnapshot, b: GraphSnapshot) => {
  if (a.nodes.length !== b.nodes.length || a.edges.length !== b.edges.length) return false;
  for (let i = 0; i < a.nodes.length; i++) {
    const x = a.nodes[i], y = b.nodes[i];
    if (x.id !== y.id || x.label !== y.label || x.type !== y.type || x.x !== y.x || x.y !== y.y) return false;
  }
  for (let i = 0; i < a.edges.length; i++) {
    const x = a.edges[i], y = b.edges[i];
    if (x.id !== y.id || x.from !== y.from || x.to !== y.to || x.label !== y.label) return false;
  }
  return true;
};

export function useGraphHistory(ctx: GraphHistoryCtx) {
  const stack = ref<GraphSnapshot[]>([]);
  const cursor = ref(-1);

  const canUndo = computed(() => cursor.value > 0);
  const canRedo = computed(() => cursor.value >= 0 && cursor.value < stack.value.length - 1);

  /** 把 nodes/edges 重置为初始快照(切模型/导入时调用)。 */
  const reset = (nodes?: OntologyNode[], edges?: OntologyEdge[]) => {
    const snap = clone({
      nodes: nodes ?? ctx.nodes.value,
      edges: edges ?? ctx.edges.value,
    });
    stack.value = [snap];
    cursor.value = 0;
  };

  /**
   * 把当前 nodes/edges 推一次快照。
   * 调用时机:任何"有意义的"用户改动之 *后*,例如:
   *   - LLM 增量更新成功
   *   - 清空画布
   *   - 自动布局
   *   - 拖拽结束
   *   - 导入
   * 如果当前游标不在末尾,会丢弃 redo 分支(经典 undo 语义)。
   */
  const snapshot = () => {
    const cur = clone({ nodes: ctx.nodes.value, edges: ctx.edges.value });
    if (cursor.value >= 0 && eqSnap(stack.value[cursor.value], cur)) return;
    // 丢掉 redo 分支
    if (cursor.value < stack.value.length - 1) {
      stack.value = stack.value.slice(0, cursor.value + 1);
    }
    stack.value.push(cur);
    if (stack.value.length > MAX_HISTORY) {
      stack.value = stack.value.slice(stack.value.length - MAX_HISTORY);
    }
    cursor.value = stack.value.length - 1;
  };

  const apply = (snap: GraphSnapshot) => {
    const c = clone(snap);
    ctx.nodes.value = c.nodes;
    ctx.edges.value = c.edges;
    ctx.persist?.(true);
  };

  const undo = () => {
    if (!canUndo.value) return false;
    cursor.value--;
    apply(stack.value[cursor.value]);
    return true;
  };

  const redo = () => {
    if (!canRedo.value) return false;
    cursor.value++;
    apply(stack.value[cursor.value]);
    return true;
  };

  return { snapshot, undo, redo, reset, canUndo, canRedo, stack, cursor };
}
