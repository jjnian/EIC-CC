import { ref, type Ref } from 'vue';
import { confirm as uiConfirm } from './useConfirm';
import { toast } from './useToast';
import {
  listConversations,
  getConversation,
  updateConversation,
  deleteConversation as apiDeleteConversation,
  type ConversationDto,
} from '../api/conversations';
import { ApiError } from '../api/http';

export interface ChatMsgAttachment {
  name: string;
  type: string;
  kind: 'image' | 'text' | 'binary';
  size?: number;
  error?: string;
  /** 保存到消息里的内容(图片是 dataUrl,文本是字符串)。超出大小阈值不存。 */
  content?: string;
  /** 原文已被截断(读取时按 200KB 切片)。 */
  truncated?: boolean;
  /** 内容因为体积过大被剥离,只保留元数据。 */
  storedTruncated?: boolean;
}

/** 推演消息(嵌在对话里展示一次推演的全过程与结果)。 */
export interface PredictionMsg {
  intent: 'forward' | 'backward';
  /** 起点 / 目标节点(冗余存 label,便于历史回看时即使节点已删也能识别)。 */
  seeds: { id: string; label: string }[];
  prompt?: string;
  name?: string;
  status: 'running' | 'done' | 'error' | 'aborted';
  /** 已收到的推演步,按时间顺序追加。 */
  steps: {
    step: number;
    nodeId: string;
    label: string;
    type: string;
    triggeredBy?: string[];
    ruleId?: string | null;
    explanation?: string;
    confidence?: number;
  }[];
  pruneDetails?: { nodeId: string; label: string; reason: string }[];
  /** 完成后生成的分支 id(供"跳到该分支"等回看入口用)。 */
  branchId?: string;
  error?: string;
}

export interface ChatBuildStep {
  key: string;
  label: string;
  status: 'done' | 'running' | 'pending' | 'error';
}

/** LLM 主动抛回给用户的澄清问题(可选),用户点选后会作为下一条 user 消息发回。 */
export interface ChatQuestionMsg {
  /** 简短主题标签(≤6 字),如「客户类型」。 */
  header?: string;
  text: string;
  /** true 时可多选,需点「提交回答」统一发送;false/缺省为单选。 */
  multiSelect?: boolean;
  options: { label: string; value?: string }[];
  /** 用户当前勾选的选项 label(多选/单选共用,单选长度≤1)。 */
  selected?: string[];
  /** 已被用户回答时,记录答案摘要,渲染为只读样式避免重复点击。 */
  answered?: string;
}

export interface ChatMsg {
  role: 'a' | 'u' | 'prediction';
  text: string;
  atts?: ChatMsgAttachment[];
  prediction?: PredictionMsg;
  buildSteps?: ChatBuildStep[];
  buildDone?: boolean;
  /** LLM 抛回的澄清问题组(支持一次多个、单题多选)。 */
  questions?: ChatQuestionMsg[];
  /** 该问题组是否已整体提交/跳过。 */
  questionsDone?: boolean;
  /** 旧格式:单个问题。仅用于兼容历史会话渲染。 */
  question?: ChatQuestionMsg;
  /** 分析完成后生成的本体模型 ID，有值时显示"查看图谱"按钮。 */
  graphModelId?: string;
}

export interface Conversation {
  id: string;
  createdAt: number;
  updatedAt?: number;
  title: string;
  /** 绑定的本体血缘图 id：一会话一图。历史会话可能为空。 */
  modelId?: string;
  msgs: ChatMsg[];
}

const LEGACY_STORAGE_KEY = 'eic-conversations';
/**
 * 当前"活动会话"的 id。落库后写入,刷新/重挂载时优先据此续接同一条会话,
 * 避免一轮一记录:任何重挂载都回到同一 conversationId,而不是按时间猜"最近"或重新生成。
 */
