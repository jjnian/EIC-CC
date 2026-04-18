<template>
  <div class="page-wrap">
  <!-- Topbar -->
  <div class="topbar">
    <div class="breadcrumb">
      <span class="bc-dim">推演</span>
      <span class="bc-sep">/</span>
      <span class="bc-muted bc-link" @click="router.push('/graphs')">图谱列表</span>
      <span class="bc-sep">/</span>
      <span class="bc-cur">{{ graphMeta?.name || '图谱' }}</span>
      <span class="bc-star">☆</span>
    </div>
    <div class="tb-tools">
      <span class="tb-badge ok">● {{ store.nodes.length }} 节点</span>
      <span class="tb-badge">{{ store.edges.length }} 关系</span>
      <button class="tb-btn" @click="exportGraph">导出</button>
      <button class="tb-btn hi" @click="router.push('/graphs')">← 返回列表</button>
    </div>
  </div>

  <!-- Editor content -->
  <div class="content">
    <div class="graph-area">
      <GraphCanvas />
      <NodeInfo :node="store.selectedNode" @close="store.selectNode(null)" />
    </div>
    <div :class="['resize-divider', divDragging && 'dragging']" @mousedown="startDivider" />
    <ChatPanel :width="chatW" />
  </div>
  </div><!-- /page-wrap -->
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import GraphCanvas from '../components/GraphCanvas.vue'
import NodeInfo from '../components/NodeInfo.vue'
import ChatPanel from '../components/ChatPanel.vue'
import { useGraphStore } from '../stores/graph.js'
import { useGraphsStore } from '../stores/graphs.js'

const router = useRouter()
const route = useRoute()
const store = useGraphStore()
const graphsStore = useGraphsStore()

// 30% of available width (total - sidebar 48px)
function initChatW() {
  return Math.round((window.innerWidth - 48) * 0.30)
}
const chatW = ref(initChatW())
const divDragging = ref(false)

const graphMeta = computed(() =>
  graphsStore.graphs.find(g => g.id === route.params.id)
)

watch(
  [() => store.nodes.length, () => store.edges.length],
  ([nc, ec]) => {
    if (route.params.id) graphsStore.updateMeta(route.params.id, nc, ec)
  }
)

let divState = null
function startDivider(e) {
  e.preventDefault()
  divState = { sx: e.clientX, sw: chatW.value }
  divDragging.value = true
}
function onDivMove(e) {
  if (!divState) return
  const maxW = Math.round((window.innerWidth - 48) * 0.55)
  chatW.value = Math.max(240, Math.min(maxW, divState.sw + (divState.sx - e.clientX)))
}
function onDivUp() { divState = null; divDragging.value = false }

function exportGraph() {
  const data = { nodes: store.nodes, edges: store.edges }
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = (graphMeta.value?.name || 'graph') + '.json'; a.click()
  URL.revokeObjectURL(url)
}

function onResize() {
  chatW.value = initChatW()
}

onMounted(() => {
  window.addEventListener('mousemove', onDivMove)
  window.addEventListener('mouseup', onDivUp)
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  window.removeEventListener('mousemove', onDivMove)
  window.removeEventListener('mouseup', onDivUp)
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.page-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
  width: 100%;
}
.bc-link { cursor: pointer; transition: color .15s; }
.bc-link:hover { color: #4a7aaa !important; }
</style>
