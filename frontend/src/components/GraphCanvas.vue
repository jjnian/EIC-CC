<template>
  <div
    ref="canvasRef"
    :class="['graph-canvas', panning && 'panning']"
    @mousedown.self="startPan"
    @wheel.prevent="onWheel"
    @click.self="store.selectNode(null)"
  >
    <svg style="position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;overflow:visible">
      <defs>
        <!-- Arrowhead markers per node type color -->
        <marker
          v-for="(t, k) in NT" :key="'m-' + k"
          :id="'arr-' + k"
          markerWidth="10" markerHeight="10"
          refX="9" refY="3.5" orient="auto"
        >
          <path d="M0,0.5 L0,6.5 L9,3.5 Z" :fill="t.color" opacity="0.9"/>
        </marker>
        <!-- Highlighted marker -->
        <marker id="arr-hl" markerWidth="10" markerHeight="10" refX="9" refY="3.5" orient="auto">
          <path d="M0,0.5 L0,6.5 L9,3.5 Z" fill="#fff" opacity="0.95"/>
        </marker>
        <!-- Gradient per edge -->
        <linearGradient
          v-for="e in edgesWithNodes" :key="'g-' + e.id"
          :id="'grad-' + e.id"
          gradientUnits="userSpaceOnUse"
          :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
        >
          <stop offset="0%"   :stop-color="NT[e.fromType]?.color || '#3d9bff'" stop-opacity="0.5"/>
          <stop offset="100%" :stop-color="NT[e.toType]?.color  || '#3d9bff'" stop-opacity="0.9"/>
        </linearGradient>
      </defs>

      <g :transform="`translate(${vp.x},${vp.y}) scale(${vp.s})`">
        <g v-for="e in edgesWithNodes" :key="e.id">
          <!-- Glow halo when highlighted -->
          <path
            v-if="isHighlighted(e)"
            :d="e.d" fill="none"
            :stroke="NT[e.toType]?.color || '#3d9bff'"
            stroke-width="12" opacity="0.07"
          />

          <!-- Main edge: gradient stroke -->
          <path
            :d="e.d" fill="none"
            :stroke="isHighlighted(e) ? `url(#grad-${e.id})` : `url(#grad-${e.id})`"
            :stroke-width="isHighlighted(e) ? 2.2 : 1.5"
            :marker-end="isHighlighted(e) ? 'url(#arr-hl)' : `url(#arr-${e.toType})`"
            :opacity="isHighlighted(e) ? 1 : 0.55"
          />

          <!-- Flow animation: dashes moving in direction of edge -->
          <path
            :d="e.d" fill="none"
            :stroke="NT[e.toType]?.color || '#3d9bff'"
            :stroke-width="isHighlighted(e) ? 2 : 1.2"
            stroke-dasharray="5 14"
            :class="isHighlighted(e) ? 'flow-fast' : 'flow-slow'"
            :opacity="isHighlighted(e) ? 0.9 : 0.35"
            :marker-end="isHighlighted(e) ? 'url(#arr-hl)' : `url(#arr-${e.toType})`"
          />

          <!-- Source dot -->
          <circle
            :cx="e.x1" :cy="e.y1" r="3"
            :fill="NT[e.fromType]?.color || '#3d9bff'"
            opacity="0.7"
          />

          <!-- Edge label -->
          <g v-if="e.label">
            <rect
              :x="e.mx - 22" :y="e.my - 17"
              width="44" height="14" rx="3"
              fill="#050810" opacity="0.92"
            />
            <text
              :x="e.mx" :y="e.my - 6"
              text-anchor="middle"
              :style="{
                fill: isHighlighted(e) ? '#c5d8eb' : '#4a7aaa',
                fontSize: '9.5px', fontFamily: 'JetBrains Mono', fontWeight: 500
              }"
            >{{ e.label }}</text>
          </g>
        </g>
      </g>
    </svg>

    <!-- Nodes layer -->
    <div :style="{ transform: `translate(${vp.x}px,${vp.y}px) scale(${vp.s})`, transformOrigin: '0 0', position: 'absolute', top: 0, left: 0 }">
      <div
        v-for="n in store.nodes" :key="n.id"
        :class="['node', n.isNew && 'node-new', store.selectedId === n.id && 'node-sel']"
        :style="nodeStyle(n)"
        @mousedown.stop="startDrag($event, n.id)"
        @click.stop="store.selectNode(n.id)"
      >
        <div class="node-dot" :style="dotStyle(n)" />
        <div class="node-label">{{ n.label }}</div>
        <div class="node-type">{{ NT[n.type]?.label }}</div>
      </div>
    </div>

    <!-- Zoom controls -->
    <div class="zoom-wrap">
      <button @click="zoom(1.2)">+</button>
      <button @click="zoom(0.85)">−</button>
      <button @click="resetVp" style="font-size:9px">复位</button>
    </div>

    <!-- Legend -->
    <div class="legend">
      <div v-for="(t, k) in NT" :key="k" class="legend-row">
        <div class="legend-sq" :style="{ background: t.color }" />
        <span>{{ t.label }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useGraphStore, NT } from '../stores/graph.js'

