<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue';
import { NT, NW, getPath } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selId: string | null;
  layoutDirection?: 'LR' | 'TB';
}>();

const emit = defineEmits<{
  (e: 'move', id: string, x: number, y: number): void;
  (e: 'select', id: string | null): void;
  (e: 'auto-layout'): void;
  (e: 'toggle-layout-direction'): void;
  (e: 'clear'): void;
  (e: 'predict-from', id: string): void;
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

const ctxMenu = ref<{ x: number; y: number; id: string } | null>(null);
const onNodeContext = (e: MouseEvent, id: string) => {
  e.preventDefault();
  e.stopPropagation();
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
  emit('select', id);
  const n = nmap.value[id];
  if (n) {
    drag.value = { id, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y };
  }
};

const startPan = (e: MouseEvent) => {
  if ((e.target as HTMLElement).closest('.node') || (e.target as HTMLElement).closest('.hud-overlay')) {
    return;
  }
  isAutoFit.value = false;
  pan.value = {
    sx: e.clientX,
    sy: e.clientY,
    sl: cvRef.value!.scrollLeft,
    st: cvRef.value!.scrollTop
  };
};

let ro: ResizeObserver | null = null;

const onWindowMouseMove = (e: MouseEvent) => {
  if (drag.value) {
    const { id, sx, sy, ox, oy } = drag.value;
    const nx = ox + (e.clientX - sx) / zoom.value;
    const ny = oy + (e.clientY - sy) / zoom.value;
    emit('move', id, Math.max(0, nx), Math.max(0, ny));
    isAutoFit.value = false;
  } else if (pan.value && cvRef.value) {
    cvRef.value.scrollLeft = pan.value.sl - (e.clientX - pan.value.sx);
    cvRef.value.scrollTop = pan.value.st - (e.clientY - pan.value.sy);
  }
};
const onWindowMouseUp = () => {
  drag.value = null;
  pan.value = null;
};

onMounted(() => {
  window.addEventListener('mousemove', onWindowMouseMove);
  window.addEventListener('mouseup', onWindowMouseUp);

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
    <div ref="cvRef" class="graph-canvas" style="flex: 1; overflow: auto; min-width: 0; position: relative;" @mousedown="startPan" @wheel="onWheel" @click="() => { closeCtx(); emit('select', null); }" @contextmenu.prevent>
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
                :class="['node', { 'node-new': n.isNew, 'node-sel': selId === n.id, 'node-predicted': n.source === 'predicted', 'node-dim': !matchesFilter(n), 'node-hl': typeFilter && matchesFilter(n) }]"
                :style="{
                  left: n.x + 'px', top: n.y + 'px', width: NW + 'px',
                  background: getT(n).bg, borderLeftColor: getT(n).color,
                  borderTopColor: (selId === n.id || (typeFilter && matchesFilter(n))) ? getT(n).color + '44' : '#111c2c',
                  borderRightColor: (selId === n.id || (typeFilter && matchesFilter(n))) ? getT(n).color + '44' : '#111c2c',
                  borderBottomColor: (selId === n.id || (typeFilter && matchesFilter(n))) ? getT(n).color + '44' : '#111c2c',
                  boxShadow: (selId === n.id || (typeFilter && matchesFilter(n))) ? `0 0 0 1px ${getT(n).color}55,0 4px 24px ${getT(n).color}33` : '0 2px 8px rgba(0,0,0,0.4)'
                }"
                @mousedown="e => startDrag(e, n.id)" @contextmenu="e => onNodeContext(e, n.id)" @click.stop>
              <div class="node-dot" :style="{ background: getT(n).color, boxShadow: selId === n.id ? `0 0 6px ${getT(n).color}88` : '' }"/>
              <div class="node-label">{{ n.label }}</div>
              <div class="node-type">{{ getT(n).label }}</div>
              <div v-if="n.source === 'predicted'" class="node-pred-badge" :title="'置信度: ' + Math.round((n.confidence || 0) * 100) + '%'">
                预测{{ n.predictedStep ? ' ' + n.predictedStep : '' }}
              </div>
            </div>
          </div>
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
    </div>

    <div class="hud-overlay" style="position: absolute; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none;">
      <div class="canvas-actions" style="position: absolute; top: 24px; left: 50%; transform: translateX(-50%); pointer-events: auto; display: flex; gap: 8px; background: rgba(15, 23, 42, 0.6); backdrop-filter: blur(12px); padding: 6px 8px; border-radius: 12px; border: 1px solid rgba(255, 255, 255, 0.1); box-shadow: 0 4px 16px rgba(0,0,0,0.2);">
        <button class="ca-btn" @click="fitView"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7 7"/></svg>适应屏幕</button>
        <button class="ca-btn" @click="emit('auto-layout')"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v3M21 16v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3M4 12h16"/></svg>自动布局</button>
        <button class="ca-btn" @click="emit('toggle-layout-direction')" :title="layoutDirection === 'LR' ? '当前从左向右,点击改为从上到下' : '当前从上到下,点击改为从左向右'">
          <svg v-if="layoutDirection === 'LR'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="12" x2="20" y2="12"/><polyline points="14 6 20 12 14 18"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="4" x2="12" y2="20"/><polyline points="6 14 12 20 18 14"/></svg>
          {{ layoutDirection === 'LR' ? '从左向右' : '从上到下' }}
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
