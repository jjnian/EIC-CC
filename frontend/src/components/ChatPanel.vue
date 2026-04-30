<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue';
import { NT } from '../constants';

const props = defineProps<{
  nodes: any[];
  edges: any[];
  width: number;
}>();

const emit = defineEmits<{
  (e: 'update', addNodes: any[], addEdges: any[]): void;
}>();

const msgs = ref([
  { role: 'a', text: '你好！我是推演助手。\n\n用自然语言描述实体和关系，我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试：「添加一个财务审计实体，与客户相关联」' }
]);
const input = ref('');
const loading = ref(false);
const atts = ref<{name: string, type: string}[]>([]);
const fileRef = ref<HTMLInputElement | null>(null);
const msgsRef = ref<HTMLElement | null>(null);

// 模型选择相关
const currentModel = ref('');
const availableModels = ref<string[]>([]);
const showModelPicker = ref(false);

// 加载当前模型和可用模型列表
const loadModels = async () => {
  try {
    const res = await fetch('/api/config');
    if (res.ok) {
      const data = await res.json();
      currentModel.value = data.modelName || '';
      const provider = (data.providers || []).find((p: any) => p.code === data.provider);
      availableModels.value = provider?.models || [];
    }
  } catch (e) {
    console.error("Failed to load models", e);
  }
};

onMounted(loadModels);

const selectModel = (model: string) => {
  currentModel.value = model;
  showModelPicker.value = false;
};

watch(msgs, () => {
  nextTick(() => {
    if (msgsRef.value) msgsRef.value.scrollTop = msgsRef.value.scrollHeight;
  });
}, { deep: true });

const addFile = (f: File) => {
  const ext = f.name.split('.').pop()?.toLowerCase() || '';
  atts.value.push({ name: f.name, type: ext });
};

const send = async () => {
  if (!input.value.trim() && !atts.value.length) return;
  const txt = input.value;
  const ua = [...atts.value];
  msgs.value.push({ role: 'u', text: txt, atts: ua } as any);
  input.value = '';
  atts.value = [];
  loading.value = true;

  try {
    const body: any = { message: txt, history: [] };
    if (currentModel.value) {
      body.modelOverride = currentModel.value;
    }
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    if (res.ok) {
      const data = await res.json();
      const nodeOffset = Math.random() * 50 - 25;

      const cx = 400 + nodeOffset;
      const cy = 300 + nodeOffset;
      const r = 150;

      const pNodes = (data.add_nodes || []).map((n: any, idx: number, arr: any[]) => {
        const angle = (idx / arr.length) * Math.PI * 2;
        return {
          ...n,
          x: cx + Math.cos(angle) * r,
          y: cy + Math.sin(angle) * r
        };
      });

      msgs.value.push({ role: 'a', text: data.reply || '图谱已更新。' });
      emit('update', pNodes, data.add_edges || []);
    } else {
      const err = await res.json();
      msgs.value.push({ role: 'a', text: `请求失败: ${err.error}` });
    }
  } catch (error: any) {
    msgs.value.push({ role: 'a', text: `网络或解析错误: ${error.message}` });
  } finally {
    loading.value = false;
  }
};
</script>

<template>
  <div class="chat-panel" :style="{ width: width + 'px' }">
    <div class="ch-head">
      <div class="ch-head-l"><div class="ch-pulse" /><span>AI 推演助手</span></div>
      <div class="ch-stat">{{ nodes.length }}节点·{{ edges.length }}关系</div>
    </div>
    <div class="ch-msgs" ref="msgsRef">
      <div v-for="(m, i) in msgs" :key="i" :class="['msg', `msg-${m.role === 'u' ? 'user' : 'asst'}`]">
        <div v-if="m.role === 'a'" class="avatar">推</div>
        <div class="msg-body">
          <div v-if="(m as any).atts?.length > 0" class="att-tags">
            <span v-for="(a, j) in (m as any).atts" :key="j" class="att-sm">{{ a.name }}</span>
          </div>
          <div class="bubble">{{ m.text }}</div>
        </div>
      </div>
      <div v-if="loading" class="msg msg-asst">
        <div class="avatar">推</div>
        <div class="msg-body">
          <div class="bubble">
            <div class="loading-dots"><span /><span /><span /></div>
          </div>
        </div>
      </div>
    </div>
    <div v-if="atts.length > 0" class="att-row">
      <div v-for="(a, i) in atts" :key="i" class="att-chip">
        <span>{{ a.name }}</span><button @click="atts = atts.filter((_, j) => j !== i)">×</button>
      </div>
    </div>
    <div class="ch-input-area">
      <!-- 模型选择器 -->
      <div class="model-bar" v-if="!input && !atts.length">
        <button class="model-selector" @click="showModelPicker = !showModelPicker" :title="'当前模型: ' + currentModel">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <span>{{ currentModel || '选择模型' }}</span>
          <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <!-- 模型下拉列表 -->
        <div class="model-dropdown" v-if="showModelPicker">
          <div class="model-dropdown-item"
               v-for="m in availableModels"
               :key="m"
               :class="{ active: m === currentModel }"
               @click="selectModel(m)">
            {{ m }}
            <span class="model-check" v-if="m === currentModel">✓</span>
          </div>
          <div class="model-dropdown-item custom" @click="showModelPicker = false">
            关闭
          </div>
        </div>
      </div>
      <div class="input-box">
        <textarea class="ch-input" v-model="input" placeholder="描述本体关系，或使用下方按钮附加文件…" @keydown.enter.prevent="send" rows="2" />
        <div class="input-footer">
          <div class="file-tools">
            <button class="file-icon-btn attach-btn" title="上传文件" @click="() => { if (fileRef) fileRef.click(); }">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
            </button>
            <input ref="fileRef" type="file" style="display:none" @change="(e: any) => e.target.files[0] && addFile(e.target.files[0])" />
          </div>
          <button class="send-btn" @click="send" :disabled="loading">
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="19" x2="12" y2="5"></line>
              <polyline points="5 12 12 5 19 12"></polyline>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: rgba(8, 14, 24, 0.6);
  border-left: 1px solid rgba(255, 255, 255, 0.06);
}

