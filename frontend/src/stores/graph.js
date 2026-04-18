import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const NT = {
  entity:   { color: '#3d9bff', bg: '#071d3a', label: '实体' },
  process:  { color: '#ffaa22', bg: '#221500', label: '流程' },
  event:    { color: '#22dd88', bg: '#002418', label: '事件' },
  data:     { color: '#bb77ff', bg: '#170a2a', label: '数据源' },
  external: { color: '#ff6644', bg: '#220800', label: '外部系统' },
}

const INIT_NODES = [
  { id: 'n1',  label: '供应商',   type: 'entity',   x: 130,  y: 330 },
  { id: 'n2',  label: '原材料订单', type: 'process', x: 360,  y: 190 },
  { id: 'n3',  label: '工厂',     type: 'entity',   x: 360,  y: 400 },
  { id: 'n4',  label: '产品',     type: 'entity',   x: 610,  y: 280 },
  { id: 'n5',  label: '配送中心', type: 'process',  x: 860,  y: 170 },
  { id: 'n6',  label: '客户',     type: 'entity',   x: 1090, y: 260 },
  { id: 'n7',  label: '订单',     type: 'process',  x: 860,  y: 360 },
  { id: 'n8',  label: '仓库',     type: 'entity',   x: 610,  y: 470 },
  { id: 'n9',  label: 'ERP系统',  type: 'external', x: 130,  y: 490 },
  { id: 'n10', label: '时序数据', type: 'data',     x: 1090, y: 450 },
]

const INIT_EDGES = [
  { id: 'e1',  from: 'n1', to: 'n2',  label: '提供' },
  { id: 'e2',  from: 'n2', to: 'n3',  label: '输入' },
  { id: 'e3',  from: 'n3', to: 'n4',  label: '生产' },
  { id: 'e4',  from: 'n4', to: 'n5',  label: '发货' },
  { id: 'e5',  from: 'n5', to: 'n6',  label: '送达' },
  { id: 'e6',  from: 'n6', to: 'n7',  label: '下单' },
  { id: 'e7',  from: 'n7', to: 'n5',  label: '触发' },
  { id: 'e8',  from: 'n3', to: 'n8',  label: '入库' },
  { id: 'e9',  from: 'n8', to: 'n5',  label: '调拨' },
  { id: 'e10', from: 'n9', to: 'n3',  label: '驱动' },
  { id: 'e11', from: 'n7', to: 'n10', label: '记录' },
]

export const useGraphStore = defineStore('graph', () => {
  const nodes = ref([...INIT_NODES.map(n => ({ ...n }))])
  const edges = ref([...INIT_EDGES.map(e => ({ ...e }))])
  const selectedId = ref(null)

  const nodeMap = computed(() => Object.fromEntries(nodes.value.map(n => [n.id, n])))
  const selectedNode = computed(() => nodeMap.value[selectedId.value] || null)

  function moveNode(id, x, y) {
    const n = nodes.value.find(n => n.id === id)
    if (n) { n.x = x; n.y = y }
  }

  function selectNode(id) { selectedId.value = id }

  function addNodes(newNodes) {
    newNodes.forEach(n => nodes.value.push({ ...n, isNew: true }))
    setTimeout(() => {
      nodes.value.forEach(n => { n.isNew = false })
    }, 800)
  }

  function addEdges(newEdges) {
    newEdges.forEach(e => edges.value.push({ ...e }))
  }

  return { nodes, edges, selectedId, nodeMap, selectedNode, moveNode, selectNode, addNodes, addEdges }
})
