<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue';

const props = defineProps<{
  nodes: any[];
  edges: any[];
  width: number;
  seed?: { text: string; files: File[] } | null;
}>();

const emit = defineEmits<{
  (e: 'update', addNodes: any[], addEdges: any[]): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
}>();

// ========== 会话管理 ==========

interface Conversation {
  id: string;
  createdAt: number;
  title: string;
  msgs: any[];
}

const STORAGE_KEY = 'eic-conversations';
const conversationId = ref('');
const conversationTitle = ref('新对话');
const showConvPicker = ref(false);

const loadConversations = (): Record<string, Conversation> => {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
  } catch { return {}; }
};

const saveConversations = (convs: Record<string, Conversation>) => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(convs));
};

const autoTitle = (msgs: any[]): string => {
  const firstUser = msgs.find(m => m.role === 'u');
  if (!firstUser) return '新对话';
  const t = firstUser.text.trim();
  return t.length > 20 ? t.slice(0, 20) + '…' : t;
};

const persistCurrent = () => {
  const convs = loadConversations();
  if (convs[conversationId.value]) {
    convs[conversationId.value].msgs = JSON.parse(JSON.stringify(msgs.value));
    convs[conversationId.value].title = autoTitle(msgs.value);
  }
  saveConversations(convs);
};

const initConversation = (id?: string) => {
  if (id && id !== 'new') {
    const convs = loadConversations();
    const conv = convs[id];
    if (conv) {
      conversationId.value = conv.id;
      conversationTitle.value = conv.title;
      msgs.value = JSON.parse(JSON.stringify(conv.msgs));
      return;
    }
  }
  // 新建对话
  conversationId.value = Date.now().toString();
  conversationTitle.value = '新对话';
  msgs.value = [
    { role: 'a', text: '你好！我是推演助手。\n\n用自然语言描述实体和关系，我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试：「添加一个财务审计实体，与客户相关联」' }
  ];
  emit('clear-graph');
};

const newConversation = () => {
  showConvPicker.value = false;
  initConversation('new');
};

const switchConversation = (id: string) => {
  showConvPicker.value = false;
  initConversation(id);
};

const deleteConversation = (id: string, e: Event) => {
  e.stopPropagation();
  if (!confirm('确定删除这条对话记录？')) return;
  const convs = loadConversations();
  delete convs[id];
  saveConversations(convs);
  if (id === conversationId.value) {
    initConversation('new');
  }
};

const sortedConversations = (): Conversation[] => {
  const convs = loadConversations();
  return Object.values(convs).sort((a, b) => b.createdAt - a.createdAt);
};

// ========== 消息 & 模型 ==========

const msgs = ref<any[]>([
  { role: 'a', text: '你好！我是推演助手。\n\n用自然语言描述实体和关系，我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试：「添加一个财务审计实体，与客户相关联」' }
]);
const input = ref('');
const loading = ref(false);
const atts = ref<{name: string, type: string}[]>([]);
const fileRef = ref<HTMLInputElement | null>(null);
const msgsRef = ref<HTMLElement | null>(null);

// 模型选择
interface ModelOption {
  id: string;
  name: string;
  type: 'preset' | 'custom';
  configId?: string;
}

const currentModel = ref<ModelOption | null>(null);
const availableModels = ref<ModelOption[]>([]);
const showModelPicker = ref(false);

const loadModels = async () => {
  try {
    const res = await fetch('/api/config');
    if (res.ok) {
      const data = await res.json();
      const models: ModelOption[] = [];

      if (data.provider && data.modelName) {
        const provider = (data.providers || []).find((p: any) => p.code === data.provider);
        const modelName = data.modelName;
        if (provider && provider.models) {
          provider.models.forEach((m: string) => {
            models.push({ id: m, name: m, type: 'preset' });
          });
        } else {
          models.push({ id: modelName, name: modelName, type: 'preset' });
        }
      }

      if (data.customModels) {
        data.customModels
          .filter((m: any) => m.enabled)
          .forEach((m: any) => {
            models.push({ id: m.id, name: m.name, type: 'custom', configId: m.id });
          });
      }

      availableModels.value = models;
      if (models.length > 0) {
        currentModel.value = models[0];
      }
    }
  } catch (e) {
    console.error("Failed to load models", e);
  }
};

const selectModel = (model: ModelOption) => {
  currentModel.value = model;
  showModelPicker.value = false;
};

