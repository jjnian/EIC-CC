<template>
  <div
    :class="['node-info', node && 'open', resizing && 'resizing']"
    :style="node ? { height: panelHeight + 'px' } : {}"
  >
    <template v-if="node">
      <div class="ni-drag-handle" @mousedown="startResize" />
      <div class="ni-inner">
        <!-- Left list -->
        <div class="ni-list">
          <div class="ni-list-head">
            <div class="ni-list-dot" :style="{ background: t.color, boxShadow: `0 0 6px ${t.color}88` }" />
            <div class="ni-list-title">{{ node.label }}</div>
            <div class="ni-list-badge" :style="{ color: t.color, borderColor: t.color + '44', background: t.bg }">{{ t.label }}</div>
            <button class="ni-list-close" @click="$emit('close')">×</button>
          </div>
          <div class="ni-list-body">
            <div v-if="!connectedNodes.length" style="padding:10px 12px;font-size:11px;color:#1e3348">暂无关联节点</div>
            <div
              v-for="(cn, i) in connectedNodes" :key="i"
              class="ni-list-item"
            >
              <div class="ni-list-item-dot" :style="{ background: ntOf(cn).color }" />
              <div class="ni-list-item-info">
                <div class="ni-list-item-label">{{ cn.label }}</div>
                <div class="ni-list-item-sub">{{ cn.dir === 'out' ? `→ ${cn.rel}` : `← ${cn.rel}` }} · {{ ntOf(cn).label }}</div>
              </div>
              <div class="ni-list-item-arrow">{{ cn.dir === 'out' ? '▶' : '◀' }}</div>
            </div>
          </div>
        </div>

        <!-- Right content -->
        <div class="ni-right">
          <div class="ni-tabs">
            <button
              v-for="(lb, i) in tabs" :key="i"
              :class="['ni-tab', tab === i && 'on']"
              @click="tab = i"
            >{{ lb }}</button>
          </div>
          <div class="ni-body">
            <!-- 概览 -->
            <template v-if="tab === 0">
              <div class="ni-section">
                <div class="ni-section-title">节点详情</div>
                <div class="ni-row"><div class="ni-key">ID</div><div class="ni-val">{{ node.id }}</div></div>
                <div class="ni-row"><div class="ni-key">名称</div><div class="ni-val">{{ node.label }}</div></div>
                <div class="ni-row"><div class="ni-key">类型</div><div class="ni-val">{{ t.label }}</div></div>
                <div class="ni-row"><div class="ni-key">状态</div><div class="ni-val green">● 已激活</div></div>
                <div class="ni-row"><div class="ni-key">坐标</div><div class="ni-val">({{ Math.round(node.x) }}, {{ Math.round(node.y) }})</div></div>
              </div>
              <div class="ni-section">
                <div class="ni-section-title">关系统计</div>
                <div class="ni-row"><div class="ni-key">出向关系</div><div class="ni-val amber">{{ outgoing.length }} 条</div></div>
                <div class="ni-row"><div class="ni-key">入向关系</div><div class="ni-val amber">{{ incoming.length }} 条</div></div>
                <div class="ni-row"><div class="ni-key">总连接数</div><div class="ni-val">{{ outgoing.length + incoming.length }}</div></div>
                <div class="ni-row"><div class="ni-key">创建时间</div><div class="ni-val">2026-04-18</div></div>
                <div class="ni-row"><div class="ni-key">数据来源</div><div class="ni-val">AI 推演助手</div></div>
              </div>
            </template>
            <!-- 关系 -->
            <template v-else-if="tab === 1">
              <div class="ni-section" style="flex:1">
                <div class="ni-section-title">所有关系 ({{ outgoing.length + incoming.length }})</div>
                <div v-for="e in outgoing" :key="e.id" class="ni-row">
                  <div class="ni-key" style="color:#3d9bff">→ 输出</div>
                  <div class="ni-val">
                    <span style="color:#ffaa22">{{ e.label }}</span>
                    <span style="color:#253a52"> → </span>
                    {{ nodeMap[e.to]?.label }}
                  </div>
                </div>
                <div v-for="e in incoming" :key="e.id" class="ni-row">
                  <div class="ni-key" style="color:#22dd88">← 输入</div>
                  <div class="ni-val">
                    {{ nodeMap[e.from]?.label }}
                    <span style="color:#253a52"> → </span>
                    <span style="color:#ffaa22">{{ e.label }}</span>
                  </div>
                </div>
                <div v-if="!outgoing.length && !incoming.length" style="color:#1e3348;font-size:11px;padding-top:4px">暂无关系</div>
              </div>
            </template>
            <!-- 属性 -->
            <template v-else-if="tab === 2">
              <div class="ni-section">
                <div class="ni-section-title">元数据属性</div>
                <div v-for="[k, v] in meta" :key="k" class="ni-row">
                  <div class="ni-key">{{ k }}</div><div class="ni-val">{{ v }}</div>
                </div>
              </div>
            </template>
            <!-- Schema -->
            <template v-else>
              <div class="ni-section">
                <div class="ni-section-title">Schema 定义</div>
                <div v-for="[k, v] in schema" :key="k" class="ni-row">
                  <div class="ni-key">{{ k }}</div><div class="ni-val">{{ v }}</div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { NT } from '../stores/graph.js'
import { useGraphStore } from '../stores/graph.js'

const props = defineProps({ node: Object })
defineEmits(['close'])

const store = useGraphStore()
const tab = ref(0)
const panelHeight = ref(200)
const resizing = ref(false)

const t = computed(() => props.node ? NT[props.node.type] || NT.entity : NT.entity)
const nodeMap = computed(() => store.nodeMap)
const outgoing = computed(() => store.edges.filter(e => e.from === props.node?.id))
const incoming = computed(() => store.edges.filter(e => e.to === props.node?.id))

const connectedNodes = computed(() => {
  if (!props.node) return []
  const out = outgoing.value.map(e => ({ ...nodeMap.value[e.to], rel: e.label, dir: 'out' })).filter(x => x.id)
  const inc = incoming.value.map(e => ({ ...nodeMap.value[e.from], rel: e.label, dir: 'in' })).filter(x => x.id)
  return [...out, ...inc]
})

const tabs = ['概览', '关系', '属性', 'Schema']
const meta = [['版本', 'v1.0'], ['创建者', 'AI 推演助手'], ['更新时间', '2026-04-18 16:30'], ['数据质量', '98.2%'], ['置信度', '高']]
const schema = computed(() => [
  ['label', 'String (required)'],
  ['type', (t.value?.label || '') + ' (enum)'],
  ['id', 'String (PK)'],
  ['x', 'Float'],
  ['y', 'Float'],
])

function ntOf(n) { return NT[n.type] || NT.entity }

let resizeStart = null
function startResize(e) {
  e.preventDefault()
  resizeStart = { y: e.clientY, h: panelHeight.value }
  resizing.value = true
}
function onResizeMove(e) {
  if (!resizeStart) return
  const delta = resizeStart.y - e.clientY
  panelHeight.value = Math.max(80, Math.min(520, resizeStart.h + delta))
}
function onResizeUp() {
  resizeStart = null
  resizing.value = false
}

onMounted(() => {
  window.addEventListener('mousemove', onResizeMove)
  window.addEventListener('mouseup', onResizeUp)
})
onUnmounted(() => {
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', onResizeUp)
})
</script>
