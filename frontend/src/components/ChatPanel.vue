<template>
  <div class="chat-panel" :style="{ width: width + 'px' }">
    <div class="ch-head">
      <div class="ch-head-l">
        <div class="ch-pulse" />
        <span>AI 推演助手</span>
      </div>
      <div class="ch-stat">{{ store.nodes.length }}节点·{{ store.edges.length }}关系</div>
    </div>

    <div class="ch-msgs" ref="msgsRef">
      <div
        v-for="(m, i) in msgs" :key="i"
        :class="['msg', m.role === 'u' ? 'msg-user' : 'msg-asst']"
      >
        <div v-if="m.role === 'a'" class="avatar">推</div>
        <div class="msg-body">
          <div v-if="m.atts?.length" class="att-tags">
            <span v-for="(a, j) in m.atts" :key="j" class="att-sm">{{ a.name }}</span>
          </div>
          <div class="bubble">{{ m.text }}</div>
        </div>
      </div>
      <div v-if="loading" class="msg msg-asst">
        <div class="avatar">推</div>
        <div class="msg-body">
          <div class="bubble">
            <div class="loading-dots"><span/><span/><span/></div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="atts.length" class="att-row">
      <div v-for="(a, i) in atts" :key="i" class="att-chip">
        <span>{{ a.name }}</span>
        <button @click="atts.splice(i, 1)">×</button>
      </div>
    </div>

    <div class="quick-row">
      <button
        v-for="(t, k) in NT" :key="k"
        class="q-btn"
        :style="{ '--c': t.color }"
        @click="input = (input ? input + ' ' : '') + '添加' + t.label + '节点'"
      >+{{ t.label }}</button>
    </div>

    <div class="ch-input-area">
      <div class="file-row">
        <span class="file-row-label">附加：</span>
        <button
          v-for="f in FILES" :key="f.label"
          class="f-btn"
          :style="{ '--c': f.color }"
          :title="`上传 ${f.label}`"
          @click="clickFile(f)"
        >{{ f.label[0] }}</button>
        <input ref="fileRef" type="file" style="display:none" @change="onFileChange" />
      </div>
      <div class="input-row">
        <textarea
          class="ch-input"
          v-model="input"
          placeholder="描述实体、关系或数据结构…"
          :rows="2"
          @keydown.enter.exact.prevent="send"
        />
        <button class="send-btn" @click="send" :disabled="loading">→</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import axios from 'axios'
import { useGraphStore, NT } from '../stores/graph.js'

defineProps({ width: Number })

const store = useGraphStore()
const msgs = ref([{
  role: 'a',
  text: '你好！我是推演助手。\n\n用自然语言描述实体和关系，我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试：「添加一个财务审计实体，与客户相关联」',
}])
const input = ref('')
const loading = ref(false)
const atts = ref([])
const msgsRef = ref(null)
const fileRef = ref(null)

const FILES = [
  { label: 'Word',  color: '#2b5eb8', accept: '.doc,.docx' },
  { label: 'PDF',   color: '#cc2222', accept: '.pdf' },
  { label: '图片',  color: '#22aa66', accept: 'image/*' },
  { label: 'CSV',   color: '#8844cc', accept: '.csv,.xlsx,.json' },
  { label: 'DB',    color: '#cc7700', accept: '' },
]

function scrollBottom() {
  nextTick(() => {
    if (msgsRef.value) msgsRef.value.scrollTop = msgsRef.value.scrollHeight
  })
}

function clickFile(f) {
  if (f.accept) {
    fileRef.value.accept = f.accept
    fileRef.value.click()
  } else {
    atts.value.push({ name: '[数据库连接]', type: 'data' })
  }
}

function onFileChange(e) {
  const f = e.target.files[0]
  if (f) atts.value.push({ name: f.name, type: f.name.split('.').pop().toLowerCase() })
  e.target.value = ''
}

async function send() {
  if (!input.value.trim() && !atts.value.length) return
  const txt = input.value
  const ua = [...atts.value]
  msgs.value.push({ role: 'u', text: txt, atts: ua })
  input.value = ''
  atts.value = []
  loading.value = true
  scrollBottom()

  try {
    const res = await axios.post('/api/chat', {
      message: txt,
      attachments: ua.map(a => a.name),
      nodes: store.nodes.map(n => ({ id: n.id, label: n.label, type: n.type })),
      edges: store.edges.map(e => ({ from: e.from, to: e.to, label: e.label })),
    })
    const data = res.data
    msgs.value.push({ role: 'a', text: data.reply || '图谱已更新。' })
    if (data.add_nodes?.length) store.addNodes(data.add_nodes)
    if (data.add_edges?.length) store.addEdges(data.add_edges)
  } catch (err) {
    msgs.value.push({ role: 'a', text: '出现错误，请检查后端服务。' })
  }

  loading.value = false
  scrollBottom()
}
</script>