onMounted(() => {
  loadModels();
  // 如果父组件传入种子消息，则开始一个全新对话并发送
  if (props.seed && (props.seed.text || props.seed.files.length)) {
    initConversation('new');
    consumeSeed(props.seed);
    return;
  }
  // 恢复上一次打开的会话
  const convs = loadConversations();
  const ids = Object.keys(convs);
  if (ids.length > 0) {
    // 选最近的一条
    const latest = ids.reduce((a, b) => convs[a].createdAt > convs[b].createdAt ? a : b);
    initConversation(latest);
  }
});

const consumeSeed = (seed: { text: string; files: File[] }) => {
  seed.files.forEach(f => addFile(f));
  input.value = seed.text;
  emit('seed-consumed');
  nextTick(() => { send(); });
};

watch(() => props.seed, (newSeed) => {
  if (newSeed && (newSeed.text || newSeed.files.length)) {
    initConversation('new');
    consumeSeed(newSeed);
  }
});

// 每次消息变化自动保存
watch(msgs, () => {
  nextTick(() => {
    if (msgsRef.value) msgsRef.value.scrollTop = msgsRef.value.scrollHeight;
  });
  persistCurrent();
}, { deep: true });

const addFile = (f: File) => {
  const ext = f.name.split('.').pop()?.toLowerCase() || '';
  atts.value.push({ name: f.name, type: ext });
};

