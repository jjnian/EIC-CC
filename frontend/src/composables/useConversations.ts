import { ref, type Ref } from 'vue';
import { confirm as uiConfirm } from './useConfirm';
import { toast } from './useToast';

export interface ChatMsg {
  role: 'a' | 'u';
  text: string;
  atts?: { name: string; type: string; kind: 'image' | 'text' | 'binary'; error?: string }[];
}

export interface Conversation {
  id: string;
  createdAt: number;
  title: string;
  msgs: ChatMsg[];
}

const STORAGE_KEY = 'eic-conversations';
const WELCOME_TEXT = '你好!我是推演助手。\n\n用自然语言描述实体和关系,我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试:「添加一个财务审计实体,与客户相关联」';

export interface ConversationsCtx {
  /** 当前消息列表 ref(在 useChatMessages 中持有,这里通过 ctx 注入)。 */
  msgs: Ref<ChatMsg[]>;
  /** 切换会话时,如果有进行中的 SSE 流应该中止。 */
  abortChat: () => void;
  /** 新建会话时让父级清空图谱。 */
  clearGraph: () => void;
}

/**
 * 会话(conversation)管理:localStorage 持久化 + 切换/新建/删除/列表。
 */
export function useConversations(ctx: ConversationsCtx) {
  const conversationId = ref('');
  const conversationTitle = ref('新对话');
  const showConvPicker = ref(false);

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
      toast.warn('对话本地存储已满,最近内容可能未保存');
    }
  };

  const autoTitle = (msgList: ChatMsg[]): string => {
    const firstUser = msgList.find(m => m.role === 'u');
    if (!firstUser) return '新对话';
    const t = (firstUser.text || '').trim();
    return t.length > 20 ? t.slice(0, 20) + '…' : t;
  };

  const persistCurrent = () => {
    const convs = loadConversations();
    if (convs[conversationId.value]) {
      convs[conversationId.value].msgs = structuredClone(ctx.msgs.value);
      convs[conversationId.value].title = autoTitle(ctx.msgs.value);
    } else if (conversationId.value) {
      convs[conversationId.value] = {
        id: conversationId.value,
        createdAt: Number(conversationId.value) || Date.now(),
        title: autoTitle(ctx.msgs.value),
        msgs: structuredClone(ctx.msgs.value),
      };
    }
    saveConversations(convs);
  };

  const initConversation = (id?: string) => {
    ctx.abortChat();
    if (id && id !== 'new') {
      const convs = loadConversations();
      const conv = convs[id];
      if (conv) {
        conversationId.value = conv.id;
        conversationTitle.value = conv.title;
        ctx.msgs.value = structuredClone(conv.msgs);
        return;
      }
    }
    conversationId.value = Date.now().toString();
    conversationTitle.value = '新对话';
    ctx.msgs.value = [{ role: 'a', text: WELCOME_TEXT }];
    ctx.clearGraph();
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
      message: '确定删除这条对话记录?',
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

  /** 启动时恢复最近会话(若无则新建)。 */
  const restoreLatestOrNew = () => {
    const convs = loadConversations();
    const ids = Object.keys(convs);
    if (ids.length > 0) {
      const latest = ids.reduce((a, b) => convs[a].createdAt > convs[b].createdAt ? a : b);
      initConversation(latest);
    }
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
