<script setup lang="ts">
/**
 * 图谱画布：负责节点/边的渲染、拖拽、平移、缩放、搜索、框选、右键菜单等所有交互。
 * <p>性能要点：
 * <ul>
 *   <li>节点 ≥ 80 / 边 ≥ 100 时启用视口裁剪（viewport culling），仅渲染可视区附近的元素；</li>
 *   <li>滚动事件用 requestAnimationFrame 节流；</li>
 *   <li>拖拽 / 缩放都基于 zoom 反算，避免在缩放时累计漂移。</li>
 * </ul>
 */
import { ref, computed, watch, reactive, onMounted, onUnmounted, nextTick } from 'vue';
import { NT, NW, NH, getPath } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selId: string | null;
  layoutDirection?: 'LR' | 'TB';
  /** 只读模式：禁用拖拽、禁用右键菜单、隐藏画布动作浮条与清空按钮（预览页用）。 */
  readonly?: boolean;
  /** 分支对比差异高亮数据 */
  diffHighlight?: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null;
  /** 撤销/重做按钮的可用状态(由父级图谱历史栈决定) */
  canUndo?: boolean;
  canRedo?: boolean;
}>();

const emit = defineEmits<{
  (e: 'move', id: string, x: number, y: number): void;
  (e: 'drag-start', id: string): void;
  (e: 'select', id: string | null): void;
  (e: 'auto-layout'): void;
  (e: 'toggle-layout-direction'): void;
  (e: 'clear'): void;
  (e: 'predict-from', id: string): void;
  (e: 'delete-node', id: string): void;
  (e: 'delete-nodes', ids: string[]): void;
  (e: 'edit-node', id: string): void;
  (e: 'clear-diff'): void;
  // P1-7：对预测节点请求详细解释
  (e: 'explain-node', id: string): void;
  (e: 'undo'): void;
  (e: 'redo'): void;
}>();

/* ── 节点类型筛选（图例点击） ── */
// 当前筛选的节点 type；null 表示不筛选，'_edge_' 表示筛选关系（边）
const typeFilter = ref<string | null>(null);
const toggleTypeFilter = (k: string) => {
  typeFilter.value = typeFilter.value === k ? null : k;
};
const toggleEdgeFilter = () => {
  typeFilter.value = typeFilter.value === '_edge_' ? null : '_edge_';
};
const matchesFilter = (n: any) => {
  if (!typeFilter.value) return true;
  if (typeFilter.value === '_edge_') return false;
  return n.type === typeFilter.value;
};
const edgeMatchesFilter = (e: any) => {
  if (!typeFilter.value) return true;
  if (typeFilter.value === '_edge_') return true;
  const fn = nmap.value[e.from];
  const tn = nmap.value[e.to];
  return (fn && fn.type === typeFilter.value) || (tn && tn.type === typeFilter.value);
};

/* ── 搜索功能 ── */
const searchRef = ref<HTMLInputElement | null>(null);
const searchQuery = ref('');
// 当前搜索结果在 searchMatches 中的索引（Enter 切下一个，循环）
const searchIdx = ref(0);

const searchMatches = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return [];
  // 同时匹配 label / type / id，以容忍用户记不清确切名称
  return props.nodes.filter(n =>
    n.label.toLowerCase().includes(q) ||
    n.type.toLowerCase().includes(q) ||
    (n.id && n.id.toLowerCase().includes(q))
  );
});

watch(searchQuery, () => { searchIdx.value = 0; });

const jumpToNext = () => {
  if (!searchMatches.value.length) return;
  searchIdx.value = (searchIdx.value + 1) % searchMatches.value.length;
  const target = searchMatches.value[searchIdx.value];
  emit('select', target.id);
  focusNode(target.id);
};

const jumpToPrev = () => {
  if (!searchMatches.value.length) return;
  searchIdx.value = (searchIdx.value - 1 + searchMatches.value.length) % searchMatches.value.length;
  const target = searchMatches.value[searchIdx.value];
  emit('select', target.id);
  focusNode(target.id);
};

const clearSearch = () => {
  searchQuery.value = '';
  searchIdx.value = 0;
};

const isSearchMatch = (n: any) => {
  if (!searchQuery.value) return false;
  return searchMatches.value.some(m => m.id === n.id);
};

const isCurrentSearchTarget = (n: any) => {
  if (!searchMatches.value.length) return false;
  return searchMatches.value[searchIdx.value]?.id === n.id;
};

/* ── 热力图 / 差异着色 ── */
const heatmapMode = ref(false);

