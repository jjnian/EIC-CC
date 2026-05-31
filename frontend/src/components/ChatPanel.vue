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
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
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
  /**
   * 在发送前确保有当前本体模型。无 modelId 时调用,App.vue 负责创建模型并把
   * currentModelId 写回,等待此 Promise resolve 后再走 send。用于「新对话」入口
   * 由用户首次发言时才落地一个模型。
   */
  ensureModel?: (titleHint: string) => Promise<void>;
}>();

const emit = defineEmits<{
  (e: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[]): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
  (e: 'focus-node', id: string): void;
  (e: 'abort-prediction'): void;
  (e: 'view-graph', modelId: string): void;
  (e: 'user-msg-changed', hasUserMsg: boolean): void;
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
const ws = useWorkspaces();
const tree = useSidebarTree();

const mention = useMention({
  input,
  inputRef,
  nodes: () => props.nodes || [],
  edges: () => props.edges || [],
  graphLabel: () => '当前图谱',
  dataSources: () => {
    const wsId = ws.currentId.value;
    return wsId ? (tree.getDataSources(wsId) || []).map(ds => ({ id: ds.id, name: ds.name })) : [];
  },
});
const {
  mentionOpen,
  mentionQuery,
  mentionIndex,
  mentionItems,
  mentionTree,
  mentionListRef,
  activeMentions,
  checkMention,
  selectMention,
  handleKeydown: handleMentionKeydown,
  syncActiveMentions,
  consumeActiveMentions,
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
  setConversationTitle,
  persistCurrent,
  flushPersist,
  cancelPersist,
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
  consumeMentions: () => consumeActiveMentions(),
});
const { send, abortChat } = sender;

/**
 * 实际触发发送的入口。若父级声明了 ensureModel(用于「新对话」首次发言时
 * 才创建本体模型的延迟落地),先 await 它再 send。
 */
const onSend = async () => {
  if (!input.value.trim() && !atts.value.length) return;
  if (props.ensureModel && !props.modelId) {
    try {
      await props.ensureModel(input.value);
      await nextTick();
    } catch (e) {
      console.error('ensureModel failed', e);
      toast.warn('创建本体模型失败');
      return;
    }
  }
  await send();
};

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
    onSend();
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
  nextTick(() => { onSend(); });
};

// 用户点「自己输入回答」→ 不发送,只把光标聚焦到输入框,由 useChatSend 在 send() 时根据 input 内容标记 answered
const onCustomAnswer = (_messageIndex: number) => {
  if (loading.value) return;
  nextTick(() => { inputRef.value?.focus(); });
};

// 当存在最近未回答的问题时,在输入框上方显示提示横条
const hasPendingQuestion = computed(() =>
  msgs.value.some(m => m.role === 'a' && m.question && !m.question.answered),
);
// 让用户主动忽略问题(标记为已答,横条收起,问题选项也变 disabled 灰态),便于继续别的话题
const dismissPendingQuestion = () => {
  for (let i = msgs.value.length - 1; i >= 0; i--) {
    const m = msgs.value[i];
    if (m.role === 'a' && m.question && !m.question.answered) {
      m.question.answered = '(已跳过)';
      break;
    }
  }
};

// ===== 启动:加载模型 + 恢复/接收 seed =====
const consumeSeed = (seed: { text: string; files: File[] }) => {
  seed.files.forEach(f => addFile(f));
  input.value = seed.text;
  emit('seed-consumed');
  nextTick(() => { onSend(); });
};

onMounted(() => {
  loadModels();
  const wsId = ws.currentId.value;
  if (wsId) tree.loadDataSources(wsId);
  if (props.seed && (props.seed.text || props.seed.files.length)) {
    initConversation('new');
    consumeSeed(props.seed);
    return;
  }
  restoreLatestOrNew();
});

watch(input, () => {
  syncActiveMentions();
  nextTick(() => checkMention());
});

watch(() => props.seed, (newSeed) => {
  if (newSeed && (newSeed.text || newSeed.files.length)) {
    initConversation('new');
    consumeSeed(newSeed);
  }
});

onBeforeUnmount(() => abortChat());

// 每次消息变化:滚到底 + localStorage 持久化 + 通知父级用户消息状态(用于切换欢迎横幅)
watch(msgs, () => {
  msgListRef.value?.scrollToBottom();
  persistCurrent();
  emit('user-msg-changed', msgs.value.some(m => m.role === 'u'));
}, { deep: true, immediate: true });

