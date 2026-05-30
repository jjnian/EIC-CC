<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import type { OntologyNode, OntologyEdge, ChainStep } from '../types';
import { useConversations, type ChatMsg, type ChatMsgAttachment } from '../composables/useConversations';
import { useAttachments } from '../composables/useAttachments';
import { useMention } from '../composables/useMention';
import { useChatModels } from '../composables/useChatModels';
import { useChatSend } from '../composables/useChatSend';
import { usePredictionSync, type LivePrediction } from '../composables/usePredictionSync';
import { toast } from '../composables/useToast';
import ChatMessageList from './chat/ChatMessageList.vue';
import AttachmentChips from './chat/AttachmentChips.vue';
import AttachmentPreview from './chat/AttachmentPreview.vue';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  width: number;
  seed?: { text: string; files: File[] } | null;
  /**
   * 当前推演的实时状态(由 App.vue 从 usePrediction 透传下来)。
   * status: 0 空闲 1 运行中 2 完成 3 错误 4 已停止
   */
  livePrediction?: LivePrediction | null;
  modelId?: string;
}>();

const emit = defineEmits<{
  (e: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[]): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
  (e: 'focus-node', id: string): void;
  (e: 'abort-prediction'): void;
  (e: 'view-graph', modelId: string): void;
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
const previewAtt = ref<ChatMsgAttachment | null>(null);

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
  mentionListRef,
  checkMention,
  selectMention,
  handleKeydown: handleMentionKeydown,
} = mention;

const models = useChatModels();
const { currentModel, loadModels } = models;

// ===== Conversation 历史管理（先建好，sender 反向引用其 title）=====
const conv = useConversations({
  msgs,
  abortChat: () => sender.abortChat(),
  clearGraph: () => emit('clear-graph'),
});
const {
  conversationTitle,
  autoTitle,
  persistCurrent,
  initConversation,
  restoreLatestOrNew,
} = conv;

// ===== Chat 发送 + SSE 流式 =====
const sender = useChatSend({
  msgs,
  input,
  atts,
  loading,
  nodes: () => props.nodes || [],
  edges: () => props.edges || [],
  conversationTitle: () => conversationTitle.value,
  setConversationTitle: (t) => { conversationTitle.value = t; },
  autoTitle,
  currentModel,
  currentModelId: () => props.modelId || '',
  emit: (event, addNodes, addEdges) => emit(event, addNodes, addEdges),
  closeMention: () => mention.closeMention(),
});
const { send, abortChat } = sender;

// ===== 推演消息同步 =====
usePredictionSync({
  msgs,
  livePrediction: () => props.livePrediction ?? null,
  nodes: () => props.nodes || [],
});

// ===== 输入框键盘 / 事件 =====
const onInputKeydown = (e: KeyboardEvent) => {
  if (handleMentionKeydown(e)) return;
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    send();
  }
};
const onInputEvent = () => { nextTick(() => checkMention()); };
/** 直接在输入框里粘贴图片(截图/复制图片)→ 当成附件加上。文本粘贴照常。 */
const onInputPaste = (e: ClipboardEvent) => {
  const data = e.clipboardData;
  if (!data) return;
  const items = data.items;
  if (!items || items.length === 0) return;
  const images: File[] = [];
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    if (it.kind === 'file' && it.type.startsWith('image/')) {
      const f = it.getAsFile();
      if (f) {
        // 截图常常没有文件名,补一个带时间戳的 .png
        const named = f.name
          ? f
          : new File([f], `pasted-${Date.now()}.${(f.type.split('/')[1] || 'png').replace('+xml','')}`, { type: f.type });
        images.push(named);
      }
    }
  }
  if (images.length > 0) {
    e.preventDefault();
    images.forEach(addFile);
    toast.success(images.length === 1 ? '已粘贴图片' : `已粘贴 ${images.length} 张图片`);
  }
};
const onInputClick = () => { nextTick(() => checkMention()); };

// 用户点击 LLM 抛回的澄清问题选项 → 标记已答 + 把选项作为新一条用户消息发送
const onSelectQuestionOption = (messageIndex: number, option: { label: string; value?: string }) => {
  if (loading.value) return;
  const m = msgs.value[messageIndex];
  if (!m || !m.question || m.question.answered) return;
  m.question.answered = option.label;
  input.value = option.value || option.label;
  nextTick(() => { send(); });
};

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

// ===== 推演消息同步 =====
// 监听父级传下来的 livePrediction:
//   - 检测到 status 从 0→1 时(运行开始),往 msgs 推一条 role='prediction' 消息
//   - 后续 steps / status 变化时同步到这条消息上
let currentPredictionMsg: ChatMsg | null = null;
const seedLabel = (id: string) => props.nodes.find(n => n.id === id)?.label || id;
const buildSeedPairs = (ids: string[]) => ids.map(id => ({ id, label: seedLabel(id) }));
const statusToLabel = (s: 0 | 1 | 2 | 3 | 4): PredictionMsg['status'] =>
  s === 1 ? 'running' : s === 2 ? 'done' : s === 3 ? 'error' : s === 4 ? 'aborted' : 'done';

