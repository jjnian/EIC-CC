<script setup lang="ts">
import { ref, computed, watch, reactive, onMounted, onUnmounted, nextTick } from 'vue';
import { NT, NW, NH, getPath } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selId: string | null;
  layoutDirection?: 'LR' | 'TB';
  /** 只读模式:禁用拖拽、禁用右键菜单、隐藏画布动作浮条与清空按钮(预览页用)。 */
  readonly?: boolean;
  /** 分支对比差异高亮数据 */
  diffHighlight?: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null;
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
}>();

const typeFilter = ref<string | null>(null);
const toggleTypeFilter = (k: string) => {
  typeFilter.value = typeFilter.value === k ? null : k;
};
const matchesFilter = (n: any) => !typeFilter.value || n.type === typeFilter.value;
const edgeMatchesFilter = (e: any) => {
  if (!typeFilter.value) return true;
  const fn = nmap.value[e.from];
  const tn = nmap.value[e.to];
  return (fn && fn.type === typeFilter.value) || (tn && tn.type === typeFilter.value);
};

/* ── 搜索功能 ── */
const searchRef = ref<HTMLInputElement | null>(null);
const searchQuery = ref('');
const searchIdx = ref(0);

const searchMatches = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return [];
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

const heatmapMode = ref(false);

const heatColor = (n: any) => {
  if (!heatmapMode.value || n.source !== 'predicted') return null;
  const p = n.effectiveProbability || n.confidence || 0;
  // 绿(高概率) → 黄(中) → 红(低)
  const h = p * 120; // 0=红, 60=黄, 120=绿
  return `hsl(${h}, 80%, 45%)`;
};

// 分支对比差异着色:蓝=A独有, 橙=B独有, 紫=共同
const diffColor = (n: any) => {
  if (!props.diffHighlight) return null;
  if (props.diffHighlight.uniqueAIds.includes(n.id)) return '#3b82f6'; // 蓝色 = A独有
  if (props.diffHighlight.uniqueBIds.includes(n.id)) return '#f97316'; // 橙色 = B独有
  if (props.diffHighlight.sharedIds.includes(n.id)) return '#a855f7';  // 紫色 = 共同
  return null;
};

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

/* ── 多选状态 ── */
const multiSel = reactive(new Set<string>());
/* ── 框选状态 ── */
const boxSel = ref<{ sx: number; sy: number; cx: number; cy: number } | null>(null);

const cvRef = ref<HTMLElement | null>(null);
const zoom = ref(1);
const drag = ref<any>(null);
const pan = ref<any>(null);
const isAutoFit = ref(true);

const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

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

const fitView = () => {
  if (!cvRef.value || props.nodes.length === 0) return;
  const cw = cvRef.value.clientWidth;
  const ch = cvRef.value.clientHeight;
  const b = bounds.value;

  const sX = (cw - 80) / Math.max(b.w, 1);
  const sY = (ch - 80) / Math.max(b.h, 1);
  const targetZoom = Math.max(0.1, Math.min(2.0, sX, sY));

  zoom.value = targetZoom;
  isAutoFit.value = true;

  const cx = b.minX + b.w / 2;
  const cy = b.minY + b.h / 2;

  nextTick(() => {
    if (cvRef.value) {
      cvRef.value.scrollLeft = Math.max(0, (cx * targetZoom) - cw / 2);
      cvRef.value.scrollTop = Math.max(0, (cy * targetZoom) - ch / 2);
    }
  });
};

const startDrag = (e: MouseEvent, id: string) => {
  e.stopPropagation();

  if (e.ctrlKey || e.metaKey) {
    // Ctrl+点击：切换多选状态
    if (multiSel.has(id)) {
      multiSel.delete(id);
    } else {
      multiSel.add(id);
    }
    emit('select', id);
    return;
  }

  // 非Ctrl点击：如果点的是多选集中的节点，保留多选准备批量拖拽
  if (!multiSel.has(id)) {
    multiSel.clear(); // 点击未选中的节点，清空多选
  }

  emit('select', id);
  if (props.readonly) return;
  const n = nmap.value[id];
  if (n) {
    // 如果拖拽的节点在多选集中且多选数量大于1，记录所有选中节点的初始位置
    const batchOrigins = multiSel.has(id) && multiSel.size > 1
      ? Object.fromEntries([...multiSel].map(sid => [sid, { x: nmap.value[sid]?.x || 0, y: nmap.value[sid]?.y || 0 }]))
      : null;
    drag.value = { id, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y, moved: false, batchOrigins };
  }
};

