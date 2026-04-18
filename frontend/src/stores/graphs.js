import { defineStore } from 'pinia'
import { ref } from 'vue'

const DEMO_GRAPHS = [
  {
    id: 'g1',
    name: '供应链本体图',
    desc: '涵盖供应商、工厂、配送中心等核心实体及其协作流程',
    nodeCount: 10,
    edgeCount: 11,
    updatedAt: '2026-04-18',
    tags: ['供应链', '物流', 'ERP'],
  },
]

export const useGraphsStore = defineStore('graphs', () => {
  const graphs = ref(JSON.parse(localStorage.getItem('tuiyan_graphs') || 'null') || DEMO_GRAPHS)
  const activeId = ref(null)

  function save() {
    localStorage.setItem('tuiyan_graphs', JSON.stringify(graphs.value))
  }

  function createGraph(name, desc = '') {
    const id = 'g_' + Date.now()
    graphs.value.push({
      id,
      name: name || '未命名图谱',
      desc,
      nodeCount: 0,
      edgeCount: 0,
      updatedAt: new Date().toISOString().slice(0, 10),
      tags: [],
    })
    save()
    return id
  }

  function deleteGraph(id) {
    graphs.value = graphs.value.filter(g => g.id !== id)
    save()
  }

  function updateMeta(id, nodeCount, edgeCount) {
    const g = graphs.value.find(g => g.id === id)
    if (g) {
      g.nodeCount = nodeCount
      g.edgeCount = edgeCount
      g.updatedAt = new Date().toISOString().slice(0, 10)
      save()
    }
  }

  return { graphs, activeId, createGraph, deleteGraph, updateMeta }
})
