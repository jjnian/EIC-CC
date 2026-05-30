import { ref, computed, nextTick } from 'vue';
import { NT, NW } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

export interface CanvasViewportCtx {
  getNodes: () => OntologyNode[];
  getEdges: () => OntologyEdge[];
}

// TBox: attribute 和 constraint 这两类只在 Schema 面板里展示,不上图。
// 其它类型(class / 以及老数据里的 entity/process/event 等)统统当成"图上的节点"渲染,
// 这样既能展示静态本体的类,也不会让用户已有的非 class 数据突然全部消失。
const SCHEMA_ONLY_TYPES = new Set(['attribute', 'constraint']);

// 视口外扩裁剪边距,避免滚动时节点突然出现 / 消失造成视觉跳变
const CULL_MARGIN = 200;

/**
 * 画布视口与缩放:持有滚动容器 ref、zoom、自适应标志、平移状态,
 * 并负责视口裁剪(viewport culling)、fitView、滚轮缩放、节点聚焦、平移。
 * 从 GraphCanvas.vue 抽出来作为其它交互 composable 的"地基"。
 */
export function useCanvasViewport(ctx: CanvasViewportCtx) {
  /* ── 视口与缩放 ── */
  const cvRef = ref<HTMLElement | null>(null);
  const zoom = ref(1);
  // 画布平移状态:滚动条位置 + 鼠标起点
  const pan = ref<any>(null);
  // 是否处于"自适应屏幕"模式:true 时窗口尺寸变化会自动 fitView
  const isAutoFit = ref(true);

  /* ── 视口裁剪(viewport culling)── */
  const scrollLeft = ref(0);
  const scrollTop = ref(0);

  const updateScroll = () => {
    if (!cvRef.value) return;
    scrollLeft.value = cvRef.value.scrollLeft;
    scrollTop.value = cvRef.value.scrollTop;
  };

  // 用 rAF 节流滚动事件,避免高频滚动导致 visibleNodes 重算阻塞主线程
  let rafId = 0;
  const onScroll = () => {
    if (rafId) return;
    rafId = requestAnimationFrame(() => {
      updateScroll();
      rafId = 0;
    });
  };
  const cancelScrollRaf = () => {
    if (rafId) cancelAnimationFrame(rafId);
  };

  const graphNodes = computed(() =>
    ctx.getNodes().filter(n => !SCHEMA_ONLY_TYPES.has(n.type))
  );
  // Legend 只列出图上真正会出现的节点类型 (按出现频次)。class 永远显示,
  // 即便整个图还没有任何节点,用户也能看到"这是类节点的颜色"这个语义。
  const legendTypes = computed(() => {
    const present = new Set<string>(['class']);
    for (const n of graphNodes.value) present.add(n.type);
    const out: Record<string, any> = {};
    for (const k of Object.keys(NT)) {
      if (present.has(k)) out[k] = (NT as any)[k];
    }
    return out;
  });

  const visibleNodes = computed(() => {
    // 小图谱不做裁剪,省下计算开销 + 避免裁剪带来的复杂度
    if (!cvRef.value || graphNodes.value.length < 80) return graphNodes.value;

    // 视口换算到内容坐标系:除以 zoom 即可
    const z = zoom.value;
    const vl = scrollLeft.value / z;
    const vt = scrollTop.value / z;
    const vr = vl + cvRef.value.clientWidth / z;
    const vb = vt + cvRef.value.clientHeight / z;

    const margin = CULL_MARGIN / z;

    // 节点矩形与视口(含 margin)相交即保留
    return graphNodes.value.filter(n => {
      const nx = n.x || 0;
      const ny = n.y || 0;
      return nx + NW >= vl - margin && nx <= vr + margin &&
             ny + 52 >= vt - margin && ny <= vb + margin;
    });
  });

  const visibleNodeIds = computed(() => new Set(visibleNodes.value.map(n => n.id)));

  const visibleEdges = computed(() => {
    if (!cvRef.value || ctx.getEdges().length < 100) return ctx.getEdges(); // 小图谱不裁剪

    // 边只要有一端在可见集合内就保留,避免边跨视口被错误裁掉
    return ctx.getEdges().filter(e => {
      return visibleNodeIds.value.has(e.from) || visibleNodeIds.value.has(e.to);
    });
  });

  // id → node 索引,给边查端点、菜单查类型用;改用 Object 而非 Map 是因为模板里直接 nmap[xxx] 更顺手
  const nmap = computed(() => Object.fromEntries(ctx.getNodes().map(n => [n.id, n])));

  /** 计算所有节点的包围盒,给 fitView 和外层容器尺寸用。 */
  const bounds = computed(() => {
    const nodes = ctx.getNodes();
    if (nodes.length === 0) return { minX: 0, w: 1000, minY: 0, h: 800 };
    let minX = Infinity, minY = Infinity;
    let maxX = -Infinity, maxY = -Infinity;
    nodes.forEach(n => {
      if (n.x < minX) minX = n.x;
      if (n.y < minY) minY = n.y;
      if (n.x > maxX) maxX = n.x;
      if (n.y > maxY) maxY = n.y;
    });
    if (minX === Infinity) return { minX: 0, w: 1000, minY: 0, h: 800 };
    return {
      minX: minX - 100,
      minY: minY - 100,
      w: maxX - minX + Math.max(NW, 200) + 200,
      h: maxY - minY + 200 + 100
    };
  });

  /** 自动缩放并居中显示所有节点。窗口 resize 时若处于 isAutoFit 状态会自动重新调用。 */
  const fitView = () => {
    if (!cvRef.value || ctx.getNodes().length === 0) return;
    const cw = cvRef.value.clientWidth;
    const ch = cvRef.value.clientHeight;
    const b = bounds.value;

    // 取宽高方向所需缩放比的较小者,并钳制到 [0.1, 2.0]
    const sX = (cw - 80) / Math.max(b.w, 1);
    const sY = (ch - 80) / Math.max(b.h, 1);
    const targetZoom = Math.max(0.1, Math.min(2.0, sX, sY));

    zoom.value = targetZoom;
    isAutoFit.value = true;

    const cx = b.minX + b.w / 2;
    const cy = b.minY + b.h / 2;

    // 等 zoom 应用后再设置滚动位置,否则 scrollLeft 会被旧 size clamp
    nextTick(() => {
      if (cvRef.value) {
        cvRef.value.scrollLeft = Math.max(0, (cx * targetZoom) - cw / 2);
        cvRef.value.scrollTop = Math.max(0, (cy * targetZoom) - ch / 2);
      }
    });
  };

  /** 在画布空白处按下(非框选)时进入平移:记录滚动起点 + 鼠标起点。 */
  const startPan = (e: MouseEvent) => {
    // 普通:记录滚动起点 + 鼠标起点,mousemove 阶段差值反向应用到 scroll
    pan.value = {
      sx: e.clientX,
      sy: e.clientY,
      sl: cvRef.value!.scrollLeft,
      st: cvRef.value!.scrollTop
    };
  };

  /** mousemove 中的平移分支:滚动条反向跟随鼠标位移。 */
  const handlePanMove = (e: MouseEvent) => {
    if (pan.value && cvRef.value) {
      // 平移:滚动条反向跟随鼠标位移
      cvRef.value.scrollLeft = pan.value.sl - (e.clientX - pan.value.sx);
      cvRef.value.scrollTop = pan.value.st - (e.clientY - pan.value.sy);
    }
  };

  /** mouseup 时结束平移。 */
  const endPan = () => {
    pan.value = null;
  };

  /** Ctrl + 滚轮缩放:以鼠标位置为中心,避免缩放后内容偏移。 */
  const onWheel = (e: WheelEvent) => {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      isAutoFit.value = false;
      if (!cvRef.value) return;

      const rect = cvRef.value.getBoundingClientRect();
      const mouseX = e.clientX - rect.left;
      const mouseY = e.clientY - rect.top;

      // 缩放前先把鼠标处对应的内容坐标记录下来
      const contentX = (cvRef.value.scrollLeft + mouseX) / zoom.value;
      const contentY = (cvRef.value.scrollTop + mouseY) / zoom.value;

      // 滚轮向上 deltaY<0 → 放大 1.12 倍;向下 → 0.9 倍;钳制到 [0.1, 3]
      const f = e.deltaY < 0 ? 1.12 : 0.9;
      const ns = Math.max(0.1, Math.min(3, zoom.value * f));

      zoom.value = ns;

      // 用新的 zoom 反推滚动位置,使得鼠标处的内容坐标保持不变
      nextTick(() => {
        if (cvRef.value) {
          cvRef.value.scrollLeft = contentX * ns - mouseX;
          cvRef.value.scrollTop = contentY * ns - mouseY;
        }
      });
    } else {
      // 普通滚轮(无 Ctrl)让浏览器原生滚动;只是关闭自适应标志
      isAutoFit.value = false;
    }
  };

  /** 平滑滚动让指定节点居中显示,用于搜索跳转。 */
  const focusNode = (id: string) => {
    const n = nmap.value[id];
    if (!n || !cvRef.value) return;
    isAutoFit.value = false;
    const cw = cvRef.value.clientWidth;
    const ch = cvRef.value.clientHeight;
    const z = zoom.value;
    // 节点中心点(已乘 zoom)→ 把它移到视口中心
    const cx = (n.x + NW / 2) * z;
    const cy = (n.y + 22) * z;
    cvRef.value.scrollTo({
      left: Math.max(0, cx - cw / 2),
      top: Math.max(0, cy - ch / 2),
      behavior: 'smooth'
    });
  };

  return {
    cvRef,
    zoom,
    scrollLeft,
    scrollTop,
    isAutoFit,
    pan,
    updateScroll,
    onScroll,
    cancelScrollRaf,
    graphNodes,
    legendTypes,
    visibleNodes,
    visibleNodeIds,
    visibleEdges,
    nmap,
    bounds,
    fitView,
    startPan,
    handlePanMove,
    endPan,
    onWheel,
    focusNode,
  };
}