const startPan = (e: MouseEvent) => {
  if ((e.target as HTMLElement).closest('.node') || (e.target as HTMLElement).closest('.hud-overlay')) {
    return;
  }
  isAutoFit.value = false;

  // Shift+拖拽：框选模式
  if (e.shiftKey && !props.readonly) {
    const rect = cvRef.value!.getBoundingClientRect();
    const x = (e.clientX - rect.left + cvRef.value!.scrollLeft) / zoom.value;
    const y = (e.clientY - rect.top + cvRef.value!.scrollTop) / zoom.value;
    boxSel.value = { sx: x, sy: y, cx: x, cy: y };
    return;
  }

  pan.value = {
    sx: e.clientX,
    sy: e.clientY,
    sl: cvRef.value!.scrollLeft,
    st: cvRef.value!.scrollTop
  };
};

let ro: ResizeObserver | null = null;

const onWindowMouseMove = (e: MouseEvent) => {
  // 框选拖拽中：更新框选的当前坐标
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
    const dx = (e.clientX - sx) / zoom.value;
    const dy = (e.clientY - sy) / zoom.value;

    if (!drag.value.moved && (Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy)) > 2) {
      drag.value.moved = true;
      emit('drag-start', id);
    }

    // 批量移动：对所有选中节点应用相同偏移
    if (batchOrigins) {
      for (const [selId, origin] of Object.entries(batchOrigins) as [string, { x: number; y: number }][]) {
        emit('move', selId, Math.max(0, origin.x + dx), Math.max(0, origin.y + dy));
      }
    } else {
      emit('move', id, Math.max(0, ox + dx), Math.max(0, oy + dy));
    }
    isAutoFit.value = false;
  } else if (pan.value && cvRef.value) {
    cvRef.value.scrollLeft = pan.value.sl - (e.clientX - pan.value.sx);
    cvRef.value.scrollTop = pan.value.st - (e.clientY - pan.value.sy);
  }
};
const onWindowMouseUp = () => {
  // 框选结束：计算框选范围内的节点
  if (boxSel.value) {
    const { sx, sy, cx, cy } = boxSel.value;
    const left = Math.min(sx, cx);
    const top = Math.min(sy, cy);
    const right = Math.max(sx, cx);
    const bottom = Math.max(sy, cy);

    multiSel.clear();
    for (const n of props.nodes) {
      // 节点完全在框选区域内才选中
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
    // 避免在输入框内触发
    const t = e.target as HTMLElement | null;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
    emit('delete-nodes', [...multiSel]);
    multiSel.clear();
  }
};

onMounted(() => {
  window.addEventListener('mousemove', onWindowMouseMove);
  window.addEventListener('mouseup', onWindowMouseUp);
  window.addEventListener('keydown', onSearchKeydown);
  window.addEventListener('keydown', onDeleteKey);

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

  setTimeout(fitView, 50);
});

onUnmounted(() => {
  window.removeEventListener('mousemove', onWindowMouseMove);
  window.removeEventListener('mouseup', onWindowMouseUp);
  window.removeEventListener('keydown', onSearchKeydown);
  window.removeEventListener('keydown', onDeleteKey);
  ro?.disconnect();
  ro = null;
});

const onWheel = (e: WheelEvent) => {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault();
    isAutoFit.value = false;
    if (!cvRef.value) return;

    const rect = cvRef.value.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const contentX = (cvRef.value.scrollLeft + mouseX) / zoom.value;
    const contentY = (cvRef.value.scrollTop + mouseY) / zoom.value;

    const f = e.deltaY < 0 ? 1.12 : 0.9;
    const ns = Math.max(0.1, Math.min(3, zoom.value * f));

    zoom.value = ns;

    nextTick(() => {
      if (cvRef.value) {
        cvRef.value.scrollLeft = contentX * ns - mouseX;
        cvRef.value.scrollTop = contentY * ns - mouseY;
      }
    });
  } else {
    isAutoFit.value = false;
  }
};

const getT = (n: any) => (NT as any)[n.type] || NT.entity;

