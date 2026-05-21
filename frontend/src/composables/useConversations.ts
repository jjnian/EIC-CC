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

export interface ChatMsg {
  role: 'a' | 'u';
  text: string;
  atts?: { name: string; type: string; kind: 'image' | 'text' | 'binary'; error?: string }[];
}

export interface Conversation {
  id: string;
  createdAt: number;
  updatedAt?: number;
  title: string;
  msgs: ChatMsg[];
}

const LEGACY_STORAGE_KEY = 'eic-conversations';
const WELCOME_TEXT = '你好!我是推演助手。\n\n用自然语言描述实体和关系,我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试:「添加一个财务审计实体,与客户相关联」';
const PERSIST_DEBOUNCE_MS = 600;

export interface ConversationsCtx {
  /** 当前消息列表 ref(在 useChatMessages 中持有,这里通过 ctx 注入)。 */
  msgs: Ref<ChatMsg[]>;
  /** 切换会话时,如果有进行中的 SSE 流应该中止。 */
  abortChat: () => void;
  /** 新建会话时让父级清空图谱。 */
  clearGraph: () => void;
}

/**
 * 会话(conversation)管理:后端持久化(`~/.tuiyan/conversations/*.json`)+ 内存缓存 + 切换/新建/删除/列表。
 */
export function useConversations(ctx: ConversationsCtx) {
  const conversationId = ref('');
  const conversationTitle = ref('新对话');
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
    createdAt: c.createdAt,
    updatedAt: c.updatedAt,
    msgs: c.msgs,
  });

  const fromDto = (d: ConversationDto): Conversation => ({
    id: d.id,
    title: d.title,
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
  /** 当前 msgs 是否仅包含初始欢迎消息(避免给空会话分配 id)。 */
  const isWelcomeOnly = () => {
    const list = ctx.msgs.value;
    if (list.length !== 1) return false;
    const m = list[0];
    return m.role === 'a' && (!m.atts || m.atts.length === 0);
  };
  /** 把当前 msgs 写回后端,带 600ms 抖动节流。 */
  const persistCurrent = () => {
    // 还没分配 id 且仅是欢迎语,不持久化空会话。
    if (!conversationId.value && isWelcomeOnly()) return;
    // 用户首次发言时为新会话分配 id。
    if (!conversationId.value) {
      conversationId.value = 'conv_' + Date.now();
    }
    if (persistTimer) { clearTimeout(persistTimer); persistTimer = null; }
    persistTimer = window.setTimeout(async () => {
      if (persistInFlight) return;
      persistInFlight = true;
      const id = conversationId.value;
      const title = autoTitle(ctx.msgs.value);
      conversationTitle.value = title === '新对话' ? conversationTitle.value : title;
      const payload: ConversationDto = {
        id,
        title: conversationTitle.value || title,
        createdAt: cache.value[id]?.createdAt || Number(id) || Date.now(),
        msgs: ctx.msgs.value.map(m => ({ ...m })),
      };
      try {
        const saved = await updateConversation(id, payload);
        cache.value[id] = fromDto(saved);
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
        conversationTitle.value = conv.title || '新对话';
        ctx.msgs.value = conv.msgs.map(m => ({ ...m }));
        return;
      }
    }
    conversationId.value = 'conv_' + Date.now();
    conversationTitle.value = '新对话';
    ctx.msgs.value = [{ role: 'a', text: WELCOME_TEXT }];
    ctx.clearGraph();
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
    showConvPicker,
    autoTitle,
    persistCurrent,
    initConversation,
    newConversation,
    switchConversation,
    deleteConversation,
    sortedConversations,
    restoreLatestOrNew,
  };
}
