<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { toast } from '../composables/useToast';
import { confirm as uiConfirm } from '../composables/useConfirm';
import { streamSSE, type SSEController } from '../composables/useSSE';
import { getConfig } from '../api/config';
import { getPrefs } from '../api/prefs';

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

// ========== 会话管理 ==========

interface Msg {
  role: 'a' | 'u';
  text: string;
  atts?: { name: string; type: string; kind: 'image' | 'text' | 'binary'; error?: string }[];
}

interface Conversation {
  id: string;
  createdAt: number;
  title: string;
  msgs: Msg[];
}

const STORAGE_KEY = 'eic-conversations';
const conversationId = ref('');
const conversationTitle = ref('新对话');
const showConvPicker = ref(false);

// 当前 chat SSE 控制器（切换会话或重发时 abort）
let chatStream: SSEController | null = null;
const abortChat = () => {
  if (chatStream) { try { chatStream.abort(); } catch {} chatStream = null; }
};

const loadConversations = (): Record<string, Conversation> => {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
  } catch (e) {
    console.warn('loadConversations failed', e);
    return {};
  }
};

const saveConversations = (convs: Record<string, Conversation>) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(convs));
  } catch (e: any) {
    console.warn('saveConversations failed', e);
    toast.warn('对话本地存储已满，最近内容可能未保存');
  }
};

const autoTitle = (msgList: Msg[]): string => {
  const firstUser = msgList.find(m => m.role === 'u');
  if (!firstUser) return '新对话';
  const t = (firstUser.text || '').trim();
  return t.length > 20 ? t.slice(0, 20) + '…' : t;
};

const persistCurrent = () => {
  const convs = loadConversations();
  if (convs[conversationId.value]) {
    convs[conversationId.value].msgs = structuredClone(msgs.value);
    convs[conversationId.value].title = autoTitle(msgs.value);
  } else if (conversationId.value) {
    // 新建会话首次保存
    convs[conversationId.value] = {
      id: conversationId.value,
      createdAt: Number(conversationId.value) || Date.now(),
      title: autoTitle(msgs.value),
      msgs: structuredClone(msgs.value),
    };
  }
  saveConversations(convs);
};

