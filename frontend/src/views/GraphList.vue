<template>
  <div class="page-wrap">
  <!-- Topbar -->
  <div class="topbar">
    <div class="breadcrumb">
      <span class="bc-dim">推演</span>
      <span class="bc-sep">/</span>
      <span class="bc-cur">图谱列表</span>
    </div>
    <div class="tb-tools">
      <button class="tb-btn hi" @click="showNew = true">
        <span style="font-size:14px;line-height:1">+</span> 新建图谱
      </button>
    </div>
  </div>

  <!-- Scrollable body -->
  <div class="list-body">
    <!-- Hero -->
    <div class="list-hero">
      <div class="list-hero-label">模型空间</div>
      <h1 class="list-hero-title">本体图谱</h1>
      <p class="list-hero-sub">构建实体关系模型，通过 AI 推演助手自动扩展图谱结构</p>
    </div>

    <!-- Search bar -->
    <div class="list-bar">
      <div class="list-search">
        <span class="list-search-icon">◎</span>
        <input v-model="q" class="list-search-input" placeholder="搜索图谱…" />
      </div>
      <div class="list-count">{{ filtered.length }} 个图谱</div>
    </div>

    <!-- Grid -->
    <div class="list-grid">
      <!-- New card -->
      <div class="graph-card graph-card-new" @click="showNew = true">
        <div class="card-new-icon">+</div>
        <div class="card-new-label">新建图谱</div>
        <div class="card-new-sub">从空白开始，或让 AI 生成初始结构</div>
      </div>

      <!-- Graph cards -->
      <div
        v-for="g in filtered" :key="g.id"
        class="graph-card"
        @click="open(g.id)"
      >
        <div class="card-preview">
          <GraphMiniPreview :graph="g" />
        </div>
        <div class="card-body">
          <div class="card-name">{{ g.name }}</div>
          <div class="card-desc">{{ g.desc || '暂无描述' }}</div>
          <div class="card-tags">
            <span v-for="t in g.tags" :key="t" class="card-tag">{{ t }}</span>
          </div>
        </div>
        <div class="card-footer">
          <div class="card-stats">
            <span class="card-stat ok">● {{ g.nodeCount }} 节点</span>
            <span class="card-stat">{{ g.edgeCount }} 关系</span>
          </div>
          <div class="card-date">{{ g.updatedAt }}</div>
          <button class="card-del" @click.stop="del(g.id)" title="删除">×</button>
        </div>
      </div>
    </div>

    <div v-if="!filtered.length && q" class="list-empty">
      未找到「{{ q }}」相关图谱
    </div>
  </div>

  <!-- New graph modal -->
  <Teleport to="body">
    <div v-if="showNew" class="modal-mask" @click.self="showNew = false">
      <div class="modal">
        <div class="modal-head">
          <span class="modal-title">新建图谱</span>
          <button class="modal-close" @click="showNew = false">×</button>
        </div>
        <div class="modal-body">
          <label class="field-label">图谱名称</label>
          <input
            ref="nameRef"
            v-model="newName"
            class="field-input"
            placeholder="例如：供应链本体图"
            @keydown.enter="create"
          />
          <label class="field-label" style="margin-top:14px">描述（可选）</label>
          <textarea
            v-model="newDesc"
            class="field-input field-textarea"
            placeholder="简短描述这个图谱的用途…"
            rows="3"
          />
        </div>
        <div class="modal-foot">
          <button class="modal-cancel" @click="showNew = false">取消</button>
          <button class="modal-confirm" @click="create" :disabled="!newName.trim()">创建并打开</button>
        </div>
      </div>
    </div>
  </Teleport>
  </div><!-- /page-wrap -->
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useGraphsStore } from '../stores/graphs.js'
import GraphMiniPreview from '../components/GraphMiniPreview.vue'

const router = useRouter()
const store = useGraphsStore()

const q = ref('')
const showNew = ref(false)
const newName = ref('')
const newDesc = ref('')
const nameRef = ref(null)

const filtered = computed(() =>
  store.graphs.filter(g =>
    !q.value || g.name.toLowerCase().includes(q.value.toLowerCase())
  )
)

watch(showNew, v => {
  if (v) {
    newName.value = ''
    newDesc.value = ''
    nextTick(() => nameRef.value?.focus())
  }
})

function open(id) {
  store.activeId = id
  router.push('/editor/' + id)
}

function del(id) {
  if (confirm('确定删除该图谱？')) store.deleteGraph(id)
}

function create() {
  if (!newName.value.trim()) return
  const id = store.createGraph(newName.value.trim(), newDesc.value.trim())
  showNew.value = false
  store.activeId = id
  router.push('/editor/' + id)
}
</script>

<style scoped>
.page-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
  width: 100%;
}

.list-body {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 60px;
}