/** 热力图模式下，预测节点根据 effectiveProbability 在绿→黄→红之间渐变。 */
const heatColor = (n: any) => {
  if (!heatmapMode.value || n.source !== 'predicted') return null;
  const p = n.effectiveProbability || n.confidence || 0;
  // HSL 色相：0=红，60=黄，120=绿；线性映射 p∈[0,1] → h∈[0,120]
  const h = p * 120;
  return `hsl(${h}, 80%, 45%)`;
};

/** 分支对比着色：蓝=A 独有，橙=B 独有，紫=共同；优先级高于热力图。 */
const diffColor = (n: any) => {
  if (!props.diffHighlight) return null;
  if (props.diffHighlight.uniqueAIds.includes(n.id)) return '#3b82f6'; // 蓝色 = A 独有
  if (props.diffHighlight.uniqueBIds.includes(n.id)) return '#f97316'; // 橙色 = B 独有
  if (props.diffHighlight.sharedIds.includes(n.id)) return '#a855f7';  // 紫色 = 共同
  return null;
};

/* ── 右键菜单 ── */
const ctxMenu = ref<{ x: number; y: number; id: string } | null>(null);
const onNodeContext = (e: MouseEvent, id: string) => {
  e.preventDefault();
  e.stopPropagation();
  if (props.readonly) return; // 只读模式不弹推演菜单
  emit('select', id);
  ctxMenu.value = { x: e.clientX, y: e.clientY, id };
};
const closeCtx = () => { ctxMenu.value = null; };
const triggerPredict = () => {
  if (ctxMenu.value) {
    emit('predict-from', ctxMenu.value.id);
    ctxMenu.value = null;
  }
};

const triggerEdit = () => {
  if (ctxMenu.value) {
    emit('edit-node', ctxMenu.value.id);
    ctxMenu.value = null;
  }
};

// P1-7：对预测节点请求详细解释（依据 / 假设 / 反例）
const triggerExplain = () => {
  if (ctxMenu.value) {
    emit('explain-node', ctxMenu.value.id);
    ctxMenu.value = null;
  }
};

/** 当前右键菜单作用的节点是否为推演节点（用于决定是否显示"为什么"项）。 */
const ctxNodeIsPredicted = computed(() => {
  if (!ctxMenu.value) return false;
  const node = props.nodes.find(n => n.id === ctxMenu.value!.id);
  return node?.source === 'predicted';
});

const triggerDelete = () => {
  if (ctxMenu.value) {
    emit('delete-node', ctxMenu.value.id);
    ctxMenu.value = null;
  }
};

const triggerBatchDelete = () => {
  if (multiSel.size > 0) {
    emit('delete-nodes', [...multiSel]);
    multiSel.clear();
    ctxMenu.value = null;
  }
};

/* ── 多选 / 框选 ── */
// 多选集合：Ctrl+点击 或 Shift+框选 后聚集的节点 id
const multiSel = reactive(new Set<string>());
// 框选状态：sx,sy 起点，cx,cy 当前点（画布坐标系，已除掉 zoom）
const boxSel = ref<{ sx: number; sy: number; cx: number; cy: number } | null>(null);

/* ── 视口与缩放 ── */
const cvRef = ref<HTMLElement | null>(null);
const zoom = ref(1);
// 节点拖拽状态：含起始坐标、起始鼠标位置、是否触发批量等
const drag = ref<any>(null);
// 画布平移状态：滚动条位置 + 鼠标起点
const pan = ref<any>(null);
// 是否处于"自适应屏幕"模式：true 时窗口尺寸变化会自动 fitView
const isAutoFit = ref(true);

/* ── 视口裁剪（viewport culling）── */
const scrollLeft = ref(0);
const scrollTop = ref(0);

const updateScroll = () => {
  if (!cvRef.value) return;
  scrollLeft.value = cvRef.value.scrollLeft;
  scrollTop.value = cvRef.value.scrollTop;
};

// 用 rAF 节流滚动事件，避免高频滚动导致 visibleNodes 重算阻塞主线程
let rafId = 0;
const onScroll = () => {
  if (rafId) return;
  rafId = requestAnimationFrame(() => {
    updateScroll();
    rafId = 0;
  });
};

// 视口外扩裁剪边距，避免滚动时节点突然出现 / 消失造成视觉跳变
const CULL_MARGIN = 200;