const send = async () => {
  if (!input.value.trim() && !atts.value.length) return;
  const txt = input.value;
  const ua = [...atts.value];
  msgs.value.push({ role: 'u', text: txt, atts: ua });
  input.value = '';
  atts.value = [];
  loading.value = true;

  // 首次发消息自动更新标题
  if (conversationTitle.value === '新对话') {
    conversationTitle.value = autoTitle(msgs.value);
  }

  // 创建 AI 消息占位（流式显示用）
  const aiMsg = { role: 'a' as const, text: '' };
  msgs.value.push(aiMsg);

  try {
    // 构建对话历史（排除刚创建的空白 AI 消息）
    const history = msgs.value
      .filter(m => m !== aiMsg && (m.role === 'u' || m.role === 'a') && m.text)
      .slice(-40)
      .map(m => ({ role: m.role === 'u' ? 'user' : 'assistant', content: m.text }));

    const body: any = { message: txt, history };
    if (currentModel.value?.configId) {
      body.configId = currentModel.value.configId;
    } else if (currentModel.value?.type === 'preset') {
      body.modelOverride = currentModel.value.id;
    }

    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
      },
      body: JSON.stringify(body)
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: '请求失败' }));
      aiMsg.text = `请求失败: ${err.error}`;
      loading.value = false;
      return;
    }

    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let currentEvent = '';
    let currentData = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          currentEvent = line.slice(7);
        } else if (line.startsWith('data: ')) {
          currentData = line.slice(6);

          if (currentEvent === 'text') {
            aiMsg.text += currentData;
          } else if (currentEvent === 'complete') {
            try {
              const data = JSON.parse(currentData);
              if (!aiMsg.text && data.reply) {
                aiMsg.text = data.reply;
              }

              const nodeOffset = Math.random() * 50 - 25;
              const cx = 400 + nodeOffset;
              const cy = 300 + nodeOffset;
              const r = 150;

              const pNodes = (data.add_nodes || []).map((n: any, idx: number, arr: any[]) => {
                const angle = (idx / arr.length) * Math.PI * 2;
                return { ...n, x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r };
              });

              emit('update', pNodes, data.add_edges || []);
            } catch (parseErr) {
              console.error('Failed to parse complete event:', parseErr);
            }
          } else if (currentEvent === 'error') {
            if (!aiMsg.text) {
              aiMsg.text = `错误: ${currentData}`;
            } else {
              aiMsg.text += `\n\n[错误] ${currentData}`;
            }
          }

          currentEvent = '';
          currentData = '';
        }
      }
    }

    if (!aiMsg.text) {
      aiMsg.text = '未收到有效回复';
    }
  } catch (error: any) {
    const lastAi = [...msgs.value].reverse().find(m => m.role === 'a');
    if (lastAi && !(lastAi as any).text) {
      (lastAi as any).text = `网络或解析错误: ${error.message}`;
    }
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
          <div class="bubble" :class="{ streaming: m.role === 'a' && !m.text }">{{ m.text }}<span v-if="loading && m.role === 'a'" class="cursor" /></div>
        </div>
      </div>
    </div>
    <div v-if="atts.length > 0" class="att-row">
      <div v-for="(a, i) in atts" :key="i" class="att-chip">
        <span>{{ a.name }}</span><button @click="atts = atts.filter((_, j) => j !== i)">×</button>
      </div>
    </div>
    <div class="ch-input-area">
      <div class="toolbar" v-if="!input && !atts.length">
        <!-- 会话切换 -->
        <button class="conv-selector" @click="showConvPicker = !showConvPicker" :title="conversationTitle">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <span class="conv-title">{{ conversationTitle }}</span>
          <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <button class="new-conv-btn" @click="newConversation" title="新建对话">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </button>
        <!-- 会话下拉 -->
        <div class="conv-dropdown" v-if="showConvPicker">
          <div class="conv-dropdown-item" @click="newConversation">
            <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span>新建对话</span>
          </div>
          <div class="conv-divider" v-if="sortedConversations().length > 0"></div>
          <div class="conv-dropdown-item history"
               v-for="c in sortedConversations()"
               :key="c.id"
               :class="{ active: c.id === conversationId }"
               @click="switchConversation(c.id)">
            <span class="conv-name">{{ c.title }}</span>
            <span class="conv-time">{{ new Date(c.createdAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}</span>
            <button class="conv-del" @click="deleteConversation(c.id, $event)" title="删除">×</button>
          </div>
        </div>
        <!-- 模型选择 -->
        <button class="model-selector" @click="showModelPicker = !showModelPicker" :title="'当前模型: ' + (currentModel?.name || '未选择')">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <span>{{ currentModel?.name || '选择模型' }}</span>
          <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <div class="model-dropdown" v-if="showModelPicker">
          <div class="model-group-label">预设模型</div>
          <div class="model-dropdown-item"
               v-for="m in availableModels.filter(x => x.type === 'preset')"
               :key="m.id"
               :class="{ active: m.id === currentModel?.id }"
               @click="selectModel(m)">
            {{ m.name }}
            <span class="model-check" v-if="m.id === currentModel?.id">✓</span>
          </div>
          <div class="model-group-label" v-if="availableModels.some(x => x.type === 'custom')">自定义模型</div>
          <div class="model-dropdown-item"
               v-for="m in availableModels.filter(x => x.type === 'custom')"
               :key="m.id"
               :class="{ active: m.id === currentModel?.id }"
               @click="selectModel(m)">
            {{ m.name }}
            <span class="model-check" v-if="m.id === currentModel?.id">✓</span>
          </div>
          <div class="model-dropdown-item custom" @click="showModelPicker = false">关闭</div>
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
.ch-head-l { display: flex; align-items: center; gap: 10px; }
.ch-pulse {
  width: 8px; height: 8px; border-radius: 50%;
  background: #42b883; box-shadow: 0 0 8px #42b883;
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}
.ch-head-l span { font-size: 15px; font-weight: 600; color: var(--text-main); }
.ch-stat { font-size: 12px; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }
.ch-msgs {
  flex: 1; overflow-y: auto; padding: 20px;
  display: flex; flex-direction: column; gap: 16px;
}
.msg { display: flex; gap: 10px; max-width: 90%; }
.msg-user { align-self: flex-end; flex-direction: row-reverse; }
.msg-asst { align-self: flex-start; }
.avatar {
  width: 32px; height: 32px; border-radius: 8px;
  background: linear-gradient(135deg, #42b883, #3d9bff);
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; font-weight: 700; color: white; flex-shrink: 0;
}
.msg-body { display: flex; flex-direction: column; gap: 6px; }
.bubble {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px; padding: 12px 16px;
  font-size: 14px; line-height: 1.6; color: var(--text-main);
  white-space: pre-wrap; word-break: break-word;
}
.cursor {
  display: inline-block;
  width: 2px; height: 1em;
  background: #42b883;
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-end infinite;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.msg-user .bubble { background: rgba(66, 184, 131, 0.15); border-color: rgba(66, 184, 131, 0.25); }
.att-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.att-sm { font-size: 11px; background: rgba(255,255,255,0.08); padding: 2px 8px; border-radius: 4px; color: var(--text-dim); }
.att-row { display: flex; flex-wrap: wrap; gap: 8px; padding: 8px 20px; }
.att-chip {
  display: flex; align-items: center; gap: 6px;
  background: rgba(66, 184, 131, 0.12); border: 1px solid rgba(66, 184, 131, 0.25);
  padding: 4px 10px; border-radius: 6px; font-size: 12px; color: #42b883;
}
.att-chip button { background: none; border: none; color: #42b883; cursor: pointer; font-size: 14px; padding: 0; line-height: 1; }
.ch-input-area { padding: 16px 20px; border-top: 1px solid rgba(255,255,255,0.06); }
.toolbar { position: relative; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
.conv-selector {
  display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0;
  background: rgba(10, 16, 27, 0.8); border: 1px solid rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.7); padding: 6px 12px; border-radius: 8px;
  font-size: 12px; cursor: pointer; transition: all 0.2s;
  font-family: 'JetBrains Mono', monospace;
}
.conv-selector:hover { border-color: #42b883; color: white; }
.conv-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.new-conv-btn {
  background: rgba(10, 16, 27, 0.8); border: 1px solid rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.5); padding: 6px 8px; border-radius: 8px;
  cursor: pointer; transition: all 0.2s; display: flex; align-items: center;
}
.new-conv-btn:hover { border-color: #42b883; color: #42b883; }
.conv-dropdown {
  position: absolute; bottom: 100%; left: 0; right: 0; margin-bottom: 6px;
  background: rgba(14, 25, 41, 0.98); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px; padding: 4px; z-index: 100;
  box-shadow: 0 8px 32px rgba(0,0,0,0.4); max-height: 300px; overflow-y: auto;
}
.conv-dropdown-item {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 12px; border-radius: 6px; font-size: 13px;
  color: rgba(255,255,255,0.7); cursor: pointer; transition: all 0.15s;
  font-family: 'JetBrains Mono', monospace;
}
.conv-dropdown-item:hover { background: rgba(66, 184, 131, 0.15); color: white; }
.conv-dropdown-item.active { background: rgba(66, 184, 131, 0.2); color: #42b883; }
.conv-dropdown-item.history { justify-content: space-between; }
.conv-divider { height: 1px; background: rgba(255,255,255,0.06); margin: 4px 8px; }
.conv-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-time { font-size: 10px; color: rgba(255,255,255,0.3); white-space: nowrap; flex-shrink: 0; }
.conv-del {
  background: none; border: none; color: rgba(255,255,255,0.3);
  font-size: 16px; cursor: pointer; padding: 0 4px; line-height: 1;
  transition: color 0.15s;
}
.conv-del:hover { color: #ff6644; }
.model-bar { position: relative; margin-bottom: 12px; }
.model-selector {
  display: flex; align-items: center; gap: 6px;
  background: rgba(10, 16, 27, 0.8); border: 1px solid rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.7); padding: 6px 12px; border-radius: 8px;
  font-size: 12px; cursor: pointer; transition: all 0.2s;
  font-family: 'JetBrains Mono', monospace;
}
.model-selector:hover { border-color: #42b883; color: white; }
.model-dropdown {
  position: absolute; bottom: 100%; left: 0; margin-bottom: 6px;
  background: rgba(14, 25, 41, 0.98); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px; padding: 4px; min-width: 240px;
  max-height: 360px; overflow-y: auto; z-index: 100;
  box-shadow: 0 8px 32px rgba(0,0,0,0.4);
}
.model-group-label {
  font-size: 10px; text-transform: uppercase; letter-spacing: 1px;
  color: rgba(255,255,255,0.3); padding: 6px 12px 4px;
  font-family: 'Inter', sans-serif;
}
.model-dropdown-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 12px; border-radius: 6px; font-size: 13px;
  color: rgba(255,255,255,0.7); cursor: pointer; transition: all 0.15s;
  font-family: 'JetBrains Mono', monospace;
}
.model-dropdown-item:hover { background: rgba(66, 184, 131, 0.15); color: white; }
.model-dropdown-item.active { background: rgba(66, 184, 131, 0.2); color: #42b883; }
.model-check { font-size: 12px; color: #42b883; }
.model-dropdown-item.custom {
  color: var(--text-dim); justify-content: center; margin-top: 4px;
  border-top: 1px solid rgba(255,255,255,0.06); padding-top: 8px;
}
.model-dropdown-item.custom:hover { background: rgba(255,255,255,0.06); color: var(--text-main); }
.input-box {
  background: rgba(10, 16, 27, 0.6); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 12px; padding: 10px 14px; transition: border-color 0.2s;
}
.input-box:focus-within { border-color: rgba(66, 184, 131, 0.4); }
.ch-input {
  width: 100%; background: transparent; border: none; color: white;
  font-size: 14px; resize: none; outline: none; font-family: inherit;
}
.ch-input::placeholder { color: rgba(255,255,255,0.3); }
.input-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.file-tools { display: flex; gap: 6px; }
.file-icon-btn {
  background: none; border: none; color: rgba(255,255,255,0.4);
  cursor: pointer; padding: 4px; border-radius: 4px; transition: all 0.2s;
  display: flex; align-items: center; justify-content: center;
}
.file-icon-btn:hover { color: #42b883; background: rgba(66,184,131,0.1); }
.send-btn {
  background: #42b883; border: none; color: #002418;
  width: 32px; height: 32px; border-radius: 8px; cursor: pointer;
  display: flex; align-items: center; justify-content: center; transition: all 0.2s;
}
.send-btn:hover:not(:disabled) { background: #50caa3; transform: translateY(-1px); }
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