const focusNode = (id: string) => {
  const n = nmap.value[id];
  if (!n || !cvRef.value) return;
  isAutoFit.value = false;
  const cw = cvRef.value.clientWidth;
  const ch = cvRef.value.clientHeight;
  const z = zoom.value;
  const cx = (n.x + NW / 2) * z;
  const cy = (n.y + 22) * z;
  cvRef.value.scrollTo({
    left: Math.max(0, cx - cw / 2),
    top: Math.max(0, cy - ch / 2),
    behavior: 'smooth'
  });
};

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
              <template v-for="e in edges" :key="e.id">
                <g v-if="nmap[e.from] && nmap[e.to]" :style="{ opacity: edgeMatchesFilter(e) ? 1 : 0.15 }">
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
            <div v-for="n in nodes" :key="n.id"
                :class="['node', { 'node-new': n.isNew, 'node-sel': selId === n.id, 'node-multi-sel': multiSel.has(n.id), 'node-predicted': n.source === 'predicted', 'node-dim': !matchesFilter(n) || (searchQuery && !isSearchMatch(n)), 'node-hl': typeFilter && matchesFilter(n), 'node-search-current': isCurrentSearchTarget(n) }]"
                :style="{
                  left: n.x + 'px', top: n.y + 'px', width: NW + 'px',
                  background: diffColor(n) || heatColor(n) || getT(n).bg, borderLeftColor: getT(n).color,
                  borderTopColor: (selId === n.id || (typeFilter && matchesFilter(n))) ? getT(n).color + '44' : '#111c2c',
                  borderRightColor: (selId === n.id || (typeFilter && matchesFilter(n))) ? getT(n).color + '44' : '#111c2c',
                  borderBottomColor: (selId === n.id || (typeFilter && matchesFilter(n))) ? getT(n).color + '44' : '#111c2c',
                  boxShadow: (selId === n.id || (typeFilter && matchesFilter(n))) ? `0 0 0 1px ${getT(n).color}55,0 4px 24px ${getT(n).color}33` : '0 2px 8px rgba(0,0,0,0.4)'
                }"
                @mousedown="e => startDrag(e, n.id)" @contextmenu="e => onNodeContext(e, n.id)" @dblclick.stop="emit('edit-node', n.id)" @click.stop>
              <div class="node-dot" :style="{ background: getT(n).color, boxShadow: selId === n.id ? `0 0 6px ${getT(n).color}88` : '' }"/>
              <div class="node-label">{{ n.label }}</div>
              <div class="node-type">{{ getT(n).label }}</div>
              <div v-if="n.source === 'predicted'" class="node-pred-badge"
                   :title="'置信度: ' + Math.round((n.confidence || 0) * 100) + '% | 有效概率: ' + Math.round((n.effectiveProbability || 0) * 100) + '%'">
                {{ Math.round((n.effectiveProbability || n.confidence || 0) * 100) }}%
              </div>
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
        <button class="ca-btn" :class="{ 'ca-active': heatmapMode }" @click="heatmapMode = !heatmapMode" title="热力图模式">
          🌡
        </button>
        <button v-if="diffHighlight" class="ca-btn ca-active" @click="emit('clear-diff')" title="清除对比高亮">
          ✕ 对比
        </button>
        <button class="ca-btn" @click="emit('clear')"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>清空画布</button>
      </div>

      <div class="zoom-wrap" style="position: absolute; bottom: 24px; left: 24px; pointer-events: auto;">
        <button @click="zoom = Math.min(3, zoom * 1.2); isAutoFit = false;">+</button>
        <button @click="zoom = Math.max(0.1, zoom * 0.85); isAutoFit = false;">−</button>
      </div>

      <div class="legend" style="position: absolute; top: 24px; right: 24px; pointer-events: auto;">
        <div v-for="(t, k) in NT" :key="k"
             :class="['legend-row', { 'legend-row-active': typeFilter === k, 'legend-row-inactive': typeFilter && typeFilter !== k }]"
             :title="typeFilter === k ? '点击取消筛选' : '点击仅显示' + (t as any).label"
             @click.stop="toggleTypeFilter(k as string)">
          <div class="legend-sq" :style="{ background: (t as any).color, boxShadow: typeFilter === k ? `0 0 0 2px ${(t as any).color}66` : 'none' }"/>
          <span>{{ (t as any).label }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