// TBox: attribute 和 constraint 这两类只在 Schema 面板里展示,不上图。
// 其它类型(class / 以及老数据里的 entity/process/event 等)统统当成"图上的节点"渲染,
// 这样既能展示静态本体的类,也不会让用户已有的非 class 数据突然全部消失。
const SCHEMA_ONLY_TYPES = new Set(['attribute', 'constraint']);
const graphNodes = computed(() =>
  props.nodes.filter(n => !SCHEMA_ONLY_TYPES.has(n.type))
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
  // 小图谱不做裁剪，省下计算开销 + 避免裁剪带来的复杂度
  if (!cvRef.value || graphNodes.value.length < 80) return graphNodes.value;

  // 视口换算到内容坐标系：除以 zoom 即可
  const z = zoom.value;
  const vl = scrollLeft.value / z;
  const vt = scrollTop.value / z;
  const vr = vl + cvRef.value.clientWidth / z;
  const vb = vt + cvRef.value.clientHeight / z;

  const margin = CULL_MARGIN / z;

  // 节点矩形与视口（含 margin）相交即保留
  return graphNodes.value.filter(n => {
    const nx = n.x || 0;
    const ny = n.y || 0;
    return nx + NW >= vl - margin && nx <= vr + margin &&
           ny + 52 >= vt - margin && ny <= vb + margin;
  });
});

const visibleNodeIds = computed(() => new Set(visibleNodes.value.map(n => n.id)));

const visibleEdges = computed(() => {
  if (!cvRef.value || props.edges.length < 100) return props.edges; // 小图谱不裁剪

  // 边只要有一端在可见集合内就保留，避免边跨视口被错误裁掉
  return props.edges.filter(e => {
    return visibleNodeIds.value.has(e.from) || visibleNodeIds.value.has(e.to);
  });
});

// id → node 索引，给边查端点、菜单查类型用；改用 Object 而非 Map 是因为模板里直接 nmap[xxx] 更顺手
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