watch(() => props.livePrediction, (now, prev) => {
  if (!now) return;
  // 进入 running:新建一条推演消息(从非 running 跳到 running 视作新一轮)
  const enteredRunning = now.status === 1 && (!prev || prev.status !== 1);
  if (enteredRunning) {
    const msg: ChatMsg = {
      role: 'prediction',
      text: '',
      prediction: {
        intent: now.intent,
        seeds: buildSeedPairs(now.seeds),
        prompt: now.prompt,
        name: now.name,
        status: 'running',
        steps: [],
        pruneDetails: [],
      },
    };
    msgs.value.push(msg);
    currentPredictionMsg = msg;
  }
  // 同步增量到当前消息
  if (currentPredictionMsg && currentPredictionMsg.prediction) {
    const p = currentPredictionMsg.prediction;
    if (now.steps.length !== p.steps.length) {
      p.steps = now.steps.map(s => ({
        step: s.step, nodeId: s.nodeId, label: s.label, type: s.type,
        triggeredBy: s.triggeredBy, ruleId: s.ruleId,
        explanation: s.explanation, confidence: s.confidence,
      }));
    }
    if (now.pruneDetails?.length && (p.pruneDetails?.length || 0) !== now.pruneDetails.length) {
      p.pruneDetails = now.pruneDetails.slice();
    }
    p.status = statusToLabel(now.status);
    if (now.branchId) p.branchId = now.branchId;
    if (now.error) p.error = now.error;
    // 终态后释放,下一轮会新建
    if (p.status !== 'running') currentPredictionMsg = null;
  }
}, { deep: true });

// ===== 上下文 token 预估 =====
const estimateTokens = (text: string) => Math.ceil(text.length / 4);

const contextTokenEstimate = computed(() => {
  let total = 0;
  // 图谱上下文
  if (props.nodes?.length) {
    const nodesStr = JSON.stringify(props.nodes.map(n => ({ id: n.id, label: n.label, type: n.type })));
    total += estimateTokens(nodesStr);
  }
  if (props.edges?.length) {
    const edgesStr = JSON.stringify(props.edges.map(e => ({ id: e.id, from: e.from, to: e.to, label: e.label || '' })));
    total += estimateTokens(edgesStr);
  }
  // 对话历史（最近40条）
  const history = msgs.value
    .filter(m => (m.role === 'u' || m.role === 'a') && m.text)
    .slice(-40);
  for (const m of history) {
    total += estimateTokens(m.text);
  }
  return total;
});

const formatTokens = (n: number) => {
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
  return String(n);
};

// 对外暴露给侧栏切换 / 删除当前会话使用
defineExpose({
  switchConversation: (id: string) => initConversation(id),
  newConversation: () => initConversation('new'),
  currentConversationId: () => conv.conversationId.value,
});

</script>

<template>
  <div class="chat-panel" :style="{ width: width + 'px' }">
    <div class="ch-head">
      <div class="ch-head-l"><div class="ch-pulse" /><span>AI 推演助手</span></div>
      <div class="ch-stat">{{ nodes.length }}节点·{{ edges.length }}关系</div>
    </div>
    <ChatMessageList
      ref="msgListRef"
      :messages="msgs"
      :loading="loading"
      @preview="(a) => previewAtt = a"
      @focus-node="(id) => emit('focus-node', id)"
      @abort-prediction="emit('abort-prediction')"
      @select-option="onSelectQuestionOption"
      @view-graph="(id) => emit('view-graph', id)"
    />
    <AttachmentPreview :attachment="previewAtt" @close="previewAtt = null" />
    <AttachmentChips
      :attachments="atts"
      @remove="(i) => atts = atts.filter((_, j) => j !== i)"
      @preview="(a) => previewAtt = { name: a.name, type: a.type, kind: a.kind, size: a.size, content: a.content, error: a.error, truncated: a.truncated }"
    />
    <div class="ch-input-area">
      <div class="input-box">
        <!-- @ mention dropdown -->
        <div ref="mentionListRef" class="mention-dropdown" v-if="mentionOpen && mentionItems.length > 0">
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
        <textarea ref="inputRef" class="ch-input" v-model="input" placeholder="描述本体关系，输入 @ 可引用节点/关系，可粘贴图片或附加 DOCX/文件…" @keydown="onInputKeydown" @input="onInputEvent" @click="onInputClick" @paste="onInputPaste" rows="2" />
        <div class="input-footer">
          <div class="file-tools">
            <button class="file-icon-btn attach-btn" type="button" title="上传文件 (图片/DOCX/MD/TXT/JSON 等),也可在输入框直接粘贴图片" @click="() => { if (fileRef) fileRef.click(); }">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
            </button>
            <input ref="fileRef" type="file" multiple accept="image/*,.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,.html,.htm,.js,.ts,.py,.java,.sql,.toml,.ini,.env,.vue,.css,text/*" style="display:none" @change="(e: any) => { Array.from(e.target.files || []).forEach((f: any) => addFile(f)); e.target.value = ''; }" />
          </div>
          <button
            class="send-btn"
            :class="{ 'send-btn-stop': loading }"
            type="button"
            :title="loading ? '停止生成' : '发送'"
            @click="loading ? abortChat() : send()"
          >
            <svg v-if="loading" viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
              <rect x="6" y="6" width="12" height="12" rx="2"></rect>
            </svg>
            <svg v-else viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round">
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
  flex-shrink: 0;
  overflow: hidden;
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
.ctx-token-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  font-size: 11px;
  color: rgba(255,255,255,0.35);
  border-top: 1px solid rgba(255,255,255,0.06);
  margin-bottom: 8px;
}
.ctx-token-count {
  color: rgba(255,255,255,0.5);
  font-family: 'SF Mono', 'Consolas', monospace;
}
.ctx-token-detail {
  margin-left: auto;
  color: rgba(255,255,255,0.25);
}
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
.send-btn-stop {
  background: rgba(255, 255, 255, 0.92);
  color: #0a1019;
  position: relative;
}
.send-btn-stop::before {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 11px;
  border: 1.5px solid rgba(66, 184, 131, 0.5);
  border-top-color: #42b883;
  animation: send-spin 0.9s linear infinite;
  pointer-events: none;
}
.send-btn-stop:hover { background: #ffffff; transform: translateY(-1px); }
@keyframes send-spin { to { transform: rotate(360deg); } }
</style>
