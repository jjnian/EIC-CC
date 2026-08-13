<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import type { OntologyNode, OntologyEdge } from '../../types';
import { layeredLayout, boundsOf, NODE_W, NODE_H, type XY } from '../lib/graphLayout';
import { typeStyle } from '../lib/graphStyle';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selectedId?: string;
  highlight?: Set<string> | null;
  query?: string;
  showEdgeLabels?: boolean;
}>();

const emit = defineEmits<{
  (e: 'select', node: OntologyNode | null): void;
  (e: 'moved'): void;
}>();

const container = ref<HTMLDivElement | null>(null);
const view = reactive({ x: 60, y: 60, k: 1 });
const pos = reactive(new Map<string, XY>());
const size = reactive({ w: 800, h: 600 });

// ── 位置管理：已有坐标优先，缺失的用分层布局补齐 ──
function ensurePositions() {
  const missing = props.nodes.filter((n) => typeof n.x !== 'number' || typeof n.y !== 'number');
  const needFull = props.nodes.length > 0 && missing.length > props.nodes.length * 0.5;
  if (needFull) {
    const laid = layeredLayout(props.nodes, props.edges);
    pos.clear();
    for (const [id, p] of laid) pos.set(id, p);
    return;
  }
  for (const n of props.nodes) {
    if (!pos.has(n.id) && typeof n.x === 'number' && typeof n.y === 'number') {
      pos.set(n.id, { x: n.x, y: n.y });
    }
  }
  const still = props.nodes.filter((n) => !pos.has(n.id));
  if (still.length) {
    const laid = layeredLayout(props.nodes, props.edges);
    for (const n of still) pos.set(n.id, laid.get(n.id)!);
  }
  // 清理已删除节点
  const ids = new Set(props.nodes.map((n) => n.id));
  for (const id of [...pos.keys()]) if (!ids.has(id)) pos.delete(id);
}

watch(() => [props.nodes, props.edges], ensurePositions, { immediate: true, deep: true });

// ── 视口 ──
let ro: ResizeObserver | null = null;
onMounted(() => {
  if (container.value) {
    const el = container.value;
    size.w = el.clientWidth; size.h = el.clientHeight;
    ro = new ResizeObserver(() => { size.w = el.clientWidth; size.h = el.clientHeight; });
    ro.observe(el);
    fit();
  }
});
onBeforeUnmount(() => ro?.disconnect());

function fit() {
  if (!pos.size) return;
  const b = boundsOf(pos.values());
  const bw = Math.max(b.maxX - b.minX, 1);
  const bh = Math.max(b.maxY - b.minY, 1);
  const k = Math.min(size.w / (bw + 120), size.h / (bh + 120), 1.2);
  view.k = Math.max(0.08, k);
  view.x = (size.w - bw * view.k) / 2 - b.minX * view.k;
  view.y = (size.h - bh * view.k) / 2 - b.minY * view.k;
}

function zoomBy(factor: number, cx?: number, cy?: number) {
  const mx = cx ?? size.w / 2;
  const my = cy ?? size.h / 2;
  const k2 = Math.min(3, Math.max(0.08, view.k * factor));
  const f = k2 / view.k;
  view.x = mx - (mx - view.x) * f;
  view.y = my - (my - view.y) * f;
  view.k = k2;
}

function onWheel(e: WheelEvent) {
  e.preventDefault();
  const rect = container.value!.getBoundingClientRect();
  zoomBy(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - rect.left, e.clientY - rect.top);
}

// ── 平移 / 节点拖拽 ──
type DragState =
  | { kind: 'pan'; sx: number; sy: number; ox: number; oy: number }
  | { kind: 'node'; id: string; sx: number; sy: number; ox: number; oy: number; moved: boolean }
  | null;
let drag: DragState = null;

function onBgPointerDown(e: PointerEvent) {
  drag = { kind: 'pan', sx: e.clientX, sy: e.clientY, ox: view.x, oy: view.y };
  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp, { once: true });
}

function onNodePointerDown(e: PointerEvent, n: OntologyNode) {
  e.stopPropagation();
  const p = pos.get(n.id);
  if (!p) return;
  drag = { kind: 'node', id: n.id, sx: e.clientX, sy: e.clientY, ox: p.x, oy: p.y, moved: false };
  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', () => onPointerUpNode(n), { once: true });
}

function onPointerMove(e: PointerEvent) {
  if (!drag) return;
  if (drag.kind === 'pan') {
    view.x = drag.ox + (e.clientX - drag.sx);
    view.y = drag.oy + (e.clientY - drag.sy);
  } else {
    const dx = (e.clientX - drag.sx) / view.k;
    const dy = (e.clientY - drag.sy) / view.k;
    if (!drag.moved && Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) < 4) return;
    drag.moved = true;
    pos.set(drag.id, { x: drag.ox + dx, y: drag.oy + dy });
  }
}

function onPointerUp() { drag = null; }

function onPointerUpNode(n: OntologyNode) {
  const wasDrag = drag?.kind === 'node' && drag.moved;
  drag = null;
  if (wasDrag) emit('moved');
  else emit('select', n);
}

// ── 渲染数据 ──
const queryLc = computed(() => (props.query || '').trim().toLowerCase());

