<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { chatStream, type ChatPayload, type ChatResult } from '../api/chat';
import type { SseHandle } from '../api/http';
import { useConversations, type ChatMsg, type ChatMsgAttachment } from '../composables/useConversations';
import { useAttachments } from '../composables/useAttachments';
import { useMention } from '../composables/useMention';
import { useChatModels } from '../composables/useChatModels';
import { toast } from '../composables/useToast';
import ChatMessageList from './chat/ChatMessageList.vue';
import AttachmentChips from './chat/AttachmentChips.vue';
import AttachmentPreview from './chat/AttachmentPreview.vue';

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
const previewAtt = ref<ChatMsgAttachment | null>(null);

// ===== SSE 流控制 =====
let chatHandle: SseHandle | null = null;
let currentResolveStream: (() => void) | null = null;
let currentAiMsg: ChatMsg | null = null;
const abortChat = () => {
  if (chatHandle) { try { chatHandle.abort(); } catch { /* noop */ } chatHandle = null; }
  // 中断后 SSE 不会再回调 onClose,这里手动收尾,避免 loading 卡住。
  if (currentResolveStream) {
    if (currentAiMsg && currentAiMsg.text === '正在分析对话内容并构建图谱…') {
      currentAiMsg.text = '已停止生成。';
    }
    const r = currentResolveStream;
    currentResolveStream = null;
    currentAiMsg = null;
    r();
  }
};

// ===== composables 接线 =====
const conv = useConversations({
  msgs,
  abortChat,
  clearGraph: () => emit('clear-graph'),
});
const {
  conversationTitle,
  autoTitle,
  persistCurrent,
  initConversation,
  newConversation,
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
  loadModels,
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
  // 把内容随消息一起存,后续在历史里可以重新预览。体积阈值控制持久化大小。
  const PERSIST_TEXT_MAX = 100_000;   // ~100KB per text attachment
  const PERSIST_IMG_MAX  = 2_000_000; // ~2MB per image dataUrl
  const uaDisplay: ChatMsgAttachment[] = atts.value.map(a => {
    const out: ChatMsgAttachment = {
      name: a.name, type: a.type, kind: a.kind, size: a.size, error: a.error,
      truncated: a.truncated,
    };
    if (!a.error && a.content) {
      const len = a.content.length;
      if (a.kind === 'image' && len <= PERSIST_IMG_MAX) {
        out.content = a.content;
      } else if (a.kind === 'text' && len <= PERSIST_TEXT_MAX) {
        out.content = a.content;
      } else if (a.kind === 'text') {
        out.content = a.content.slice(0, PERSIST_TEXT_MAX);
        out.storedTruncated = true;
      } else if (a.kind === 'image') {
        // 太大的图就不持久化 dataUrl 了,只留预览失败提示
        out.storedTruncated = true;
      }
    }
    return out;
  });
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

  // 创建 AI 消息占位(流式期间显示"分析中",complete 后替换为 parsed.reply)
  const aiMsg: ChatMsg = { role: 'a', text: '正在分析对话内容并构建图谱…' };
  msgs.value.push(aiMsg);
  // LLM 返回的是 JSON,流式 chunk 不要直接灌入气泡(会让用户看到一坨原始 JSON)。
  let rawJsonBuf = '';

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

    const body: ChatPayload = { message: composedMessage, history };
    // 把当前画布的节点/关系一并发给后端,让模型避免重复实体并基于已有图谱增量扩展
    if (props.nodes?.length) {
      body.nodes = props.nodes.map(n => ({ id: n.id, label: n.label, type: n.type }));
    }
    if (props.edges?.length) {
      body.edges = props.edges.map(e => ({ id: e.id, from: e.from, to: e.to, label: e.label || '' }));
    }
    if (imageAtts.length) body.attachments = imageAtts;
    if (currentModel.value?.configId) {
      body.configId = currentModel.value.configId;
    } else if (currentModel.value?.type === 'preset') {
      body.modelOverride = currentModel.value.id;
    }

    abortChat();

    await new Promise<void>((resolveStream) => {
      currentResolveStream = resolveStream;
      currentAiMsg = aiMsg;
      chatHandle = chatStream(body, {
        onText: (chunk: string) => {
          rawJsonBuf += chunk;
        },
        onComplete: (parsed: ChatResult) => {
          try {
            const addNodes = (parsed.add_nodes as OntologyNode[]) || [];
            const addEdges = (parsed.add_edges as OntologyEdge[]) || [];
            const reply = (parsed.reply || '').trim();
            if (reply) {
              aiMsg.text = reply;
            } else if (addNodes.length || addEdges.length) {
              aiMsg.text = `已从对话内容提取 ${addNodes.length} 个节点 / ${addEdges.length} 条关系,已加入图谱。`;
            } else {
              aiMsg.text = '未识别到可加入图谱的实体或关系,请补充更具体的描述。';
            }

            // 位置交给 App.vue 的 placeIncomingNodes + autoLayout 统一摆,
            // 这里只透传节点本身,避免圆形堆叠盖在已有图上。
            emit('update', addNodes.map(n => ({ ...n })), addEdges);

            if (addNodes.length || addEdges.length) {
              toast.success(`图谱已更新:+${addNodes.length} 节点 / +${addEdges.length} 关系`);
            }
          } catch (parseErr) {
            console.error('Failed to handle complete event:', parseErr);
            if (!aiMsg.text || aiMsg.text === '正在分析对话内容并构建图谱…') {
              aiMsg.text = '解析失败,模型返回内容非合法 JSON。';
            }
          }
        },
        onError: (msg: string) => {
          aiMsg.text = `错误: ${msg}`;
          resolveStream();
        },
        onClose: () => {
          if (aiMsg.text === '正在分析对话内容并构建图谱…') {
            // 兜底:complete 没触发但有累计的原始 JSON,尝试解析一次。
            const fallback = rawJsonBuf.trim().replace(/^```json/i, '').replace(/```$/, '').trim();
            if (fallback) {
              try {
                const parsed = JSON.parse(fallback) as ChatResult;
                const reply = (parsed.reply || '').trim();
                aiMsg.text = reply || '已收到回复,但未识别到图谱更新。';
                const addNodes = (parsed.add_nodes as OntologyNode[]) || [];
                const addEdges = (parsed.add_edges as OntologyEdge[]) || [];
                if (addNodes.length || addEdges.length) {
                  emit('update', addNodes.map(n => ({ ...n })), addEdges);
                  toast.success(`图谱已更新:+${addNodes.length} 节点 / +${addEdges.length} 关系`);
                }
              } catch {
                aiMsg.text = '未收到有效回复';
              }
            } else {
              aiMsg.text = '未收到有效回复';
            }
          }
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
    chatHandle = null;
    currentResolveStream = null;
    currentAiMsg = null;
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
    <ChatMessageList ref="msgListRef" :messages="msgs" :loading="loading" @preview="(a) => previewAtt = a" />
    <AttachmentPreview :attachment="previewAtt" @close="previewAtt = null" />
    <AttachmentChips :attachments="atts" @remove="(i) => atts = atts.filter((_, j) => j !== i)" />
    <div class="ch-input-area">
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
