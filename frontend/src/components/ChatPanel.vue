<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import type { OntologyNode, OntologyEdge, GraphMutation } from '../types';
import { useConversations, type ChatMsg, type ChatMsgAttachment, type ChatQuestionMsg } from '../composables/useConversations';
import { useAttachments } from '../composables/useAttachments';
import { useMention } from '../composables/useMention';
import { useChatModels } from '../composables/useChatModels';
import { useChatSend } from '../composables/useChatSend';
import { useChatQuestions } from '../composables/useChatQuestions';
import { useContextTokens } from '../composables/useContextTokens';
import { toast } from '../composables/useToast';
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
import ChatMessageList from './chat/ChatMessageList.vue';
import AttachmentChips from './chat/AttachmentChips.vue';
import AttachmentPreview from './chat/AttachmentPreview.vue';
import { Button } from '@/components/ui/button';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  width: number;
  seed?: { text: string; files: File[] } | null;
  modelId?: string;
  /**
   * 在发送前确保有当前本体模型。无 modelId 时调用,App.vue 负责创建模型并把
   * currentModelId 写回,等待此 Promise resolve 后再走 send。用于「新对话」入口
   * 由用户首次发言时才落地一个模型。
   */
  ensureModel?: (titleHint: string) => Promise<void>;
}>();

const emit = defineEmits<{
  (e: 'update', mutation: GraphMutation): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
  (e: 'focus-node', id: string): void;
  (e: 'view-graph', modelId: string): void;
  (e: 'user-msg-changed', hasUserMsg: boolean): void;
  (e: 'need-workspace'): void;
}>();

// ===== 消息/输入 状态 =====
const msgs = ref<ChatMsg[]>([
  { role: 'a', text: '你好!我是建模助手。\n\n用自然语言描述实体和关系,我会自动构建本体血缘图。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试:「添加一个财务审计实体,与客户相关联」' }
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
  experiences: () => {
    const wsId = ws.currentId.value;
    return wsId ? (tree.getExperiences(wsId) || []).map(e => ({ id: e.id, title: e.title })) : [];
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
  // 落库后把这条会话原地回填到侧栏:同一 id 只更新不新增,确保"一次对话一个条目"。
  onPersisted: (c) => {
    const wsId = ws.currentId.value;
    if (wsId) tree.upsertConversation(wsId, c);
  },
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
  bindModel,
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
  emit: (event, mutation) => emit(event, mutation),
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
  // 没有选中工作空间时,所有 /api 业务请求都会缺 X-Workspace-Id 头被后端 400 拒绝。
  // 在发送前拦下,给出可操作的提示并把用户带回工作空间选择页,而不是抛后端的原始报错。
  if (!ws.currentId.value) {
    toast.warn('请先选择或新建工作空间');
    emit('need-workspace');
    return;
  }
  if (props.ensureModel && !props.modelId) {
    try {
      await props.ensureModel(input.value);
      await nextTick();
      // 惰性建图完成后，把这张血缘图绑定到当前会话：一会话一图，后续改动都落到它上。
      bindModel(props.modelId || '');
    } catch (e) {
      console.error('ensureModel failed', e);
      toast.warn('创建本体模型失败');
      return;
    }
  }
  await send();
};

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

// 澄清问题交互（迁移/勾选/提交/跳过）抽到 useChatQuestions
const {
  migrateLegacyQuestions,
  onPickOption,
  onSubmitAnswers,
  onCustomAnswer,
  hasPendingQuestion,
  dismissPendingQuestion,
} = useChatQuestions({ msgs, input, loading, inputRef, send: () => onSend() });

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
  if (wsId) { tree.loadDataSources(wsId); tree.loadExperiences(wsId); }
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
  migrateLegacyQuestions();
  msgListRef.value?.scrollToBottom();
  persistCurrent();
  emit('user-msg-changed', msgs.value.some(m => m.role === 'u'));
}, { deep: true, immediate: true });