/** 计算所有节点的包围盒，给 fitView 和外层容器尺寸用。 */
const bounds = computed(() => {
  if (props.nodes.length === 0) return { minX: 0, w: 1000, minY: 0, h: 800 };
  let minX = Infinity, minY = Infinity;
  let maxX = -Infinity, maxY = -Infinity;
  props.nodes.forEach(n => {
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
  if (!cvRef.value || props.nodes.length === 0) return;
  const cw = cvRef.value.clientWidth;
  const ch = cvRef.value.clientHeight;
  const b = bounds.value;

  // 取宽高方向所需缩放比的较小者，并钳制到 [0.1, 2.0]
  const sX = (cw - 80) / Math.max(b.w, 1);
  const sY = (ch - 80) / Math.max(b.h, 1);
  const targetZoom = Math.max(0.1, Math.min(2.0, sX, sY));

  zoom.value = targetZoom;
  isAutoFit.value = true;

  const cx = b.minX + b.w / 2;
  const cy = b.minY + b.h / 2;

  // 等 zoom 应用后再设置滚动位置，否则 scrollLeft 会被旧 size clamp
  nextTick(() => {
    if (cvRef.value) {
      cvRef.value.scrollLeft = Math.max(0, (cx * targetZoom) - cw / 2);
      cvRef.value.scrollTop = Math.max(0, (cy * targetZoom) - ch / 2);
    }
  });
};

/* ── 拖拽 ── */
const startDrag = (e: MouseEvent, id: string) => {
  e.stopPropagation();

  if (e.ctrlKey || e.metaKey) {
    // Ctrl + 点击：切换该节点在多选集合中的状态
    if (multiSel.has(id)) {
      multiSel.delete(id);
    } else {
      multiSel.add(id);
    }
    emit('select', id);
    return;
  }

  // 非 Ctrl 点击：如果点的是多选集中的节点，保留多选准备批量拖拽
  if (!multiSel.has(id)) {
    multiSel.clear(); // 点击未选中的节点，清空多选
  }

  emit('select', id);
  if (props.readonly) return;
  const n = nmap.value[id];
  if (n) {
    // 批量拖拽：当被拖节点在多选集合中且集合 >1，记录所有选中节点的初始坐标
    const batchOrigins = multiSel.has(id) && multiSel.size > 1
      ? Object.fromEntries([...multiSel].map(sid => [sid, { x: nmap.value[sid]?.x || 0, y: nmap.value[sid]?.y || 0 }]))
      : null;
    drag.value = { id, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y, moved: false, batchOrigins };
  }
};

/** 在画布空白处按下：进入平移或框选模式。 */
const startPan = (e: MouseEvent) => {
  // 点击落在节点 / 浮层上时不进入 pan，让对应控件优先响应
  if ((e.target as HTMLElement).closest('.node') || (e.target as HTMLElement).closest('.hud-overlay')) {
    return;
  }
  isAutoFit.value = false;

  // Shift + 拖拽：框选模式（只读时禁用）
  if (e.shiftKey && !props.readonly) {
    const rect = cvRef.value!.getBoundingClientRect();
    // 把客户端坐标换算到内容坐标系（含滚动 + zoom）
    const x = (e.clientX - rect.left + cvRef.value!.scrollLeft) / zoom.value;
    const y = (e.clientY - rect.top + cvRef.value!.scrollTop) / zoom.value;
    boxSel.value = { sx: x, sy: y, cx: x, cy: y };
    return;
  }

  // 普通：记录滚动起点 + 鼠标起点，mousemove 阶段差值反向应用到 scroll
  pan.value = {
    sx: e.clientX,
    sy: e.clientY,
    sl: cvRef.value!.scrollLeft,
    st: cvRef.value!.scrollTop
  };
};

let ro: ResizeObserver | null = null;

const onWindowMouseMove = (e: MouseEvent) => {
  // 框选拖拽中：实时更新当前角点，模板用它绘制虚线矩形
  if (boxSel.value) {
    const rect = cvRef.value!.getBoundingClientRect();
    const x = (e.clientX - rect.left + cvRef.value!.scrollLeft) / zoom.value;
    const y = (e.clientY - rect.top + cvRef.value!.scrollTop) / zoom.value;
    boxSel.value.cx = x;
    boxSel.value.cy = y;
    return;
  }

  if (drag.value) {
    const { id, sx, sy, ox, oy, batchOrigins } = drag.value;
    // 把屏幕位移换算到内容坐标系
    const dx = (e.clientX - sx) / zoom.value;
    const dy = (e.clientY - sy) / zoom.value;

    // 超过 2px 才视为真正的拖拽，否则当成点击；避免选中态被微抖动覆盖
    if (!drag.value.moved && (Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy)) > 2) {
      drag.value.moved = true;
      emit('drag-start', id);
    }

    // 批量移动：所有被选节点应用相同位移；单选则只移动当前节点
    if (batchOrigins) {
      for (const [selId, origin] of Object.entries(batchOrigins) as [string, { x: number; y: number }][]) {
        emit('move', selId, Math.max(0, origin.x + dx), Math.max(0, origin.y + dy));
      }
    } else {
      emit('move', id, Math.max(0, ox + dx), Math.max(0, oy + dy));
    }
    isAutoFit.value = false;
  } else if (pan.value && cvRef.value) {
    // 平移：滚动条反向跟随鼠标位移
    cvRef.value.scrollLeft = pan.value.sl - (e.clientX - pan.value.sx);
    cvRef.value.scrollTop = pan.value.st - (e.clientY - pan.value.sy);
  }
};
const onWindowMouseUp = () => {
  // 框选结束：把矩形内的节点全部加入 multiSel
  if (boxSel.value) {
    const { sx, sy, cx, cy } = boxSel.value;
    const left = Math.min(sx, cx);
    const top = Math.min(sy, cy);
    const right = Math.max(sx, cx);
    const bottom = Math.max(sy, cy);

    multiSel.clear();
    for (const n of props.nodes) {
      // 节点矩形完全落在选框内才算选中（部分相交不计），避免误选
      const nx = n.x ?? 0;
      const ny = n.y ?? 0;
      if (nx >= left && nx + NW <= right && ny >= top && ny + NH <= bottom) {
        multiSel.add(n.id);
      }
    }
    boxSel.value = null;
    return;
  }
  drag.value = null;
  pan.value = null;
};

/* Ctrl+F 快捷键唤起搜索框 */
const onSearchKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
    if (props.readonly) return;
    e.preventDefault();
    searchRef.value?.focus();
  }
};

/* Delete 键批量删除选中节点 */
const onDeleteKey = (e: KeyboardEvent) => {
  if (e.key === 'Delete' && multiSel.size > 0 && !props.readonly) {
    // 避免在输入框内触发误删（用户正在编辑文本）
    const t = e.target as HTMLElement | null;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
    emit('delete-nodes', [...multiSel]);
    multiSel.clear();
  }
};