.ch-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.ch-head-l {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ch-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #42b883;
  box-shadow: 0 0 8px #42b883;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}

.ch-head-l span {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-main);
}

.ch-stat {
  font-size: 12px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}

.ch-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.msg {
  display: flex;
  gap: 10px;
  max-width: 90%;
}

.msg-user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.msg-asst {
  align-self: flex-start;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #42b883, #3d9bff);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: white;
  flex-shrink: 0;
}

.msg-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bubble {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-user .bubble {
  background: rgba(66, 184, 131, 0.15);
  border-color: rgba(66, 184, 131, 0.25);
}

.att-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.att-sm {
  font-size: 11px;
  background: rgba(255, 255, 255, 0.08);
  padding: 2px 8px;
  border-radius: 4px;
  color: var(--text-dim);
}

.loading-dots {
  display: flex;
  gap: 4px;
  padding: 8px 0;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-dim);
  animation: dotBounce 1.4s infinite ease-in-out both;
}

.loading-dots span:nth-child(1) { animation-delay: -0.32s; }
.loading-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes dotBounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.att-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 20px;
}

.att-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(66, 184, 131, 0.12);
  border: 1px solid rgba(66, 184, 131, 0.25);
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: #42b883;
}

.att-chip button {
  background: none;
  border: none;
  color: #42b883;
  cursor: pointer;
  font-size: 14px;
  padding: 0;
  line-height: 1;
}

.ch-input-area {
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

/* 模型选择器样式 */
.model-bar {
  position: relative;
  margin-bottom: 12px;
}

.model-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.7);
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  font-family: 'JetBrains Mono', monospace;
}

.model-selector:hover {
  border-color: #42b883;
  color: white;
}

.model-dropdown {
  position: absolute;
  bottom: 100%;
  left: 0;
  margin-bottom: 6px;
  background: rgba(14, 25, 41, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  padding: 6px;
  min-width: 200px;
  max-height: 300px;
  overflow-y: auto;
  z-index: 100;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}

.model-dropdown-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  transition: all 0.15s;
  font-family: 'JetBrains Mono', monospace;
}

.model-dropdown-item:hover {
  background: rgba(66, 184, 131, 0.15);
  color: white;
}

.model-dropdown-item.active {
  background: rgba(66, 184, 131, 0.2);
  color: #42b883;
}

.model-check {
  font-size: 12px;
  color: #42b883;
}

.model-dropdown-item.custom {
  color: var(--text-dim);
  justify-content: center;
  margin-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  padding-top: 8px;
}

.model-dropdown-item.custom:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-main);
}

.input-box {
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 10px 14px;
  transition: border-color 0.2s;
}

.input-box:focus-within {
  border-color: rgba(66, 184, 131, 0.4);
}

.ch-input {
  width: 100%;
  background: transparent;
  border: none;
  color: white;
  font-size: 14px;
  resize: none;
  outline: none;
  font-family: inherit;
}

.ch-input::placeholder {
  color: rgba(255, 255, 255, 0.3);
}

.input-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.file-tools {
  display: flex;
  gap: 6px;
}

.file-icon-btn {
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.4);
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-icon-btn:hover {
  color: #42b883;
  background: rgba(66, 184, 131, 0.1);
}

.send-btn {
  background: #42b883;
  border: none;
  color: #002418;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.send-btn:hover:not(:disabled) {
  background: #50caa3;
  transform: translateY(-1px);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.cmd-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
  align-items: center;
}

.cmd-label {
  font-size: 12px;
  color: var(--text-dim);
  margin-right: 4px;
}

.cmd-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.cmd-btn:hover {
  border-color: rgba(255, 255, 255, 0.2);
  color: var(--text-main);
}

.cmd-btn i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
</style>