watch(loading, (now, prev) => {
  if (!now && prev) {
    flushPersist();
  }
});

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
  setConversationTitle: (title: string) => setConversationTitle(title),
  flushPersist,
  cancelPersist,
  focusInput: () => { inputRef.value?.focus(); nextTick(() => checkMention()); },
  /** 把示例文本直接写入输入框(供欢迎横幅的示例按钮用)。 */
  setInput: (text: string) => {
    input.value = text;
    nextTick(() => {
      inputRef.value?.focus();
      const len = text.length;
      try { inputRef.value?.setSelectionRange(len, len); } catch { /* noop */ }
    });
  },
  /** 取当前会话里首条用户消息的文本,供 App.vue 用于本体模型标题。 */
  getFirstUserText: (): string => {
    const first = msgs.value.find(m => m.role === 'u');
    return first?.text || '';
  },
});

watch(() => ws.currentId.value, (wsId) => {
  if (wsId) tree.loadDataSources(wsId);
}, { immediate: true });

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
      @custom-answer="onCustomAnswer"
      @view-graph="(id) => emit('view-graph', id)"
    />
    <AttachmentPreview :attachment="previewAtt" @close="previewAtt = null" />
    <AttachmentChips
      :attachments="atts"
      @remove="(i) => atts = atts.filter((_, j) => j !== i)"
      @preview="(a) => previewAtt = { name: a.name, type: a.type, kind: a.kind, size: a.size, content: a.content, error: a.error, truncated: a.truncated }"
    />
    <div v-if="activeMentions.length" class="mention-chips" :title="'@ 引用会让 LLM 只参考这些数据源 / 锚定到这些节点'">
      <span class="mention-chips-label">🎯 已 @ 引用</span>
      <span
        v-for="(m, i) in activeMentions"
        :key="m.kind + ':' + m.id + ':' + i"
        class="mention-chip"
        :class="'mc-' + m.kind"
      >
        <span class="mc-kind">{{ m.kind === 'graph' ? '图' : m.kind === 'datasource' ? '源' : m.kind === 'relation' ? '关' : '点' }}</span>
        <span class="mc-label">{{ m.label }}</span>
        <button
          class="mc-x"
          title="从引用列表移除 (同时删掉输入框里的 token)"
          @click="() => { input = input.split(m.token).join('').replace(/\s{2,}/g, ' ').trim(); syncActiveMentions(); }"
        >×</button>
      </span>
    </div>
    <div class="ch-input-area">
      <div v-if="hasPendingQuestion" class="answering-hint">
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
        <span>正在回答上方问题,或继续提问</span>
        <button class="answering-dismiss" type="button" title="忽略这个问题" @click="dismissPendingQuestion">跳过</button>
      </div>
      <div class="input-box">
        <!-- @ mention dropdown -->
        <div ref="mentionListRef" class="mention-dropdown" v-if="mentionOpen && mentionItems.length > 0">
          <div class="mention-header">
            <span>引用 {{ mentionQuery ? `"${mentionQuery}"` : '图谱 / 数据源' }}</span>
            <span class="mention-hint">↑↓ 选择 · Enter 确认 · Esc 取消</span>
          </div>
          <div v-for="section in mentionTree" :key="section.key" class="mention-section">
            <div class="mention-section-head">{{ section.label }}</div>
            <div v-for="group in section.groups" :key="group.key" class="mention-group">
              <div class="mention-group-head">{{ group.label }}</div>
              <div
                v-for="row in group.items"
                :key="row.item.kind + ':' + row.item.id"
                class="mention-item"
                :class="{
                  active: row.index === mentionIndex,
                  'mention-edge': row.item.kind === 'relation',
                  'mention-graph': row.item.kind === 'graph',
                  'mention-ds': row.item.kind === 'datasource'
                }"
                @mousedown.prevent="selectMention(row.item)"
                @mouseenter="mentionIndex = row.index"
              >
                <span class="mention-kind">
                  {{ row.item.kind === 'graph' ? '图' : row.item.kind === 'datasource' ? '源' : row.item.kind === 'relation' ? '关' : '点' }}
                </span>
                <span class="mention-label">{{ row.item.label }}</span>
                <span class="mention-sub">{{ row.item.sub }}</span>
              </div>
            </div>
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
            @click="loading ? abortChat() : onSend()"
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
  background: transparent;
  border-left: 1px solid rgba(255, 255, 255, 0.07);
  flex-shrink: 0;
  overflow: hidden;
  position: relative;
}
.chat-panel::before {
  content: '';
  position: absolute;
  left: 0; top: 0; bottom: 0; width: 1px;
  background: linear-gradient(180deg, transparent, rgba(255,255,255,0.10) 30%, rgba(255,255,255,0.10) 70%, transparent);
  pointer-events: none;
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
  background: #42b883;
  box-shadow: 0 0 10px #42b883, 0 0 0 4px rgba(66,184,131,0.10);
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}
.ch-head-l span {
  font-size: 15px; font-weight: 600; color: var(--text-main);
  letter-spacing: 0.4px;
}
.ch-stat {
  font-size: 12px; color: var(--text-dim);
  font-family: 'JetBrains Mono', 'SF Mono', ui-monospace, monospace;
  letter-spacing: 0.2px;
}
.ch-msgs {
  flex: 1; overflow-y: auto; padding: 20px;
  display: flex; flex-direction: column; gap: 16px;
}
.msg { display: flex; gap: 10px; max-width: 72%; }
.msg-user { align-self: flex-end; flex-direction: row-reverse; }
.msg-asst { align-self: flex-start; }
.avatar {
  width: 32px; height: 32px; border-radius: 9px;
  background: linear-gradient(135deg, #5fd4a3, #42b883 55%, #3d9bff);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: white; flex-shrink: 0;
  box-shadow: 0 6px 14px rgba(66,184,131,0.28), inset 0 1px 0 rgba(255,255,255,0.28);
  letter-spacing: 0.4px;
}
.msg-body { display: flex; flex-direction: column; gap: 6px; max-width: 100%; }
.bubble {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.07), rgba(255, 255, 255, 0.04));
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 14px; padding: 12px 16px;
  font-size: 14px; line-height: 1.65; color: var(--text-main);
  white-space: pre-wrap; word-break: break-word;
  letter-spacing: 0.15px;
  box-shadow: 0 4px 14px rgba(0,0,0,0.18), inset 0 1px 0 rgba(255,255,255,0.03);
}
.cursor {
  display: inline-block;
  width: 2px; height: 1em;
  background: #42b883;
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-end infinite;
  box-shadow: 0 0 6px rgba(66,184,131,0.6);
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.msg-user .bubble {
  background: linear-gradient(135deg, rgba(66, 184, 131, 0.20), rgba(66, 184, 131, 0.10));
  border-color: rgba(66, 184, 131, 0.32);
  box-shadow: 0 6px 18px rgba(66, 184, 131, 0.18), inset 0 1px 0 rgba(255,255,255,0.06);
}
.att-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.att-sm {
  font-size: 11px;
  background: rgba(255,255,255,0.07);
  padding: 2px 8px; border-radius: 5px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.att-sm-err { background: rgba(255,99,99,0.14); color: #ff8a8a; }
.att-row { display: flex; flex-wrap: wrap; gap: 8px; padding: 8px 20px; max-width: 100%; }
.att-chip {
  display: flex; align-items: center; gap: 6px;
  background: linear-gradient(180deg, rgba(66, 184, 131, 0.16), rgba(66, 184, 131, 0.08));
  border: 1px solid rgba(66, 184, 131, 0.28);
  padding: 4px 10px; border-radius: 7px;
  font-size: 12px; color: #5fd4a3;
}
.att-chip button { background: none; border: none; color: inherit; cursor: pointer; font-size: 14px; padding: 0; line-height: 1; }
.att-chip.att-img {
  background: linear-gradient(180deg, rgba(99, 155, 255, 0.16), rgba(99, 155, 255, 0.08));
  border-color: rgba(99, 155, 255, 0.30); color: #82b1ff;
}
.att-chip.att-err {
  background: linear-gradient(180deg, rgba(255, 99, 99, 0.16), rgba(255, 99, 99, 0.08));
  border-color: rgba(255, 99, 99, 0.34); color: #ff8a8a;
}
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
.mention-chips {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  padding: 6px 20px 0; font-size: 11.5px; color: #c0c4cf;
}
.mention-chips-label { color: #888; font-size: 11px; margin-right: 2px; }
.mention-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 4px 2px 6px; border-radius: 10px;
  background: rgba(255,255,255,.06);
  border: 1px solid rgba(255,255,255,.08);
  line-height: 1.4;
}
.mention-chip.mc-graph { background: rgba(184,134,255,.12); border-color: rgba(184,134,255,.3); color: #d8c4ff; }
.mention-chip.mc-datasource { background: rgba(34,221,136,.12); border-color: rgba(34,221,136,.3); color: #9febc6; }
.mention-chip.mc-relation { background: rgba(255,191,73,.12); border-color: rgba(255,191,73,.3); color: #ffd99b; }
.mention-chip.mc-node { background: rgba(74,141,240,.15); border-color: rgba(74,141,240,.35); color: #b9d4ff; }
.mc-kind {
  font-size: 10px; padding: 0 4px; border-radius: 4px;
  background: rgba(0,0,0,.25); color: inherit; font-weight: 600;
}
.mc-label { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mc-x {
  border: none; background: transparent; color: inherit; cursor: pointer;
  font-size: 14px; line-height: 1; padding: 0 4px; opacity: .65;
}
.mc-x:hover { opacity: 1; }
.ch-input-area { padding: 16px 20px; border-top: 1px solid rgba(255,255,255,0.06); }
.answering-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  margin-bottom: 8px;
  font-size: 11.5px;
  color: #fbbf24;
  background: rgba(251, 191, 36, 0.08);
  border: 1px solid rgba(251, 191, 36, 0.22);
  border-radius: 8px;
  line-height: 1.4;
}
.answering-hint > span { flex: 1; }
.answering-dismiss {
  background: transparent;
  border: 1px solid rgba(251, 191, 36, 0.3);
  color: #fde68a;
  padding: 2px 8px;
  border-radius: 100px;
  font-size: 11px;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}
.answering-dismiss:hover {
  background: rgba(251, 191, 36, 0.18);
  border-color: rgba(251, 191, 36, 0.55);
  color: #fff;
}
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
  background: linear-gradient(180deg, rgba(8, 13, 22, 0.68), rgba(6, 10, 18, 0.55));
  border: 1px solid rgba(255,255,255,0.10);
  border-radius: 14px; padding: 10px 14px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
  position: relative;
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.03);
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
.mention-section {
  padding: 2px 0 4px;
}
.mention-section + .mention-section {
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  margin-top: 4px;
  padding-top: 6px;
}
.mention-section-head {
  padding: 5px 10px 4px;
  font-size: 11px;
  font-weight: 700;
  color: #7dd3fc;
}
.mention-group {
  position: relative;
}
.mention-group::before {
  content: '';
  position: absolute;
  left: 16px;
  top: 18px;
  bottom: 4px;
  width: 1px;
  background: rgba(125, 211, 252, 0.14);
}
.mention-group-head {
  padding: 4px 10px 3px 28px;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.38);
}
.mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px 7px 38px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s;
  font-size: 13px;
  position: relative;
}
.mention-item::before {
  content: '';
  position: absolute;
  left: 16px;
  top: 50%;
  width: 14px;
  height: 1px;
  background: rgba(125, 211, 252, 0.18);
}
.mention-item.active,
.mention-item:hover {
  background: rgba(66, 184, 131, 0.15);
}
.mention-item.mention-edge .mention-kind { color: #639bff; }
.mention-item.mention-graph .mention-kind { color: #42b883; }
.mention-item.mention-ds .mention-kind { color: #fbbf24; }
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
.input-box:focus-within {
  border-color: rgba(66, 184, 131, 0.50);
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.04), 0 0 0 3px rgba(66, 184, 131, 0.10);
}
.ch-input {
  width: 100%; background: transparent; border: none; color: white;
  font-size: 14px; resize: none; outline: none; font-family: inherit;
  letter-spacing: 0.15px;
}
.ch-input::placeholder { color: rgba(255,255,255,0.32); }
.input-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.file-tools { display: flex; gap: 6px; }
.file-icon-btn {
  background: none; border: none; color: rgba(255,255,255,0.42);
  cursor: pointer; padding: 5px; border-radius: 6px;
  transition: background 0.18s ease, color 0.18s ease;
  display: flex; align-items: center; justify-content: center;
}
.file-icon-btn:hover { color: #5fd4a3; background: rgba(66,184,131,0.10); }
.send-btn {
  background: linear-gradient(135deg, #5fd4a3, #42b883);
  border: none; color: #062a1c;
  width: 32px; height: 32px; border-radius: 9px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
  box-shadow: 0 6px 16px rgba(66, 184, 131, 0.30), inset 0 1px 0 rgba(255,255,255,0.30);
}
.send-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(66, 184, 131, 0.42), inset 0 1px 0 rgba(255,255,255,0.34);
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.send-btn-stop {
  background: rgba(255, 255, 255, 0.94);
  color: #0a1019;
  position: relative;
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
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