const initConversation = (id?: string) => {
  abortChat();
  if (id && id !== 'new') {
    const convs = loadConversations();
    const conv = convs[id];
    if (conv) {
      conversationId.value = conv.id;
      conversationTitle.value = conv.title;
      msgs.value = structuredClone(conv.msgs);
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

const deleteConversation = async (id: string, e: Event) => {
  e.stopPropagation();
  const ok = await uiConfirm({
    title: '删除对话',
    message: '确定删除这条对话记录？',
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
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

const msgs = ref<Msg[]>([
  { role: 'a', text: '你好！我是推演助手。\n\n用自然语言描述实体和关系，我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试：「添加一个财务审计实体，与客户相关联」' }
]);
const input = ref('');
const loading = ref(false);
const atts = ref<{name: string, type: string, kind: 'text' | 'image' | 'binary', content?: string, size: number, loading?: boolean}[]>([]);
const fileRef = ref<HTMLInputElement | null>(null);
const msgsRef = ref<HTMLElement | null>(null);
const inputRef = ref<HTMLTextAreaElement | null>(null);

// ========== @ 提及节点 / 关系 ==========
interface MentionItem {
  kind: 'node' | 'edge';
  id: string;
  label: string;
  sub: string;
}
const mentionOpen = ref(false);
const mentionQuery = ref('');
const mentionIndex = ref(0);
const mentionStart = ref(-1);

const mentionItems = computed<MentionItem[]>(() => {
  const q = mentionQuery.value.toLowerCase().trim();
  const nodeItems: MentionItem[] = (props.nodes || []).map(n => ({
    kind: 'node' as const,
    id: n.id,
    label: n.label || n.id,
    sub: n.type || '实体'
  }));
  const edgeItems: MentionItem[] = (props.edges || []).map(e => {
    const fromN = (props.nodes || []).find(n => n.id === e.from);
    const toN = (props.nodes || []).find(n => n.id === e.to);
    return {
      kind: 'edge' as const,
      id: e.id,
      label: e.label || '关系',
      sub: `${fromN?.label || e.from} → ${toN?.label || e.to}`
    };
  });
  const all = [...nodeItems, ...edgeItems];
  if (!q) return all.slice(0, 12);
  return all.filter(it =>
    it.label.toLowerCase().includes(q) || it.sub.toLowerCase().includes(q)
  ).slice(0, 12);
});

const checkMention = () => {
  const ta = inputRef.value;
  if (!ta) { mentionOpen.value = false; return; }
  const cursor = ta.selectionStart || 0;
  const before = input.value.slice(0, cursor);
  const atIdx = before.lastIndexOf('@');
  if (atIdx === -1) { mentionOpen.value = false; return; }
  const prevChar = atIdx > 0 ? before[atIdx - 1] : ' ';
  if (atIdx !== 0 && !/\s/.test(prevChar)) { mentionOpen.value = false; return; }
  const query = before.slice(atIdx + 1);
  if (/\s/.test(query)) { mentionOpen.value = false; return; }
  mentionStart.value = atIdx;
  mentionQuery.value = query;
  mentionOpen.value = true;
  mentionIndex.value = 0;
};

const selectMention = (it: MentionItem) => {
  const ta = inputRef.value;
  const queryLen = mentionQuery.value.length;
  const start = mentionStart.value;
  if (start < 0) return;
  const before = input.value.slice(0, start);
  const after = input.value.slice(start + 1 + queryLen);
  const token = it.kind === 'node' ? `@${it.label}` : `@「${it.label}」`;
  input.value = before + token + ' ' + after;
  mentionOpen.value = false;
  nextTick(() => {
    if (ta) {
      ta.focus();
      const pos = (before + token + ' ').length;
      ta.setSelectionRange(pos, pos);
    }
  });
};

const onInputKeydown = (e: KeyboardEvent) => {
  if (mentionOpen.value && mentionItems.value.length > 0) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      mentionIndex.value = (mentionIndex.value + 1) % mentionItems.value.length;
      return;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      mentionIndex.value = (mentionIndex.value - 1 + mentionItems.value.length) % mentionItems.value.length;
      return;
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      const it = mentionItems.value[mentionIndex.value];
      if (it) selectMention(it);
      return;
    } else if (e.key === 'Escape') {
      e.preventDefault();
      mentionOpen.value = false;
      return;
    }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    send();
  }
};

const onInputEvent = () => {
  nextTick(() => checkMention());
};

const onInputClick = () => {
  nextTick(() => checkMention());
};

const MAX_TEXT_BYTES = 200_000;  // 200KB per text file
const MAX_IMAGE_BYTES = 8_000_000; // 8MB per image

const TEXT_EXTS = ['txt','md','markdown','json','csv','tsv','log','xml','yaml','yml','html','htm','js','ts','tsx','jsx','py','java','c','cpp','h','hpp','go','rs','rb','sh','sql','toml','ini','env','vue','css','scss','less'];
const IMAGE_EXTS = ['png','jpg','jpeg','gif','webp','bmp'];

const readAsText = (f: File): Promise<string> => new Promise((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result || ''));
  r.onerror = () => reject(r.error);
  r.readAsText(f);
});

const readAsDataURL = (f: File): Promise<string> => new Promise((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result || ''));
  r.onerror = () => reject(r.error);
  r.readAsDataURL(f);
});

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

const presetModels = computed(() => availableModels.value.filter(x => x.type === 'preset'));
const customModels = computed(() => availableModels.value.filter(x => x.type === 'custom'));