const store = useGraphStore()
const canvasRef = ref(null)
const vp = ref({ x: 30, y: 30, s: 1 })
const panning = ref(false)
const NW = 172, NH = 44

const nodeMap = computed(() => store.nodeMap)

// Pre-compute edge geometry so template stays clean
const edgesWithNodes = computed(() => {
  return store.edges.flatMap(e => {
    const fn = nodeMap.value[e.from], tn = nodeMap.value[e.to]
    if (!fn || !tn) return []
    const x1 = fn.x + NW / 2, y1 = fn.y + NH / 2
    const x2 = tn.x + NW / 2, y2 = tn.y + NH / 2
    const dx = Math.abs(x2 - x1), cp = Math.max(dx * .5, 50)
    return [{
      ...e,
      x1, y1, x2, y2,
      d: `M${x1},${y1} C${x1+cp},${y1} ${x2-cp},${y2} ${x2},${y2}`,
      mx: (x1 + x2) / 2,
      my: (y1 + y2) / 2,
      fromType: fn.type,
      toType: tn.type,
    }]
  })
})

function isHighlighted(e) {
  return store.selectedId === e.from || store.selectedId === e.to
}

function nodeStyle(n) {
  const t = NT[n.type] || NT.entity
  const sel = store.selectedId === n.id
  return {
    left: n.x + 'px', top: n.y + 'px', width: NW + 'px',
    background: t.bg, borderLeftColor: t.color,
    borderTopColor: sel ? t.color + '44' : '#111c2c',
    borderRightColor: sel ? t.color + '44' : '#111c2c',
    borderBottomColor: sel ? t.color + '44' : '#111c2c',
    boxShadow: sel ? `0 0 0 1px ${t.color}33, 0 4px 24px ${t.color}1a` : '0 2px 8px #00000066',
  }
}

function dotStyle(n) {
  const t = NT[n.type] || NT.entity
  const sel = store.selectedId === n.id
  return { background: t.color, boxShadow: sel ? `0 0 6px ${t.color}88` : '' }
}

let dragState = null
function startDrag(e, id) {
  e.preventDefault()
  store.selectNode(id)
  const n = store.nodeMap[id]
  dragState = { id, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y }
}

let panState = null
function startPan(e) {
  panState = { sx: e.clientX - vp.value.x, sy: e.clientY - vp.value.y }
  panning.value = true
}

function onMouseMove(e) {
  if (dragState) {
    const { id, sx, sy, ox, oy } = dragState
    store.moveNode(id, ox + (e.clientX - sx) / vp.value.s, oy + (e.clientY - sy) / vp.value.s)
  } else if (panState) {
    vp.value = { ...vp.value, x: e.clientX - panState.sx, y: e.clientY - panState.sy }
  }
}

function onMouseUp() {
  dragState = null; panState = null; panning.value = false
}

function onWheel(e) {
  const rect = canvasRef.value.getBoundingClientRect()
  const mx = e.clientX - rect.left, my = e.clientY - rect.top
  const f = e.deltaY < 0 ? 1.12 : 0.9
  const ns = Math.max(.2, Math.min(3, vp.value.s * f))
  vp.value = {
    x: mx - (mx - vp.value.x) * (ns / vp.value.s),
    y: my - (my - vp.value.y) * (ns / vp.value.s),
    s: ns,
  }
}

function zoom(f) {
  vp.value = { ...vp.value, s: Math.max(.2, Math.min(3, vp.value.s * f)) }
}

function resetVp() { vp.value = { x: 30, y: 30, s: 1 } }

onMounted(() => {
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
})
onUnmounted(() => {
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
})
</script>

<style scoped>
/* Flow animation: dashes march in the direction of the path */
@keyframes flow {
  from { stroke-dashoffset: 0; }
  to   { stroke-dashoffset: -19; }
}
@keyframes flow-fast {
  from { stroke-dashoffset: 0; }
  to   { stroke-dashoffset: -19; }
}

.flow-slow {
  animation: flow 1.8s linear infinite;
}
.flow-fast {
  animation: flow-fast 0.7s linear infinite;
}
</style>
