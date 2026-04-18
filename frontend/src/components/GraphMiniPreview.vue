<template>
  <svg width="100%" height="100%" :viewBox="viewBox" preserveAspectRatio="xMidYMid meet">
    <!-- Edges -->
    <g v-for="e in edges" :key="e.id">
      <path :d="e.d" fill="none" :stroke="e.color" stroke-width="1" opacity="0.4" :marker-end="`url(#mini-arr-${uid})`"/>
    </g>
    <!-- Nodes -->
    <g v-for="n in nodesScaled" :key="n.id">
      <rect :x="n.x" :y="n.y" :width="n.w" :height="n.h" rx="2" :fill="n.bg" :stroke="n.color" stroke-width="0.8" opacity="0.85"/>
      <rect :x="n.x" :y="n.y" width="2" :height="n.h" rx="0" :fill="n.color"/>
      <text :x="n.x + 10" :y="n.y + n.h/2 + 3" :style="{ fontSize: '6px', fill: '#c5d8eb', fontFamily: 'Space Grotesk' }">{{ n.label }}</text>
    </g>
    <defs>
      <marker :id="`mini-arr-${uid}`" markerWidth="5" markerHeight="5" refX="4" refY="2" orient="auto">
        <path d="M0,0.5 L0,3.5 L4,2 Z" fill="#2a4a6a"/>
      </marker>
    </defs>
  </svg>
</template>

<script setup>
import { computed } from 'vue'
import { NT } from '../stores/graph.js'

const props = defineProps({ graph: Object })

// Stable unique id per component instance
const uid = Math.random().toString(36).slice(2, 7)

// Fake mini layout based on graph metadata — deterministic from graph id
const MINI_NODES = [
  { id: 'a', label: '实体 A', type: 'entity',   x: 20,  y: 15 },
  { id: 'b', label: '流程',  type: 'process',  x: 100, y: 8  },
  { id: 'c', label: '实体 B', type: 'entity',   x: 100, y: 40 },
  { id: 'd', label: '数据源', type: 'data',     x: 180, y: 25 },
  { id: 'e', label: '事件',  type: 'event',    x: 180, y: 55 },
  { id: 'f', label: '外部',  type: 'external', x: 260, y: 38 },
]
const MINI_EDGES = [
  { id: 'e1', from: 'a', to: 'b' },
  { id: 'e2', from: 'a', to: 'c' },
  { id: 'e3', from: 'b', to: 'd' },
  { id: 'e4', from: 'c', to: 'e' },
  { id: 'e5', from: 'd', to: 'f' },
  { id: 'e6', from: 'e', to: 'f' },
]
const NW = 52, NH = 14

const nodesScaled = computed(() =>
  MINI_NODES.map(n => ({
    ...n, w: NW, h: NH,
    color: NT[n.type]?.color || '#3d9bff',
    bg: NT[n.type]?.bg || '#071d3a',
  }))
)

const nodeMap = computed(() => Object.fromEntries(nodesScaled.value.map(n => [n.id, n])))

const edges = computed(() =>
  MINI_EDGES.map(e => {
    const fn = nodeMap.value[e.from], tn = nodeMap.value[e.to]
    if (!fn || !tn) return null
    const x1 = fn.x + NW / 2, y1 = fn.y + NH / 2
    const x2 = tn.x + NW / 2, y2 = tn.y + NH / 2
    const cp = Math.max(Math.abs(x2 - x1) * .4, 20)
    return {
      ...e,
      d: `M${x1},${y1} C${x1+cp},${y1} ${x2-cp},${y2} ${x2},${y2}`,
      color: NT[tn.type]?.color || '#2a4f72',
    }
  }).filter(Boolean)
)

const viewBox = '-10 -5 340 90'
</script>
