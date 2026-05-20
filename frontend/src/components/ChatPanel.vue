<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { streamSSE, type SSEController } from '../composables/useSSE';
import { useConversations, type ChatMsg } from '../composables/useConversations';
import { useAttachments } from '../composables/useAttachments';
import { useMention } from '../composables/useMention';
import { useChatModels } from '../composables/useChatModels';
import ChatMessageList from './chat/ChatMessageList.vue';
import AttachmentChips from './chat/AttachmentChips.vue';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  width: number;
  seed?: { text: string; files: File[] } | null;
}>();

const emit = defineEmits<{
  (e: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[]): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
}>();

// ===== 消息/输入 状态 =====
const msgs = ref<ChatMsg[]>([
  { role: 'a', text: '你好!我是推演助手。\n\n用自然语言描述实体和关系,我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试:「添加一个财务审计实体,与客户相关联」' }
]);
const input = ref('');
const loading = ref(false);
const fileRef = ref<HTMLInputElement | null>(null);
const msgListRef = ref<InstanceType<typeof ChatMessageList> | null>(null);
const inputRef = ref<HTMLTextAreaElement | null>(null);

// ===== SSE 流控制 =====
let chatStream: SSEController | null = null;
const abortChat = () => {
  if (chatStream) { try { chatStream.abort(); } catch { /* noop */ } chatStream = null; }
};

// ===== composables 接线 =====
const conv = useConversations({
  msgs,
  abortChat,
  clearGraph: () => emit('clear-graph'),
});
const {
  conversationId,
  conversationTitle,
  showConvPicker,
  autoTitle,
  persistCurrent,
  initConversation,
  newConversation,
  switchConversation,
  deleteConversation,
  sortedConversations,
  restoreLatestOrNew,
} = conv;

const { atts, addFile } = useAttachments();

const mention = useMention({
  input,
  inputRef,
  nodes: () => props.nodes || [],
  edges: () => props.edges || [],
});
const {
  mentionOpen,
  mentionQuery,
  mentionIndex,
  mentionItems,
  checkMention,
  selectMention,
  handleKeydown: handleMentionKeydown,
} = mention;

const models = useChatModels();
const {
  currentModel,
  showModelPicker,
  presetModels,
  customModels,
  loadModels,
  selectModel,
} = models;

// ===== 输入框键盘 / 事件 =====
const onInputKeydown = (e: KeyboardEvent) => {
  if (handleMentionKeydown(e)) return;
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    send();
  }
};
const onInputEvent = () => { nextTick(() => checkMention()); };
const onInputClick = () => { nextTick(() => checkMention()); };

// ===== 启动:加载模型 + 恢复/接收 seed =====
const consumeSeed = (seed: { text: string; files: File[] }) => {
  seed.files.forEach(f => addFile(f));
  input.value = seed.text;
  emit('seed-consumed');
  nextTick(() => { send(); });
};

onMounted(() => {
  loadModels();
  if (props.seed && (props.seed.text || props.seed.files.length)) {
    initConversation('new');
    consumeSeed(props.seed);
    return;
  }
  restoreLatestOrNew();
});

watch(() => props.seed, (newSeed) => {
  if (newSeed && (newSeed.text || newSeed.files.length)) {
    initConversation('new');
    consumeSeed(newSeed);
  }
});

onBeforeUnmount(() => abortChat());

// 每次消息变化:滚到底 + localStorage 持久化
watch(msgs, () => {
  msgListRef.value?.scrollToBottom();
  persistCurrent();
}, { deep: true });