const loadModels = async () => {
  try {
    const data = await getConfig();
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
      // 优先使用偏好里的默认模型
      let defaultId: string | null = null;
      try {
        const prefs = await getPrefs();
        if (prefs.defaultModelConfigId) defaultId = prefs.defaultModelConfigId as string;
      } catch { /* prefs 不可读时退化用第一个 */ }
      const preferred = defaultId
        ? models.find(m => m.configId === defaultId || m.id === defaultId)
        : null;
      currentModel.value = preferred || models[0];
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

onBeforeUnmount(() => abortChat());

// 每次消息变化自动保存
watch(msgs, () => {
  nextTick(() => {
    if (msgsRef.value) msgsRef.value.scrollTop = msgsRef.value.scrollHeight;
  });
  persistCurrent();
}, { deep: true });

const addFile = async (f: File) => {
  const ext = f.name.split('.').pop()?.toLowerCase() || '';
  const isImage = IMAGE_EXTS.includes(ext) || f.type.startsWith('image/');
  const isText = !isImage && (TEXT_EXTS.includes(ext) || f.type.startsWith('text/') || f.type === 'application/json');

  const att: any = {
    name: f.name,
    type: ext,
    kind: isImage ? 'image' : (isText ? 'text' : 'binary'),
    size: f.size,
    loading: true
  };
  atts.value.push(att);

  try {
    if (isImage) {
      if (f.size > MAX_IMAGE_BYTES) {
        att.error = `图片超过 ${Math.round(MAX_IMAGE_BYTES/1024/1024)}MB 限制`;
      } else {
        att.content = await readAsDataURL(f);
      }
    } else if (isText) {
      if (f.size > MAX_TEXT_BYTES) {
        const slice = f.slice(0, MAX_TEXT_BYTES);
        att.content = await readAsText(new File([slice], f.name));
        att.truncated = true;
      } else {
        att.content = await readAsText(f);
      }
    } else {
      att.error = `不支持的文件类型 (.${ext})，请上传文本或图片`;
    }
  } catch (e: any) {
    att.error = '读取失败: ' + (e?.message || e);
  } finally {
    att.loading = false;
  }
};

const send = async () => {
  if (!input.value.trim() && !atts.value.length) return;
  mentionOpen.value = false;

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
  // 用户消息显示用：只保留元信息
  const uaDisplay = atts.value.map(a => ({ name: a.name, type: a.type, kind: a.kind, error: a.error }));
  msgs.value.push({ role: 'u', text: txt, atts: uaDisplay });
  input.value = '';
  // 保留附件用于本次请求构建
  const requestAtts = [...validAtts];
  atts.value = [];
  loading.value = true;

  if (failed.length) {
    msgs.value.push({ role: 'a', text: '⚠ 部分文件未能加入：\n' + failed.map(a => `· ${a.name}: ${a.error}`).join('\n') });
  }

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

    // 构建发送给后端的消息：将文本类附件内容拼接入正文，图片作为独立 attachment
    let composedMessage = txt;
    const textAtts = requestAtts.filter(a => a.kind === 'text' && a.content);
    if (textAtts.length) {
      const docs = textAtts.map(a =>
        `=== 文件: ${a.name}${a.truncated ? ' (已截断)' : ''} ===\n${a.content}`
      ).join('\n\n');
      composedMessage = (txt ? txt + '\n\n' : '') + '附加文档内容：\n' + docs;
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

    // Abort any in-flight chat stream first (e.g. user clicked send while last one still running).
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
    <div class="ch-msgs" ref="msgsRef">
      <div v-for="(m, i) in msgs" :key="i" :class="['msg', `msg-${m.role === 'u' ? 'user' : 'asst'}`]">
        <div v-if="m.role === 'a'" class="avatar">推</div>
        <div class="msg-body">
          <div v-if="(m as any).atts?.length > 0" class="att-tags">
            <span v-for="(a, j) in (m as any).atts" :key="j" class="att-sm" :class="{ 'att-sm-err': a.error }" :title="a.error || a.name">
              {{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }} {{ a.name }}
            </span>
          </div>
          <div class="bubble" :class="{ streaming: m.role === 'a' && !m.text }">{{ m.text }}<span v-if="loading && m.role === 'a'" class="cursor" /></div>
        </div>
      </div>
    </div>
    <div v-if="atts.length > 0" class="att-row">
      <div v-for="(a, i) in atts" :key="i" class="att-chip" :class="{ 'att-err': a.error, 'att-img': a.kind === 'image' }" :title="a.error || (a.truncated ? '文件较大，已截断' : '')">
        <span class="att-kind">{{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }}</span>
        <span class="att-name">{{ a.name }}</span>
        <span v-if="a.loading" class="att-spin" />
        <span v-else-if="a.error" class="att-bad">!</span>
        <button type="button" @click="atts = atts.filter((_, j) => j !== i)">×</button>
      </div>
    </div>
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