onMounted(() => {
  // 拖拽 / 平移用 window 级事件，保证鼠标移出画布外仍能跟踪
  window.addEventListener('mousemove', onWindowMouseMove);
  window.addEventListener('mouseup', onWindowMouseUp);
  window.addEventListener('keydown', onSearchKeydown);
  window.addEventListener('keydown', onDeleteKey);
  cvRef.value?.addEventListener('scroll', onScroll, { passive: true });

  // 容器尺寸变化（如折叠侧栏）时若处于自适应模式自动重排
  ro = new ResizeObserver(() => {
    window.requestAnimationFrame(() => {
      if (isAutoFit.value) {
        fitView();
      }
    });
  });
  if (cvRef.value) {
    ro.observe(cvRef.value);
  }

  // 等首帧布局完成再 fitView，确保 clientWidth 已可用
  setTimeout(fitView, 50);
});

onUnmounted(() => {
  window.removeEventListener('mousemove', onWindowMouseMove);
  window.removeEventListener('mouseup', onWindowMouseUp);
  window.removeEventListener('keydown', onSearchKeydown);
  window.removeEventListener('keydown', onDeleteKey);
  cvRef.value?.removeEventListener('scroll', onScroll);
  if (rafId) cancelAnimationFrame(rafId);
  ro?.disconnect();
  ro = null;
});

/** Ctrl + 滚轮缩放：以鼠标位置为中心，避免缩放后内容偏移。 */
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

    // 滚轮向上 deltaY<0 → 放大 1.12 倍；向下 → 0.9 倍；钳制到 [0.1, 3]
    const f = e.deltaY < 0 ? 1.12 : 0.9;
    const ns = Math.max(0.1, Math.min(3, zoom.value * f));

    zoom.value = ns;

    // 用新的 zoom 反推滚动位置，使得鼠标处的内容坐标保持不变
    nextTick(() => {
      if (cvRef.value) {
        cvRef.value.scrollLeft = contentX * ns - mouseX;
        cvRef.value.scrollTop = contentY * ns - mouseY;
      }
    });
  } else {
    // 普通滚轮（无 Ctrl）让浏览器原生滚动；只是关闭自适应标志
    isAutoFit.value = false;
  }
};

const getT = (n: any) => (NT as any)[n.type] || NT.class;

const neighborIds = computed(() => {
  if (!props.selId) return null;
  const ids = new Set<string>();
  ids.add(props.selId);
  for (const e of props.edges) {
    if (e.from === props.selId) ids.add(e.to);
    if (e.to === props.selId) ids.add(e.from);
  }
  return ids;
});

/** 平滑滚动让指定节点居中显示，用于搜索跳转。 */
const focusNode = (id: string) => {
  const n = nmap.value[id];
  if (!n || !cvRef.value) return;
  isAutoFit.value = false;
  const cw = cvRef.value.clientWidth;
  const ch = cvRef.value.clientHeight;
  const z = zoom.value;
  // 节点中心点（已乘 zoom）→ 把它移到视口中心
  const cx = (n.x + NW / 2) * z;
  const cy = (n.y + 22) * z;
  cvRef.value.scrollTo({
    left: Math.max(0, cx - cw / 2),
    top: Math.max(0, cy - ch / 2),
    behavior: 'smooth'
  });
};

// 暴露给父组件：fitView 在推演完成等场景手动调用，focusNode 给节点定位用
defineExpose({ fitView, focusNode });
</script>