.list-hero {
  padding: 40px 28px 24px;
}
.list-hero-label {
  font-size: 10.5px; font-weight: 600; color: #2a5a8a;
  text-transform: uppercase; letter-spacing: .1em;
  margin-bottom: 10px; font-family: 'JetBrains Mono', monospace;
}
.list-hero-title {
  font-size: 28px; font-weight: 700; color: #c5d8eb;
  letter-spacing: -.5px; margin-bottom: 8px;
}
.list-hero-sub { font-size: 13px; color: #2d4a66; max-width: 440px; line-height: 1.6; }

.list-bar {
  display: flex; align-items: center; gap: 12px;
  padding: 0 28px 18px;
}
.list-search {
  display: flex; align-items: center; gap: 8px;
  background: #0a0f18; border: 1px solid #111c2c; border-radius: 7px;
  padding: 0 12px; height: 32px; flex: 1; max-width: 300px;
  transition: border-color .15s;
}
.list-search:focus-within { border-color: #1a3a6a; }
.list-search-icon { color: #1e3348; font-size: 12px; }
.list-search-input {
  background: none; border: none; outline: none;
  color: #8ab4d0; font-size: 12px; flex: 1;
  font-family: 'Space Grotesk', sans-serif;
}
.list-search-input::placeholder { color: #1e3348; }
.list-count { font-size: 10.5px; color: #1e3348; font-family: 'JetBrains Mono', monospace; }

.list-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px; padding: 0 28px;
}

/* Cards */
.graph-card {
  background: #070b12; border: 1px solid #111c2c;
  border-radius: 10px; cursor: pointer;
  transition: all .2s; overflow: hidden;
  display: flex; flex-direction: column;
}
.graph-card:hover {
  border-color: #1a3a6a;
  box-shadow: 0 4px 24px #00000066, 0 0 0 1px #1a3a6a44;
  transform: translateY(-2px);
}
.graph-card-new {
  border-style: dashed; border-color: #0f1c2c;
  align-items: center; justify-content: center;
  padding: 36px 20px; gap: 10px; min-height: 190px;
}
.graph-card-new:hover { border-color: #1a5fcc66; }
.card-new-icon {
  width: 40px; height: 40px;
  background: #0a1e38; border: 1px solid #1a3a6a;
  border-radius: 9px; display: flex; align-items: center; justify-content: center;
  font-size: 20px; color: #2a5a9a; transition: all .15s;
}
.graph-card-new:hover .card-new-icon { background: #0d2444; color: #4a8acc; }
.card-new-label { font-size: 12.5px; font-weight: 600; color: #4a6a8a; }
.card-new-sub { font-size: 11px; color: #1e3348; text-align: center; line-height: 1.5; }

.card-preview {
  height: 100px; background: #050810;
  border-bottom: 1px solid #0d1824; flex-shrink: 0; overflow: hidden;
}
.card-body { padding: 12px 14px 8px; flex: 1; }
.card-name { font-size: 13px; font-weight: 600; color: #a8c8e0; margin-bottom: 5px; }
.card-desc {
  font-size: 11px; color: #2d4a66; line-height: 1.5; margin-bottom: 7px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.card-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.card-tag {
  font-size: 9px; color: #2a5a7a; background: #0a1828;
  border: 1px solid #0f2038; border-radius: 3px; padding: 2px 6px;
  font-family: 'JetBrains Mono', monospace;
}
.card-footer {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 14px; border-top: 1px solid #0d1824;
}
.card-stats { display: flex; gap: 8px; flex: 1; }
.card-stat { font-size: 9.5px; color: #1e3348; font-family: 'JetBrains Mono', monospace; }
.card-stat.ok { color: #00aa66; }
.card-date { font-size: 9px; color: #1a2e44; font-family: 'JetBrains Mono', monospace; }
.card-del {
  background: none; border: none; color: #1a2e44;
  cursor: pointer; font-size: 15px; line-height: 1; padding: 0 2px; transition: color .15s;
}
.card-del:hover { color: #cc3344; }

.list-empty {
  text-align: center; padding: 60px; font-size: 12px; color: #1e3348;
  font-family: 'JetBrains Mono', monospace;
}

/* Modal */
.modal-mask {
  position: fixed; inset: 0; background: #00000088;
  backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.modal {
  background: #070b12; border: 1px solid #1a3a6a;
  border-radius: 12px; width: 400px;
  box-shadow: 0 24px 80px #000000bb;
}
.modal-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 15px 18px; border-bottom: 1px solid #111c2c;
}
.modal-title { font-size: 13.5px; font-weight: 600; color: #8ab4d0; }
.modal-close {
  background: none; border: none; color: #1e3348; cursor: pointer;
  font-size: 18px; line-height: 1; transition: color .15s;
}
.modal-close:hover { color: #cc3344; }
.modal-body { padding: 18px; }
.field-label {
  display: block; font-size: 10.5px; color: #2d4a66; margin-bottom: 6px;
  text-transform: uppercase; letter-spacing: .05em; font-family: 'JetBrains Mono', monospace;
}
.field-input {
  width: 100%; background: #0a0f18; border: 1px solid #111c2c;
  border-radius: 6px; color: #a8c8e0; font-size: 13px;
  padding: 8px 12px; outline: none;
  font-family: 'Space Grotesk', sans-serif; transition: border-color .15s;
}
.field-input:focus { border-color: #1a3a6a; }
.field-input::placeholder { color: #1e3348; }
.field-textarea { resize: none; }
.modal-foot {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 12px 18px; border-top: 1px solid #111c2c;
}
.modal-cancel {
  background: none; border: 1px solid #111c2c; color: #2d4a66;
  padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 12px;
  font-family: 'Space Grotesk', sans-serif; transition: all .15s;
}
.modal-cancel:hover { border-color: #1a3a6a; color: #4a6a8a; }
.modal-confirm {
  background: #0a1e38; border: 1px solid #1a5fcc44; color: #4a8acc;
  padding: 6px 18px; border-radius: 6px; cursor: pointer; font-size: 12px;
  font-weight: 600; font-family: 'Space Grotesk', sans-serif; transition: all .15s;
}
.modal-confirm:hover:not(:disabled) { background: #0d2444; color: #7ab8f0; }
.modal-confirm:disabled { opacity: .3; cursor: not-allowed; }
</style>
