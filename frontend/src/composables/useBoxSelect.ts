import { ref } from 'vue';
import { NW, NH } from '../constants';
import type { OntologyNode } from '../types';

export interface BoxSelectCtx {
  getZoom: () => number;
  getCvEl: () => HTMLElement | null;
  /** 多选集合(reactive Set 引用,保持响应式) */
  multiSel: Set<string>;
  getNodes: () => OntologyNode[];
  getReadonly: () => boolean | undefined;
}

/**
 * 框选(Shift + 拖拽):boxSel 状态留在此处。
 * tryStart 在 startPan 入口判断是否进入框选;window mousemove/mouseup
 * 由组件统一分派到 handleMouseMove / handleMouseUp(均返回 boolean)。
 */
export function useBoxSelect(ctx: BoxSelectCtx) {
  // 框选状态:sx,sy 起点,cx,cy 当前点(画布坐标系,已除掉 zoom)
  const boxSel = ref<{ sx: number; sy: number; cx: number; cy: number } | null>(null);

  /**
   * 在画布空白处按下时尝试开始框选。
   * Shift + 拖拽且非只读时进入框选并返回 true;否则返回 false(交给平移)。
   */
  const tryStart = (e: MouseEvent): boolean => {
    // Shift + 拖拽:框选模式(只读时禁用)
    if (e.shiftKey && !ctx.getReadonly()) {
      const cv = ctx.getCvEl()!;
      const rect = cv.getBoundingClientRect();
      // 把客户端坐标换算到内容坐标系(含滚动 + zoom)
      const x = (e.clientX - rect.left + cv.scrollLeft) / ctx.getZoom();
      const y = (e.clientY - rect.top + cv.scrollTop) / ctx.getZoom();
      boxSel.value = { sx: x, sy: y, cx: x, cy: y };
      return true;
    }
    return false;
  };

  /** window mousemove 中的框选分支:实时更新当前角点。返回 true 表示已处理。 */
  const handleMouseMove = (e: MouseEvent): boolean => {
    // 框选拖拽中:实时更新当前角点,模板用它绘制虚线矩形
    if (!boxSel.value) return false;
    const cv = ctx.getCvEl()!;
    const rect = cv.getBoundingClientRect();
    const x = (e.clientX - rect.left + cv.scrollLeft) / ctx.getZoom();
    const y = (e.clientY - rect.top + cv.scrollTop) / ctx.getZoom();
    boxSel.value.cx = x;
    boxSel.value.cy = y;
    return true;
  };

  /** window mouseup 中的框选分支:把矩形内的节点全部加入 multiSel。返回 true 表示已处理。 */
  const handleMouseUp = (): boolean => {
    // 框选结束:把矩形内的节点全部加入 multiSel
    if (!boxSel.value) return false;
    const { sx, sy, cx, cy } = boxSel.value;
    const left = Math.min(sx, cx);
    const top = Math.min(sy, cy);
    const right = Math.max(sx, cx);
    const bottom = Math.max(sy, cy);

    ctx.multiSel.clear();
    for (const n of ctx.getNodes()) {
      // 节点矩形完全落在选框内才算选中(部分相交不计),避免误选
      const nx = n.x ?? 0;
      const ny = n.y ?? 0;
      if (nx >= left && nx + NW <= right && ny >= top && ny + NH <= bottom) {
        ctx.multiSel.add(n.id);
      }
    }
    boxSel.value = null;
    return true;
  };

  return {
    boxSel,
    tryStart,
    handleMouseMove,
    handleMouseUp,
  };
}