const ACTIVE_CONV_KEY = 'eic-active-conversation';
const rememberActiveConv = (id: string) => {
  try { if (id) localStorage.setItem(ACTIVE_CONV_KEY, id); } catch { /* noop */ }
};
const forgetActiveConv = () => {
  try { localStorage.removeItem(ACTIVE_CONV_KEY); } catch { /* noop */ }
};
const readActiveConv = (): string | null => {
  try { return localStorage.getItem(ACTIVE_CONV_KEY); } catch { return null; }
};
const WELCOME_TEXT = '你好!我是推演助手。\n\n用自然语言描述实体和关系,我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试:「添加一个财务审计实体,与客户相关联」';
const PERSIST_DEBOUNCE_MS = 600;

export interface ConversationsCtx {
  /** 当前消息列表 ref(在 useChatMessages 中持有,这里通过 ctx 注入)。 */
  msgs: Ref<ChatMsg[]>;
  /** 切换会话时,如果有进行中的 SSE 流应该中止。 */
  abortChat: () => void;
  /** 新建会话时让父级清空图谱。 */
  clearGraph: () => void;
  /** 侧边栏重命名当前会话时，由外部注入手动标题，避免后续自动标题覆盖。 */
  setConversationTitle?: (title: string) => void;
  /** 每次成功落库后回调,用于把这条会话实时回填到侧栏(一条会话一个条目,原地更新)。 */
  onPersisted?: (c: { id: string; title: string; updatedAt: number }) => void;
}

/**
 * 会话(conversation)管理:后端持久化(`~/.tuiyan/conversations/*.json`)+ 内存缓存 + 切换/新建/删除/列表。
 */
