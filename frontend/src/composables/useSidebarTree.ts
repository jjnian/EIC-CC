import { ref } from 'vue';
import {
  listConversations,
  deleteConversation as apiDeleteConversation,
  type ConversationDto,
} from '../api/conversations';
import {
  listDataSources,
  deleteDataSource as apiDeleteDataSource,
  type DataSource,
} from '../api/dataSources';

export interface SidebarConversation {
  id: string;
  title: string;
  updatedAt: number;
}

const conversations = ref<SidebarConversation[]>([]);
const dataSources = ref<DataSource[]>([]);
const loadingConvs = ref(false);
const loadingDataSources = ref(false);

const toSidebar = (d: ConversationDto): SidebarConversation => ({
  id: d.id,
  title: d.title || '新对话',
  updatedAt: d.updatedAt || d.createdAt || 0,
});

/** 侧栏树需要的轻量数据 — 只关心 id / 标题 / 时间，不拉取消息体。 */
export function useSidebarTree() {
  const loadConversations = async () => {
    loadingConvs.value = true;
    try {
      const list = (await listConversations()) || [];
      conversations.value = list
        .map(toSidebar)
        .sort((a, b) => b.updatedAt - a.updatedAt);
    } catch (e) {
      console.warn('listConversations failed', e);
    } finally {
      loadingConvs.value = false;
    }
  };

  const loadDataSources = async () => {
    loadingDataSources.value = true;
    try {
      dataSources.value = (await listDataSources()) || [];
    } catch (e) {
      console.warn('listDataSources failed', e);
    } finally {
      loadingDataSources.value = false;
    }
  };

  const removeConversation = async (id: string) => {
    await apiDeleteConversation(id);
    conversations.value = conversations.value.filter(c => c.id !== id);
  };

  const removeDataSource = async (id: string) => {
    await apiDeleteDataSource(id);
    dataSources.value = dataSources.value.filter(d => d.id !== id);
  };

  /** 把对话 upsert 进缓存（外部 useConversations 持久化后调用，保持侧栏即时更新）。 */
  const upsertConversation = (c: SidebarConversation) => {
    const idx = conversations.value.findIndex(x => x.id === c.id);
    if (idx >= 0) conversations.value[idx] = c;
    else conversations.value.unshift(c);
    conversations.value.sort((a, b) => b.updatedAt - a.updatedAt);
  };

  return {
    conversations,
    dataSources,
    loadingConvs,
    loadingDataSources,
    loadConversations,
    loadDataSources,
    removeConversation,
    removeDataSource,
    upsertConversation,
  };
}