<template>
  <div class="graph-wrapper" style="position: relative; flex: 1; overflow: hidden; display: flex; background: transparent;">
    <div ref="cvRef" class="graph-canvas" style="flex: 1; overflow: auto; min-width: 0; position: relative;" @mousedown="startPan" @wheel="onWheel" @click="() => { closeCtx(); multiSel.clear(); emit('select', null); }" @contextmenu.prevent>
      <div :style="{ width: Math.max(3000, bounds.w * zoom) + 'px', height: Math.max(3000, bounds.h * zoom) + 'px', position: 'relative' }">
        <div class="scale-container" :style="{ transform: `scale(${zoom})`, transformOrigin: '0 0', width: '3000px', height: '3000px', position: 'absolute', top: 0, left: 0 }">
          <svg style="position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;overflow:visible">
            <defs>
              <marker id="arr" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="rgba(255, 255, 255, 0.4)"/>
              </marker>
              <marker id="arr-r" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="#ff3399"/>
              </marker>
              <marker id="arr-s" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="#42b883"/>
              </marker>
              <marker id="arr-p" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="#fbbf24"/>
              </marker>
            </defs>
            <g>
              <template v-for="e in visibleEdges" :key="e.id">
                <g v-if="nmap[e.from] && nmap[e.to]" :style="{ opacity: !edgeMatchesFilter(e) ? 0.15 : (selId && selId !== e.from && selId !== e.to ? 0.25 : 1), transition: 'opacity .2s' }">
                  <path v-if="selId === e.from || selId === e.to" :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none" :stroke="e.source === 'predicted' ? '#fbbf24' : (e.rule_driven ? '#ff3399' : '#42b883')" :stroke-width="8" opacity="0.1"/>
                  <path :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none"
                        :stroke="e.source === 'predicted' ? '#fbbf24' : (e.rule_driven ? (selId === e.from || selId === e.to ? '#ff3399' : 'rgba(255, 51, 153, 0.4)') : (selId === e.from || selId === e.to ? '#42b883' : 'rgba(255, 255, 255, 0.3)'))"
                        :stroke-width="selId === e.from || selId === e.to ? 2 : 1.4"
                        :stroke-dasharray="e.source === 'predicted' ? '6 4' : null"
                        :marker-end="e.source === 'predicted' ? 'url(#arr-p)' : (e.rule_driven ? 'url(#arr-r)' : (selId === e.from || selId === e.to ? 'url(#arr-s)' : 'url(#arr)'))"/>
                  <path v-if="e.source !== 'predicted'" :class="selId === e.from || selId === e.to ? 'line-flow-fast' : 'line-flow'" :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none" :stroke="e.rule_driven ? (selId === e.from || selId === e.to ? '#ff80bf' : 'rgba(255, 51, 153, 0.6)') : (selId === e.from || selId === e.to ? '#a7f3d0' : 'rgba(66, 184, 131, 0.6)')" :stroke-width="selId === e.from || selId === e.to ? 2.5 : 1.5"/>
                  <g v-if="e.label">
                    <rect :x="getPath(nmap[e.from], nmap[e.to]).mx - 20" :y="getPath(nmap[e.from], nmap[e.to]).my - 17" width="40" height="14" rx="3" fill="#0f172a" opacity="0.8"/>
                    <text :x="getPath(nmap[e.from], nmap[e.to]).mx" :y="getPath(nmap[e.from], nmap[e.to]).my - 6" text-anchor="middle" :style="{fill: e.source === 'predicted' ? '#fbbf24' : (e.rule_driven ? '#ff3399' : (selId === e.from || selId === e.to ? '#42b883' : 'rgba(255, 255, 255, 0.7)')), fontSize: '9.5px', fontFamily: 'JetBrains Mono', fontWeight: 500}">
                      <tspan v-if="e.source === 'predicted'">◇</tspan><tspan v-else-if="e.rule_driven">⚡</tspan>{{ e.label }}
                    </text>
                  </g>
                </g>
              </template>
            </g>
          </svg>

          <div class="graph-root" style="transform:none;">
            <div v-for="n in visibleNodes" :key="n.id"
                :class="['node', { 'node-new': n.isNew, 'node-sel': selId === n.id, 'node-multi-sel': multiSel.has(n.id), 'node-predicted': n.source === 'predicted', 'node-dim': !matchesFilter(n) || (searchQuery && !isSearchMatch(n)), 'node-neighbor-dim': !typeFilter && !searchQuery && neighborIds && !neighborIds.has(n.id), 'node-neighbor-hl': !typeFilter && !searchQuery && neighborIds && neighborIds.has(n.id) && selId !== n.id, 'node-hl': typeFilter && matchesFilter(n), 'node-search-current': isCurrentSearchTarget(n) }]"
                :style="{
                  left: n.x + 'px', top: n.y + 'px', width: NW + 'px',
                  background: diffColor(n) || heatColor(n) || getT(n).bg, borderLeftColor: getT(n).color,
                  borderTopColor: (selId === n.id || (typeFilter && matchesFilter(n)) || (neighborIds && neighborIds.has(n.id) && selId !== n.id)) ? getT(n).color + '44' : '#111c2c',
                  borderRightColor: (selId === n.id || (typeFilter && matchesFilter(n)) || (neighborIds && neighborIds.has(n.id) && selId !== n.id)) ? getT(n).color + '44' : '#111c2c',
                  borderBottomColor: (selId === n.id || (typeFilter && matchesFilter(n)) || (neighborIds && neighborIds.has(n.id) && selId !== n.id)) ? getT(n).color + '44' : '#111c2c',
                  boxShadow: (selId === n.id || (typeFilter && matchesFilter(n))) ? `0 0 0 1px ${getT(n).color}55,0 4px 24px ${getT(n).color}33` : (neighborIds && neighborIds.has(n.id) && selId !== n.id) ? `0 0 0 1px ${getT(n).color}44,0 2px 12px ${getT(n).color}22` : '0 2px 8px rgba(0,0,0,0.4)'
                }"
                @mousedown="e => startDrag(e, n.id)" @contextmenu="e => onNodeContext(e, n.id)" @dblclick.stop="emit('edit-node', n.id)" @click.stop>
              <div class="node-dot" :style="{ background: getT(n).color, boxShadow: selId === n.id ? `0 0 6px ${getT(n).color}88` : '' }"/>
              <div class="node-label">{{ n.label }}</div>
              <div class="node-type">{{ getT(n).label }}</div>
              <div v-if="n.source === 'predicted'" class="node-pred-badge"
                   :title="'置信度: ' + Math.round((n.confidence || 0) * 100) + '% | 有效概率: ' + Math.round((n.effectiveProbability || 0) * 100) + '%'">
                {{ Math.round((n.effectiveProbability || n.confidence || 0) * 100) }}%
              </div>
              <div v-if="(n.constraints?.length || 0) > 0" class="node-lock-badge" :title="n.constraints.map((c: any) => (c.kind || '约束') + ': ' + c.note).join('\n')">🔒</div>
            </div>
          </div>

          <!-- 框选矩形视觉反馈 -->
          <div v-if="boxSel" class="box-select" :style="{
            left: Math.min(boxSel.sx, boxSel.cx) + 'px',
            top: Math.min(boxSel.sy, boxSel.cy) + 'px',
            width: Math.abs(boxSel.cx - boxSel.sx) + 'px',
            height: Math.abs(boxSel.cy - boxSel.sy) + 'px'
          }"></div>
        </div>
      </div>
    </div>

    <!-- Right-click context menu -->
    <div v-if="ctxMenu" class="node-ctx-menu" :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }" @click.stop>
      <button class="ctx-item" @click="triggerPredict">
        <span class="ctx-icon">⚡</span>
        <span>从此推演</span>
        <span class="ctx-hint">Forward</span>
      </button>
      <!-- P1-7：仅对推演节点显示"为什么" -->
      <button v-if="ctxNodeIsPredicted" class="ctx-item" @click="triggerExplain">
        <span class="ctx-icon">🔍</span>
        <span>为什么会发生？</span>
        <span class="ctx-hint">Explain</span>
      </button>
      <div class="ctx-sep"></div>
      <button class="ctx-item" @click="triggerEdit">
        <span class="ctx-icon">✎</span>
        <span>编辑节点</span>
      </button>
      <button v-if="multiSel.size > 1" class="ctx-item ctx-danger" @click="triggerBatchDelete">
        <span class="ctx-icon">✕</span>
        <span>删除选中 ({{ multiSel.size }})</span>
        <span class="ctx-hint">Delete</span>
      </button>
      <button v-else class="ctx-item ctx-danger" @click="triggerDelete">
        <span class="ctx-icon">✕</span>
        <span>删除节点</span>
      </button>
    </div>

    <div class="hud-overlay" style="position: absolute; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none;">
      <div class="search-bar" v-if="!readonly" style="pointer-events: auto;">
        <input
          ref="searchRef"
          v-model="searchQuery"
          class="search-input"
          type="text"
          placeholder="搜索节点名称或类型…"
          @keydown.enter="jumpToNext"
          @keydown.escape="clearSearch"
        />
        <span v-if="searchQuery && searchMatches.length" class="search-count">
          {{ searchIdx + 1 }}/{{ searchMatches.length }}
        </span>
        <button v-if="searchQuery" class="search-btn" @click="jumpToNext" title="下一个 (Enter)">↓</button>
        <button v-if="searchQuery" class="search-btn" @click="jumpToPrev" title="上一个">↑</button>
        <button v-if="searchQuery" class="search-btn" @click="clearSearch" title="清除">✕</button>
      </div>
      <div v-if="!readonly" class="canvas-actions" style="position: absolute; top: 24px; left: 50%; transform: translateX(-50%); pointer-events: auto; display: flex; gap: 8px; background: rgba(15, 23, 42, 0.6); backdrop-filter: blur(12px); padding: 6px 8px; border-radius: 12px; border: 1px solid rgba(255, 255, 255, 0.1); box-shadow: 0 4px 16px rgba(0,0,0,0.2);">
        <button class="ca-btn" @click="fitView"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7 7"/></svg>适应屏幕</button>
        <button class="ca-btn" @click="emit('auto-layout')"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v3M21 16v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3M4 12h16"/></svg>自动布局</button>
        <button class="ca-btn" @click="emit('toggle-layout-direction')" :title="layoutDirection === 'LR' ? '当前从左向右,点击改为从上到下' : '当前从上到下,点击改为从左向右'">
          <svg v-if="layoutDirection === 'LR'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="12" x2="20" y2="12"/><polyline points="14 6 20 12 14 18"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="4" x2="12" y2="20"/><polyline points="6 14 12 20 18 14"/></svg>
          {{ layoutDirection === 'LR' ? '从左向右' : '从上到下' }}
        </button>
        <button
          class="ca-btn"
          :class="{ 'ca-active': heatmapMode }"
          @click="heatmapMode = !heatmapMode"
          :title="heatmapMode
            ? '已开启概率色阶:推演节点按置信度着色(绿 高 → 黄 中 → 红 低)。点击关闭。'
            : '概率色阶:开启后将推演节点按置信度着色(绿 高 → 黄 中 → 红 低),方便快速识别可信度。'"
        >
          <svg class="ca-heat-icon" viewBox="0 0 24 24" width="22" height="10" fill="none" aria-hidden="true">
            <circle cx="5"  cy="12" r="3.2" fill="#42b883"/>
            <circle cx="12" cy="12" r="3.2" fill="#fbbf24"/>
            <circle cx="19" cy="12" r="3.2" fill="#ef4444"/>
          </svg>
          概率色阶
        </button>
        <button v-if="diffHighlight" class="ca-btn ca-active" @click="emit('clear-diff')" title="清除对比高亮">
          ✕ 对比
        </button>
        <button class="ca-btn" @click="emit('clear')"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>清空画布</button>
        <span class="ca-sep" aria-hidden="true"></span>
        <button class="ca-btn ca-icon" :disabled="!canUndo" @click="emit('undo')" title="撤销 (Ctrl+Z)">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/></svg>
        </button>
        <button class="ca-btn ca-icon" :disabled="!canRedo" @click="emit('redo')" title="重做 (Ctrl+Shift+Z)">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 7v6h-6"/><path d="M3 17a9 9 0 0 1 15-6.7L21 13"/></svg>
        </button>
      </div>

      <div class="zoom-wrap" style="position: absolute; bottom: 24px; left: 24px; pointer-events: auto;">
        <button @click="zoom = Math.min(3, zoom * 1.2); isAutoFit = false;">+</button>
        <button @click="zoom = Math.max(0.1, zoom * 0.85); isAutoFit = false;">−</button>
      </div>
      <span v-if="props.nodes.length >= 80" class="perf-indicator">
        {{ visibleNodes.length }}/{{ props.nodes.length }} 节点可见
      </span>

      <div class="legend" style="position: absolute; top: 24px; right: 24px; pointer-events: auto;">
        <!-- 静态本体层图例: 类/关系/(约束)。类按 NT 里出现的类型分色,关系/约束是固定标识。 -->
        <div v-for="(t, k) in legendTypes" :key="k"
             :class="['legend-row', { 'legend-row-active': typeFilter === k, 'legend-row-inactive': typeFilter && typeFilter !== k }]"
             :title="typeFilter === k ? '点击取消筛选' : '点击仅显示' + (t as any).label"
             @click.stop="toggleTypeFilter(k as string)">
          <div class="legend-sq" :style="{ background: (t as any).color, boxShadow: typeFilter === k ? `0 0 0 2px ${(t as any).color}66` : 'none' }"/>
          <span>{{ (t as any).label }}</span>
        </div>
        <div class="legend-sep"></div>
        <div :class="['legend-row', { 'legend-row-active': typeFilter === '_edge_', 'legend-row-inactive': typeFilter && typeFilter !== '_edge_' }]"
             :title="typeFilter === '_edge_' ? '点击取消筛选' : '点击仅显示关系'"
             @click.stop="toggleEdgeFilter">
          <svg class="legend-arrow" width="18" height="10" viewBox="0 0 18 10">
            <line x1="1" y1="5" x2="14" y2="5" stroke="#22dd88" stroke-width="1.6"/>
            <path d="M14,1 L17,5 L14,9 Z" fill="#22dd88"/>
          </svg>
          <span>关系</span>
        </div>
      </div>
    </div>
  </div>
</template>