export function useConversations(ctx: ConversationsCtx) {
  const conversationId = ref('');
  const conversationTitle = ref('新对话');
  // 当前会话绑定的本体血缘图 id；空串表示尚未建图（首次发言时由 ensureModel 惰性创建并回填）。
  const conversationModelId = ref('');
  const manualTitle = ref(false);
  const showConvPicker = ref(false);
  /** 内存里维护一份缓存,便于 sortedConversations 同步返回。 */
  const cache = ref<Record<string, Conversation>>({});

  const autoTitle = (msgList: ChatMsg[]): string => {
    const firstUser = msgList.find(m => m.role === 'u');
    if (!firstUser) return '新对话';
    const t = (firstUser.text || '').trim();
    return t.length > 20 ? t.slice(0, 20) + '…' : t;
  };

  const toDto = (c: Conversation): ConversationDto => ({
    id: c.id,
    title: c.title,
    modelId: c.modelId,
    createdAt: c.createdAt,
    updatedAt: c.updatedAt,
    msgs: c.msgs,
  });

  const fromDto = (d: ConversationDto): Conversation => ({
    id: d.id,
    title: d.title,
    modelId: d.modelId,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
    msgs: Array.isArray(d.msgs) ? d.msgs : [],
  });

  const refreshList = async (): Promise<Conversation[]> => {
    try {
      const list = (await listConversations()) || [];
      const next: Record<string, Conversation> = {};
      for (const d of list) next[d.id] = fromDto(d);
      cache.value = next;
      return list.map(fromDto);
    } catch (e) {
      console.warn('listConversations failed', e);
      return Object.values(cache.value);
    }
  };

  // 把旧 localStorage 数据一次性迁移到后端(成功后清空 key,避免重复迁移)。
  const migrateLegacyIfAny = async () => {
    try {
      const raw = localStorage.getItem(LEGACY_STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Record<string, Conversation>;
      const list = Object.values(parsed || {});
      if (!list.length) {
        localStorage.removeItem(LEGACY_STORAGE_KEY);
        return;
      }
      for (const c of list) {
        try {
          await updateConversation(c.id, {
            id: c.id,
            title: c.title || autoTitle(c.msgs || []),
            createdAt: c.createdAt || Date.now(),
            msgs: Array.isArray(c.msgs) ? c.msgs : [],
          });
        } catch (e) {
          console.warn('migrate conv failed', c.id, e);
        }
      }
      localStorage.removeItem(LEGACY_STORAGE_KEY);
    } catch (e) {
      console.warn('legacy migration failed', e);
    }
  };

  let persistTimer: number | null = null;
  let persistInFlight = false;
  /** 当前 msgs 是否仅包含初始欢迎消息(欢迎语会话永不持久化,避免 DB 里堆"空对话")。 */
  const isWelcomeOnly = () => {
    const list = ctx.msgs.value;
    if (list.length !== 1) return false;
    const m = list[0];
    return m.role === 'a' && (!m.atts || m.atts.length === 0);
  };
  /** 把当前 msgs 写回后端,带 600ms 抖动节流。欢迎语会话直接跳过(连 id 都不分配),避免空对话流入 DB。 */
  const persistCurrent = () => {
    if (isWelcomeOnly()) return;
    if (!conversationId.value) {
      conversationId.value = 'conv_' + Date.now();
    }
    if (persistTimer) { clearTimeout(persistTimer); persistTimer = null; }
    persistTimer = window.setTimeout(async () => {
      if (persistInFlight) return;
      persistInFlight = true;
      const id = conversationId.value;
      const title = autoTitle(ctx.msgs.value);
      if (!manualTitle.value && title !== '新对话') {
        conversationTitle.value = title;
      }
      const payload: ConversationDto = {
        id,
        title: conversationTitle.value || title,
        modelId: conversationModelId.value || undefined,
        createdAt: cache.value[id]?.createdAt || Number(id) || Date.now(),
        msgs: ctx.msgs.value.map(m => ({ ...m })),
      };
      try {
        const saved = await updateConversation(id, payload);
        cache.value[id] = fromDto(saved);
        rememberActiveConv(id);
        ctx.onPersisted?.({ id, title: saved.title || payload.title, updatedAt: saved.updatedAt || Date.now() });
      } catch (e) {
        console.warn('persist conversation failed', e);
        if (e instanceof ApiError) {
          toast.warn('对话历史未保存到服务器 (HTTP ' + e.status + ')');
        } else {
          toast.warn('对话历史未保存到服务器');
        }
      } finally {
        persistInFlight = false;
      }
    }, PERSIST_DEBOUNCE_MS);
  };

  const flushPersist = async () => {
    if (persistTimer) {
      clearTimeout(persistTimer);
      persistTimer = null;
    }
    if (persistInFlight) return;
    if (isWelcomeOnly()) return;
    if (!conversationId.value) {
      conversationId.value = 'conv_' + Date.now();
    }
    persistInFlight = true;
    try {
      const id = conversationId.value;
      const title = autoTitle(ctx.msgs.value);
      if (!manualTitle.value && title !== '新对话') {
        conversationTitle.value = title;
      }
      const payload: ConversationDto = {
        id,
        title: conversationTitle.value || title,
        modelId: conversationModelId.value || undefined,
        createdAt: cache.value[id]?.createdAt || Date.now(),
        msgs: ctx.msgs.value.map(m => ({ ...m })),
      };
      const saved = await updateConversation(id, payload);
      cache.value[id] = fromDto(saved);
      rememberActiveConv(id);
      ctx.onPersisted?.({ id, title: saved.title || payload.title, updatedAt: saved.updatedAt || Date.now() });
    } catch (e) {
      console.warn('flush conversation failed', e);
    } finally {
      persistInFlight = false;
    }
  };

  /**
   * 停掉一切对当前会话的写回。删除会话前必须调用,否则 600ms 防抖里的旧 PUT 会
   * 在 DELETE 后到达 backend,触发 save() 里的「不存在则 INSERT」分支,把刚删的
   * 会话又一次原样写回 — 这就是用户看到的「删了又出来」。
   */
  const cancelPersist = async () => {
    if (persistTimer) {
      clearTimeout(persistTimer);
      persistTimer = null;
    }
    const start = Date.now();
    while (persistInFlight && Date.now() - start < 3000) {
      await new Promise(r => setTimeout(r, 30));
    }
  };

  const initConversation = async (id?: string) => {
    ctx.abortChat();
    if (id && id !== 'new') {
      // 优先用缓存,缓存未命中再请求后端。
      let conv = cache.value[id];
      if (!conv) {
        try {
          const fetched = await getConversation(id);
          if (fetched) {
            conv = fromDto(fetched);
            cache.value[id] = conv;
          }
        } catch (e) {
          console.warn('load conversation failed', id, e);
        }
      }
      if (conv) {
        conversationId.value = conv.id;
        rememberActiveConv(conv.id);
        conversationTitle.value = conv.title || '新对话';
        conversationModelId.value = conv.modelId || '';
        manualTitle.value = !!conv.title && conv.title !== autoTitle(conv.msgs || []);
        // 历史消息一律视为已完成:把残留的 running 步骤标 done、buildDone=true,
        // 避免重新打开对话时还显示 loading 动画
        ctx.msgs.value = conv.msgs.map(m => {
          const copy: ChatMsg = { ...m };
          if (copy.role === 'a' && copy.buildSteps?.length) {
            copy.buildSteps = copy.buildSteps.map(s =>
              s.status === 'running' || s.status === 'pending'
                ? { ...s, status: 'done' as const }
                : s,
            );
            copy.buildDone = true;
          }
          return copy;
        });
        return;
      }
    }
    // 显式新对话:丢弃旧的活动会话句柄,避免重挂载时又把上一条会话续接回来。
    // 这条新会话的 id 在首次落库时才写回 ACTIVE_CONV_KEY。
    forgetActiveConv();
    conversationId.value = 'conv_' + Date.now();
    conversationTitle.value = '新对话';
    conversationModelId.value = '';
    manualTitle.value = false;
    ctx.msgs.value = [{ role: 'a', text: WELCOME_TEXT }];
    ctx.clearGraph();
  };

  /**
   * 把当前会话绑定到一张本体血缘图（首次发言惰性建图后调用）。
   * 已绑定且 id 未变则忽略；变化时落库，保证「一会话一图」。
   */
  const bindModel = (modelId: string) => {
    if (!modelId || conversationModelId.value === modelId) return;
    conversationModelId.value = modelId;
    persistCurrent();
  };

  const newConversation = async () => {
    showConvPicker.value = false;
    await initConversation('new');
  };

  const switchConversation = async (id: string) => {
    showConvPicker.value = false;
    await initConversation(id);
  };

  const deleteConversation = async (id: string, e: Event) => {
    e.stopPropagation();
    const ok = await uiConfirm({
      title: '删除对话',
      message: '确定删除这条对话记录?',
      confirmLabel: '删除',
      danger: true,
    });
    if (!ok) return;
    try {
      await apiDeleteConversation(id);
    } catch (err) {
      console.warn('delete conversation failed', err);
      toast.warn('删除失败');
      return;
    }
    delete cache.value[id];
    if (id === conversationId.value) {
      await initConversation('new');
    }
  };

  const sortedConversations = (): Conversation[] => {
    return Object.values(cache.value)
      .sort((a, b) => (b.updatedAt || b.createdAt) - (a.updatedAt || a.createdAt));
  };

  /** 启动时恢复最近会话(若无则保持欢迎态,首次发送时自动创建)。 */
  const restoreLatestOrNew = async () => {
    await migrateLegacyIfAny();
    const list = await refreshList();
    // 优先续接"上次活动的会话":重挂载/刷新都回到同一 conversationId,
    // 这样后续每一轮都落到同一条记录上,而不是新建一条(一轮一记录的根因)。
    const activeId = readActiveConv();
    if (activeId && list.some(c => c.id === activeId)) {
      await initConversation(activeId);
      return;
    }
    if (list.length > 0) {
      const latest = list.reduce((a, b) =>
        (a.updatedAt || a.createdAt) > (b.updatedAt || b.createdAt) ? a : b);
      await initConversation(latest.id);
    }
    // 没有历史时保留外部初始化的欢迎消息,不分配 id,避免持久化空对话。
  };

  return {
    conversationId,
    conversationTitle,
    conversationModelId,
    bindModel,
    manualTitle,
    showConvPicker,
    autoTitle,
    setConversationTitle: (title: string) => {
      conversationTitle.value = title;
      manualTitle.value = true;
      ctx.setConversationTitle?.(title);
    },
    persistCurrent,
    flushPersist,
    cancelPersist,
    initConversation,
    newConversation,
    switchConversation,
    deleteConversation,
    sortedConversations,
    restoreLatestOrNew,
  };
}
