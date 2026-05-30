import { ref } from 'vue';
import type { OntologyNode } from '../types';

export interface NodeDragCtx {
  getZoom: () => number;
  getNmap: () => Record<string, OntologyNode>;
  /** 多选集合(reactive Set 引用,保持响应式) */
  multiSel: Set<string>;
  getReadonly: () => boolean | undefined;
  setAutoFit: (v: boolean) => void;
  emitSelect: (id: string | null) => void;
  emitMove: (id: string, x: number, y: number) => void;
  emitDragStart: (id: string) => void;
}

/**
 * 节点拖拽:含 Ctrl+点击切换多选、批量拖拽。drag 状态留在此处,
 * window mousemove/mouseup 由组件统一分派到 handleMouseMove / end。
 */
export function useNodeDrag(ctx: NodeDragCtx) {
  // 节点拖拽状态:含起始坐标、起始鼠标位置、是否触发批量等
  const drag = ref<any>(null);

  const startDrag = (e: MouseEvent, id: string) => {
    e.stopPropagation();

    if (e.ctrlKey || e.metaKey) {
      // Ctrl + 点击:切换该节点在多选集合中的状态
      if (ctx.multiSel.has(id)) {
        ctx.multiSel.delete(id);
      } else {
        ctx.multiSel.add(id);
      }
      ctx.emitSelect(id);
      return;
    }

    // 非 Ctrl 点击:如果点的是多选集中的节点,保留多选准备批量拖拽
    if (!ctx.multiSel.has(id)) {
      ctx.multiSel.clear(); // 点击未选中的节点,清空多选
    }

    ctx.emitSelect(id);
    if (ctx.getReadonly()) return;
    const nmap = ctx.getNmap();
    const n = nmap[id];
    if (n) {
      // 批量拖拽:当被拖节点在多选集合中且集合 >1,记录所有选中节点的初始坐标
      const batchOrigins = ctx.multiSel.has(id) && ctx.multiSel.size > 1
        ? Object.fromEntries([...ctx.multiSel].map(sid => [sid, { x: nmap[sid]?.x || 0, y: nmap[sid]?.y || 0 }]))
        : null;
      drag.value = { id, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y, moved: false, batchOrigins };
    }
  };

  /** window mousemove 中的拖拽分支。返回 true 表示已处理(应 return)。 */
  const handleMouseMove = (e: MouseEvent): boolean => {
    if (!drag.value) return false;
    const { id, sx, sy, ox, oy, batchOrigins } = drag.value;
    // 把屏幕位移换算到内容坐标系
    const dx = (e.clientX - sx) / ctx.getZoom();
    const dy = (e.clientY - sy) / ctx.getZoom();

    // 超过 2px 才视为真正的拖拽,否则当成点击;避免选中态被微抖动覆盖
    if (!drag.value.moved && (Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy)) > 2) {
      drag.value.moved = true;
      ctx.emitDragStart(id);
    }

    // 批量移动:所有被选节点应用相同位移;单选则只移动当前节点
    if (batchOrigins) {
      for (const [selId, origin] of Object.entries(batchOrigins) as [string, { x: number; y: number }][]) {
        ctx.emitMove(selId, Math.max(0, origin.x + dx), Math.max(0, origin.y + dy));
      }
    } else {
      ctx.emitMove(id, Math.max(0, ox + dx), Math.max(0, oy + dy));
    }
    ctx.setAutoFit(false);
    return true;
  };

  /** mouseup 时结束拖拽。 */
  const end = () => {
    drag.value = null;
  };

  return {
    drag,
    startDrag,
    handleMouseMove,
    end,
  };
}