// ===== 发送 =====
const send = async () => {
  if (!input.value.trim() && !atts.value.length) return;
  mention.closeMention();

  // 等待所有附件读取完成
  if (atts.value.some(a => a.loading)) {
    loading.value = true;
    while (atts.value.some(a => a.loading)) {
      await new Promise(r => setTimeout(r, 80));
    }
    loading.value = false;
  }

  const validAtts = atts.value.filter(a => !a.error);
  const failed = atts.value.filter(a => a.error);

  const txt = input.value;
  const uaDisplay = atts.value.map(a => ({ name: a.name, type: a.type, kind: a.kind, error: a.error }));
  msgs.value.push({ role: 'u', text: txt, atts: uaDisplay });
  input.value = '';
  const requestAtts = [...validAtts];
  atts.value = [];
  loading.value = true;

  if (failed.length) {
    msgs.value.push({ role: 'a', text: '⚠ 部分文件未能加入:\n' + failed.map(a => `· ${a.name}: ${a.error}`).join('\n') });
  }

  // 首次发消息自动更新标题
  if (conversationTitle.value === '新对话') {
    conversationTitle.value = autoTitle(msgs.value);
  }

  // 创建 AI 消息占位(流式显示用)
  const aiMsg: ChatMsg = { role: 'a', text: '' };
  msgs.value.push(aiMsg);

  try {
    const history = msgs.value
      .filter(m => m !== aiMsg && (m.role === 'u' || m.role === 'a') && m.text)
      .slice(-40)
      .map(m => ({ role: m.role === 'u' ? 'user' : 'assistant', content: m.text }));

    // 文本附件拼入正文,图片作为独立 attachment
    let composedMessage = txt;
    const textAtts = requestAtts.filter(a => a.kind === 'text' && a.content);
    if (textAtts.length) {
      const docs = textAtts.map(a =>
        `=== 文件: ${a.name}${a.truncated ? ' (已截断)' : ''} ===\n${a.content}`
      ).join('\n\n');
      composedMessage = (txt ? txt + '\n\n' : '') + '附加文档内容:\n' + docs;
    }

    const imageAtts = requestAtts
      .filter(a => a.kind === 'image' && a.content)
      .map(a => ({ name: a.name, type: 'image', dataUrl: a.content }));

    const body: any = { message: composedMessage, history };
    if (imageAtts.length) body.attachments = imageAtts;
    if (currentModel.value?.configId) {
      body.configId = currentModel.value.configId;
    } else if (currentModel.value?.type === 'preset') {
      body.modelOverride = currentModel.value.id;
    }

    abortChat();

    await new Promise<void>((resolveStream) => {
      chatStream = streamSSE('/api/chat', body, {
        onEvent: (name, data) => {
          if (name === 'text') {
            aiMsg.text += data;
          } else if (name === 'complete') {
            try {
              const parsed = JSON.parse(data);
              if (!aiMsg.text && parsed.reply) aiMsg.text = parsed.reply;
              const nodeOffset = Math.random() * 50 - 25;
              const cx = 400 + nodeOffset;
              const cy = 300 + nodeOffset;
              const r = 150;
              const pNodes: OntologyNode[] = (parsed.add_nodes || []).map((n: OntologyNode, idx: number, arr: OntologyNode[]) => {
                const angle = (idx / arr.length) * Math.PI * 2;
                return { ...n, x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r };
              });
              emit('update', pNodes, parsed.add_edges || []);
            } catch (parseErr) {
              console.error('Failed to parse complete event:', parseErr);
            }
          } else if (name === 'error') {
            if (!aiMsg.text) aiMsg.text = `错误: ${data}`;
            else aiMsg.text += `\n\n[错误] ${data}`;
          }
        },
        onError: (err) => {
          if (!aiMsg.text) aiMsg.text = `网络或解析错误: ${err.message}`;
          resolveStream();
        },
        onComplete: () => {
          if (!aiMsg.text) aiMsg.text = '未收到有效回复';
          resolveStream();
        },
      });
    });
  } catch (error: any) {
    const lastAi = [...msgs.value].reverse().find(m => m.role === 'a');
    if (lastAi && !lastAi.text) {
      lastAi.text = `网络或解析错误: ${error.message}`;
    }
  } finally {
    chatStream = null;
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
    <ChatMessageList ref="msgListRef" :messages="msgs" :loading="loading" />
    <AttachmentChips :attachments="atts" @remove="(i) => atts = atts.filter((_, j) => j !== i)" />
    <div class="ch-input-area">
      <div class="toolbar" v-if="!input && !atts.length">
        <!-- 会话切换 -->
        <button class="conv-selector" type="button" @click="showConvPicker = !showConvPicker" :title="conversationTitle">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <span class="conv-title">{{ conversationTitle }}</span>
          <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <button class="new-conv-btn" type="button" @click="newConversation" title="新建对话">
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
        <button class="model-selector" type="button" @click="showModelPicker = !showModelPicker" :title="'当前模型: ' + (currentModel?.name || '未选择')">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <span>{{ currentModel?.name || '选择模型' }}</span>
          <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <div class="model-dropdown" v-if="showModelPicker">
          <div class="model-group-label">预设模型</div>
          <div class="model-dropdown-item"
               v-for="m in presetModels"
               :key="m.id"
               :class="{ active: m.id === currentModel?.id }"
               @click="selectModel(m)">
            {{ m.name }}
            <span class="model-check" v-if="m.id === currentModel?.id">✓</span>
          </div>
          <div class="model-group-label" v-if="customModels.length">自定义模型</div>
          <div class="model-dropdown-item"
               v-for="m in customModels"
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
        <!-- @ mention dropdown -->
        <div class="mention-dropdown" v-if="mentionOpen && mentionItems.length > 0">
          <div class="mention-header">
            <span>引用 {{ mentionQuery ? `"${mentionQuery}"` : '本体节点 / 关系' }}</span>
            <span class="mention-hint">↑↓ 选择 · Enter 确认 · Esc 取消</span>
          </div>
          <div
            v-for="(it, i) in mentionItems"
            :key="it.kind + ':' + it.id"
            class="mention-item"
            :class="{ active: i === mentionIndex, 'mention-edge': it.kind === 'edge' }"
            @mousedown.prevent="selectMention(it)"
            @mouseenter="mentionIndex = i"
          >
            <span class="mention-kind">{{ it.kind === 'node' ? '◆' : '→' }}</span>
            <span class="mention-label">{{ it.label }}</span>
            <span class="mention-sub">{{ it.sub }}</span>
          </div>
        </div>
        <textarea ref="inputRef" class="ch-input" v-model="input" placeholder="描述本体关系，输入 @ 可引用节点/关系，或附加文件…" @keydown="onInputKeydown" @input="onInputEvent" @click="onInputClick" rows="2" />
        <div class="input-footer">
          <div class="file-tools">
            <button class="file-icon-btn attach-btn" type="button" title="上传文件 (图片/MD/TXT/JSON等)" @click="() => { if (fileRef) fileRef.click(); }">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
            </button>
            <input ref="fileRef" type="file" multiple accept="image/*,.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,.html,.htm,.js,.ts,.py,.java,.sql,.toml,.ini,.env,.vue,.css,text/*" style="display:none" @change="(e: any) => { Array.from(e.target.files || []).forEach((f: any) => addFile(f)); e.target.value = ''; }" />
          </div>
          <button class="send-btn" type="button" @click="send" :disabled="loading">
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
.att-sm-err { background: rgba(255,99,99,0.12); color: #ff8a8a; }
.att-row { display: flex; flex-wrap: wrap; gap: 8px; padding: 8px 20px; }
.att-chip {
  display: flex; align-items: center; gap: 6px;
  background: rgba(66, 184, 131, 0.12); border: 1px solid rgba(66, 184, 131, 0.25);
  padding: 4px 10px; border-radius: 6px; font-size: 12px; color: #42b883;
}
.att-chip button { background: none; border: none; color: inherit; cursor: pointer; font-size: 14px; padding: 0; line-height: 1; }
.att-chip.att-img { background: rgba(99, 155, 255, 0.12); border-color: rgba(99, 155, 255, 0.25); color: #639bff; }
.att-chip.att-err { background: rgba(255, 99, 99, 0.12); border-color: rgba(255, 99, 99, 0.3); color: #ff8a8a; }
.att-kind { font-size: 12px; }
.att-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.att-spin {
  width: 10px; height: 10px; border: 2px solid currentColor; border-right-color: transparent;
  border-radius: 50%; animation: att-spin 0.8s linear infinite; opacity: 0.6;
}
@keyframes att-spin { to { transform: rotate(360deg); } }
.att-bad {
  width: 14px; height: 14px; border-radius: 50%; background: rgba(255,99,99,0.3);
  color: #fff; font-size: 10px; font-weight: 700; display: inline-flex;
  align-items: center; justify-content: center;
}
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
  position: relative;
}
.mention-dropdown {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  right: 0;
  background: rgba(14, 25, 41, 0.98);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(66, 184, 131, 0.3);
  border-radius: 10px;
  padding: 4px;
  max-height: 280px;
  overflow-y: auto;
  z-index: 200;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5);
}
.mention-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px 4px;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: rgba(255, 255, 255, 0.4);
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  margin-bottom: 4px;
}
.mention-hint {
  font-size: 9px;
  letter-spacing: 0.5px;
  color: rgba(255, 255, 255, 0.3);
}
.mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s;
  font-size: 13px;
}
.mention-item.active,
.mention-item:hover {
  background: rgba(66, 184, 131, 0.15);
}
.mention-item.mention-edge .mention-kind { color: #639bff; }
.mention-kind {
  font-size: 12px;
  color: #42b883;
  width: 14px;
  text-align: center;
  flex-shrink: 0;
}
.mention-label {
  color: var(--text-main);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 50%;
}
.mention-sub {
  font-size: 11px;
  color: var(--text-dim);
  margin-left: auto;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 50%;
  text-align: right;
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