function nodeDim(n: OntologyNode): boolean {
  if (props.highlight && props.highlight.size) return !props.highlight.has(n.id);
  if (queryLc.value) return !n.label.toLowerCase().includes(queryLc.value);
  return false;
}

const renderNodes = computed(() =>
  props.nodes.map((n) => {
    const p = pos.get(n.id) || { x: 0, y: 0 };
    return { n, x: p.x, y: p.y, style: typeStyle(n.type), dim: nodeDim(n) };
  }),
);

function borderPoint(from: XY, to: XY): XY {
  const cx = from.x + NODE_W / 2;
  const cy = from.y + NODE_H / 2;
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  if (!dx && !dy) return { x: cx, y: cy };
  const tx = dx ? (NODE_W / 2) / Math.abs(dx) : Infinity;
  const ty = dy ? (NODE_H / 2) / Math.abs(dy) : Infinity;
  const t = Math.min(tx, ty);
  return { x: cx + dx * t, y: cy + dy * t };
}

const renderEdges = computed(() => {
  const out: { e: OntologyEdge; d: string; mx: number; my: number; dim: boolean; hl: boolean }[] = [];
  for (const e of props.edges) {
    const p1 = pos.get(e.from);
    const p2 = pos.get(e.to);
    if (!p1 || !p2) continue;
    const a = borderPoint(p1, p2);
    const b = borderPoint(p2, p1);
    const dx = Math.abs(b.x - a.x);
    const cp = Math.max(dx * 0.5, 40);
    const d = `M${a.x},${a.y} C${a.x + cp},${a.y} ${b.x - cp},${b.y} ${b.x},${b.y}`;
    const hl = !!props.highlight && props.highlight.size > 0 &&
      props.highlight.has(e.from) && props.highlight.has(e.to);
    const dim = !!props.highlight && props.highlight.size > 0 && !hl;
    out.push({ e, d, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 - 6, dim, hl });
  }
  return out;
});

const transform = computed(() => `translate(${view.x},${view.y}) scale(${view.k})`);

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function collectPositions(): Record<string, XY> {
  const out: Record<string, XY> = {};
  for (const [id, p] of pos) out[id] = { ...p };
  return out;
}

defineExpose({ fit, zoomIn: () => zoomBy(1.25), zoomOut: () => zoomBy(1 / 1.25), collectPositions });
</script>

<template>
  <div ref="container" class="relative h-full w-full overflow-hidden bg-slate-50">
    <svg
      class="h-full w-full cursor-grab active:cursor-grabbing"
      @pointerdown="onBgPointerDown"
      @wheel="onWheel"
      @dblclick="fit"
    >
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="#94a3b8" />
        </marker>
        <marker id="arrow-hl" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="#4f46e5" />
        </marker>
      </defs>
      <g :transform="transform">
        <g v-for="r in renderEdges" :key="r.e.id" :opacity="r.dim ? 0.12 : 1">
          <path
            :d="r.d"
            fill="none"
            :stroke="r.hl ? '#4f46e5' : '#94a3b8'"
            :stroke-width="r.hl ? 1.8 : 1.2"
            :stroke-dasharray="r.e.source === 'inferred' ? '5 4' : undefined"
            :marker-end="`url(#${r.hl ? 'arrow-hl' : 'arrow'})`"
          />
          <text
            v-if="showEdgeLabels && r.e.label"
            :x="r.mx" :y="r.my"
            text-anchor="middle"
            class="fill-slate-400"
            style="font-size: 10px"
          >{{ truncate(r.e.label, 12) }}</text>
        </g>

        <g
          v-for="r in renderNodes"
          :key="r.n.id"
          :transform="`translate(${r.x},${r.y})`"
          :opacity="r.dim ? 0.18 : 1"
          class="cursor-pointer select-none"
          @pointerdown="onNodePointerDown($event, r.n)"
        >
          <rect
            :width="NODE_W" :height="NODE_H" rx="10"
            :fill="selectedId === r.n.id ? '#eef2ff' : '#ffffff'"
            :stroke="selectedId === r.n.id ? '#4f46e5' : '#e2e8f0'"
            :stroke-width="selectedId === r.n.id ? 1.8 : 1"
            style="filter: drop-shadow(0 1px 2px rgb(15 23 42 / 0.06))"
          />
          <rect :width="4" :height="NODE_H" rx="2" :fill="r.style.color" />
          <text :x="16" :y="21" class="fill-slate-800" style="font-size: 12.5px; font-weight: 600">
            {{ truncate(r.n.label, 11) }}
          </text>
          <text :x="16" :y="37" class="fill-slate-400" style="font-size: 10px">
            {{ r.style.label }}<template v-if="r.n.domain"> · {{ truncate(r.n.domain, 8) }}</template>
          </text>
        </g>
      </g>
    </svg>

    <div class="pointer-events-none absolute bottom-3 left-3 rounded-lg bg-white/85 px-3 py-1.5 text-[11.5px] text-slate-400 shadow-sm backdrop-blur">
      拖拽空白平移 · 滚轮缩放 · 双击适应视图 · 拖动节点调整布局
    </div>
  </div>
</template>