watch(loading, (now, prev) => {
  if (!now && prev) {
    flushPersist();
  }
});

// ===== 上下文 token 预估（抽到 useContextTokens）=====
const { contextTokenEstimate, formatTokens } = useContextTokens({
  nodes: () => props.nodes || [],
  edges: () => props.edges || [],
  msgs,
});

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
  if (wsId) { tree.loadDataSources(wsId); tree.loadExperiences(wsId); }
}, { immediate: true });

</script>

<template>
  <div class="chat-panel" :style="{ width: width + 'px' }">
    <div class="ch-head">
      <div class="ch-head-l"><div class="ch-pulse" /><span>AI 建模助手</span></div>
      <div class="ch-stat">{{ nodes.length }}节点·{{ edges.length }}关系</div>
    </div>
    <ChatMessageList
      ref="msgListRef"
      :messages="msgs"
      :loading="loading"
      @preview="(a) => previewAtt = a"
      @focus-node="(id) => emit('focus-node', id)"
      @pick-option="onPickOption"
      @submit-answers="onSubmitAnswers"
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
        <span class="mc-kind">{{ m.kind === 'graph' ? '图' : m.kind === 'datasource' ? '源' : m.kind === 'relation' ? '关' : m.kind === 'experience' ? '验' : '点' }}</span>
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
        <Button variant="ghost" size="sm" type="button" title="忽略这个问题" @click="dismissPendingQuestion">跳过</Button>
      </div>
      <div class="input-box">
        <!-- @ mention dropdown -->
        <div ref="mentionListRef" class="mention-dropdown" v-if="mentionOpen && mentionItems.length > 0">
          <div class="mention-header">
            <span>引用 {{ mentionQuery ? `"${mentionQuery}"` : '图谱 / 数据源 / 经验库' }}</span>
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
                  'mention-ds': row.item.kind === 'datasource',
                  'mention-exp': row.item.kind === 'experience'
                }"
                @mousedown.prevent="selectMention(row.item)"
                @mouseenter="mentionIndex = row.index"
              >
                <span class="mention-kind">
                  {{ row.item.kind === 'graph' ? '图' : row.item.kind === 'datasource' ? '源' : row.item.kind === 'relation' ? '关' : row.item.kind === 'experience' ? '验' : '点' }}
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
            <Button variant="ghost" size="icon" type="button" title="上传文件 (图片/DOCX/MD/TXT/JSON 等),也可在输入框直接粘贴图片" @click="() => { if (fileRef) fileRef.click(); }">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
            </Button>
            <input ref="fileRef" type="file" multiple accept="image/*,.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,.html,.htm,.js,.ts,.py,.java,.sql,.toml,.ini,.env,.vue,.css,text/*" style="display:none" @change="(e: any) => { Array.from(e.target.files || []).forEach((f: any) => addFile(f)); e.target.value = ''; }" />
          </div>
          <Button
            size="icon"
            :variant="loading ? 'secondary' : 'default'"
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
          </Button>
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
  border-left: 1px solid var(--hairline);
  flex-shrink: 0;
  overflow: hidden;
  position: relative;
}
.ch-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--hairline);
}
.ch-head-l { display: flex; align-items: center; gap: 10px; }
.ch-pulse {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--accent-2);
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
  background: var(--accent);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: #fff; flex-shrink: 0;
  letter-spacing: 0.4px;
}
.msg-body { display: flex; flex-direction: column; gap: 6px; max-width: 100%; }
.bubble {
  background: transparent;
  border: none;
  border-radius: 14px; padding: 12px 16px;
  font-size: 14px; line-height: 1.65; color: var(--text-main);
  white-space: pre-wrap; word-break: break-word;
  letter-spacing: 0.15px;
}
.cursor {
  display: inline-block;
  width: 2px; height: 1em;
  background: var(--accent);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-end infinite;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.msg-user .bubble {
  background: rgba(0, 0, 0, 0.055);
}
.att-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.att-sm {
  font-size: 11px;
  background: var(--bg-elev);
  padding: 2px 8px; border-radius: 5px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.att-sm-err { background: rgba(220, 38, 38, 0.08); color: #dc2626; }
.att-row { display: flex; flex-wrap: wrap; gap: 8px; padding: 8px 20px; max-width: 100%; }
.att-chip {
  display: flex; align-items: center; gap: 6px;
  background: #ffffff;
  border: 1px solid var(--glass-border);
  padding: 4px 10px; border-radius: 7px;
  font-size: 12px; color: var(--text-dim);
}
.att-chip button { background: none; border: none; color: inherit; cursor: pointer; font-size: 14px; padding: 0; line-height: 1; }
.att-chip.att-img {
  background: #ffffff;
  border-color: var(--glass-border); color: var(--text-dim);
}
.att-chip.att-err {
  background: rgba(220, 38, 38, 0.08);
  border-color: rgba(220, 38, 38, 0.35); color: #dc2626;
}
.att-kind { font-size: 12px; }
.att-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.att-spin {
  width: 10px; height: 10px; border: 2px solid currentColor; border-right-color: transparent;
  border-radius: 50%; animation: att-spin 0.8s linear infinite; opacity: 0.6;
}
@keyframes att-spin { to { transform: rotate(360deg); } }
.att-bad {
  width: 14px; height: 14px; border-radius: 50%; background: #dc2626;
  color: #fff; font-size: 10px; font-weight: 700; display: inline-flex;
  align-items: center; justify-content: center;
}
.mention-chips {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  padding: 6px 20px 0; font-size: 11.5px; color: var(--text-dim);
}
.mention-chips-label { color: var(--text-muted); font-size: 11px; margin-right: 2px; }
.mention-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 4px 2px 6px; border-radius: 10px;
  background: var(--bg-elev);
  border: 1px solid var(--hairline);
  line-height: 1.4;
}
.mention-chip.mc-graph { background: rgba(124, 58, 237, 0.08); border-color: rgba(124, 58, 237, 0.3); color: #7c3aed; }
.mention-chip.mc-datasource { background: rgba(5, 150, 105, 0.08); border-color: rgba(5, 150, 105, 0.3); color: #059669; }
.mention-chip.mc-relation { background: rgba(217, 119, 6, 0.08); border-color: rgba(217, 119, 6, 0.3); color: #d97706; }
.mention-chip.mc-node { background: rgba(37, 99, 235, 0.08); border-color: rgba(37, 99, 235, 0.3); color: #2563eb; }
.mention-chip.mc-experience { background: rgba(2, 132, 199, 0.08); border-color: rgba(2, 132, 199, 0.3); color: #0284c7; }
.mc-kind {
  font-size: 10px; padding: 0 4px; border-radius: 4px;
  background: rgba(0, 0, 0, 0.08); color: inherit; font-weight: 600;
}
.mc-label { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mc-x {
  border: none; background: transparent; color: inherit; cursor: pointer;
  font-size: 14px; line-height: 1; padding: 0 4px; opacity: .65;
}
.mc-x:hover { opacity: 1; }
.ch-input-area { padding: 16px 20px; border-top: 1px solid var(--hairline); }
.answering-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  margin-bottom: 8px;
  font-size: 11.5px;
  color: #d97706;
  background: rgba(217, 119, 6, 0.08);
  border: 1px solid rgba(217, 119, 6, 0.25);
  border-radius: 8px;
  line-height: 1.4;
}
.answering-hint > span { flex: 1; }
.answering-dismiss {
  background: transparent;
  border: 1px solid rgba(217, 119, 6, 0.35);
  color: #d97706;
  padding: 2px 8px;
  border-radius: 100px;
  font-size: 11px;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}
.answering-dismiss:hover {
  background: rgba(217, 119, 6, 0.14);
  border-color: rgba(217, 119, 6, 0.55);
  color: #b45309;
}
.ctx-token-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  font-size: 11px;
  color: var(--text-muted);
  border-top: 1px solid var(--hairline);
  margin-bottom: 8px;
}
.ctx-token-count {
  color: var(--text-dim);
  font-family: 'SF Mono', 'Consolas', monospace;
}
.ctx-token-detail {
  margin-left: auto;
  color: var(--text-muted);
}
.input-box {
  background: #ffffff;
  border: 1px solid var(--glass-border);
  border-radius: 12px; padding: 10px 14px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
  position: relative;
  box-shadow: var(--shadow-sm);
}
.mention-dropdown {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  right: 0;
  background: #ffffff;
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  padding: 4px;
  max-height: 280px;
  overflow-y: auto;
  z-index: 200;
  box-shadow: var(--shadow-lg);
}
.mention-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px 4px;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--text-muted);
  border-bottom: 1px solid var(--hairline);
  margin-bottom: 4px;
}
.mention-hint {
  font-size: 9px;
  letter-spacing: 0.5px;
  color: var(--text-muted);
}
.mention-section {
  padding: 2px 0 4px;
}
.mention-section + .mention-section {
  border-top: 1px solid var(--hairline);
  margin-top: 4px;
  padding-top: 6px;
}
.mention-section-head {
  padding: 5px 10px 4px;
  font-size: 11px;
  font-weight: 700;
  color: var(--text-dim);
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
  background: rgba(0, 0, 0, 0.08);
}
.mention-group-head {
  padding: 4px 10px 3px 28px;
  font-size: 10px;
  color: var(--text-muted);
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
  background: rgba(0, 0, 0, 0.10);
}
.mention-item.active,
.mention-item:hover {
  background: var(--accent-tint);
}
.mention-item.mention-edge .mention-kind { color: #d97706; }
.mention-item.mention-graph .mention-kind { color: #7c3aed; }
.mention-item.mention-ds .mention-kind { color: #059669; }
.mention-item.mention-exp .mention-kind { color: #0284c7; }
.mention-kind {
  font-size: 12px;
  color: #2563eb;
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
  border-color: rgba(0, 0, 0, 0.32);
  box-shadow: var(--shadow-sm), 0 0 0 3px rgba(0, 0, 0, 0.05);
}
.ch-input {
  width: 100%; background: transparent; border: none; color: var(--text-main);
  font-size: 14px; resize: none; outline: none; font-family: inherit;
  letter-spacing: 0.15px;
}
.ch-input::placeholder { color: var(--text-muted); }
.input-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.file-tools { display: flex; gap: 6px; }
.file-icon-btn {
  background: none; border: none; color: var(--text-muted);
  cursor: pointer; padding: 5px; border-radius: 6px;
  transition: background 0.18s ease, color 0.18s ease;
  display: flex; align-items: center; justify-content: center;
}
.file-icon-btn:hover { color: var(--text-main); background: var(--bg-elev); }
.send-btn {
  background: var(--accent);
  border: none; color: #fff;
  width: 32px; height: 32px; border-radius: 9px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
  box-shadow: var(--shadow-sm);
}
.send-btn:hover:not(:disabled) {
  background: var(--accent-soft);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.send-btn-stop {
  background: #ffffff;
  color: var(--text-main);
  position: relative;
  box-shadow: var(--shadow-sm);
}
.send-btn-stop::before {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 11px;
  border: 1.5px solid rgba(0, 0, 0, 0.15);
  border-top-color: var(--accent);
  animation: send-spin 0.9s linear infinite;
  pointer-events: none;
}
.send-btn-stop:hover { background: #f7f8fa; transform: translateY(-1px); }
@keyframes send-spin { to { transform: rotate(360deg); } }
</style>
