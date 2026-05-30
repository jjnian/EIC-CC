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
import { listOntologies } from '../api/ontology';
import type { OntologyModel } from '../types';

export interface SidebarOntology {
  id: string;
  name: string;
  updatedAt: number;
}

const cacheOntologies = ref<Record<string, SidebarOntology[]>>({});
const loadingOntology = ref<Record<string, boolean>>({});
const loadedOntology = ref<Record<string, boolean>>({});

export interface SidebarConversation {
  id: string;
  title: string;
  updatedAt: number;
}

// 按 workspaceId 索引的缓存:同一 wsId 只首次拉取一次,除非显式 force 或写后回填
const cacheConvs = ref<Record<string, SidebarConversation[]>>({});
const cacheDS = ref<Record<string, DataSource[]>>({});
const loadingConv = ref<Record<string, boolean>>({});
const loadingDS = ref<Record<string, boolean>>({});
const loadedConv = ref<Record<string, boolean>>({});
const loadedDS = ref<Record<string, boolean>>({});

const toSidebar = (d: ConversationDto): SidebarConversation => ({
  id: d.id,
  title: d.title || '新对话',
  updatedAt: d.updatedAt || d.createdAt || 0,
});

/** 侧栏树:按 workspaceId 索引懒加载对话/数据源,跨工作空间互不影响。 */
export function useSidebarTree() {
  const loadConversations = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedConv.value[wsId]) return;
    if (loadingConv.value[wsId]) return;
    loadingConv.value[wsId] = true;
    try {
      const list = (await listConversations({ workspaceId: wsId })) || [];
      cacheConvs.value[wsId] = list
        .map(toSidebar)
        .sort((a, b) => b.updatedAt - a.updatedAt);
      loadedConv.value[wsId] = true;
    } catch (e) {
      console.warn('listConversations failed', wsId, e);
    } finally {
      loadingConv.value[wsId] = false;
    }
  };

  const loadDataSources = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedDS.value[wsId]) return;
    if (loadingDS.value[wsId]) return;
    loadingDS.value[wsId] = true;
    try {
      cacheDS.value[wsId] = (await listDataSources({ workspaceId: wsId })) || [];
      loadedDS.value[wsId] = true;
    } catch (e) {
      console.warn('listDataSources failed', wsId, e);
    } finally {
      loadingDS.value[wsId] = false;
    }
  };

  const loadOntologies = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedOntology.value[wsId]) return;
    if (loadingOntology.value[wsId]) return;
    loadingOntology.value[wsId] = true;
    try {
      const list = (await listOntologies()) || [];
      cacheOntologies.value[wsId] = list
        .map((m: OntologyModel) => ({ id: m.id, name: m.name || m.title || '未命名图谱', updatedAt: (m as any).updatedAt || (m.updated ? new Date(m.updated).getTime() : 0) }))
        .sort((a, b) => b.updatedAt - a.updatedAt);
      loadedOntology.value[wsId] = true;
    } catch (e) {
      console.warn('listOntologies failed', wsId, e);
    } finally {
      loadingOntology.value[wsId] = false;
    }
  };

  const getConversations = (wsId: string): SidebarConversation[] =>
    cacheConvs.value[wsId] || [];

  const getDataSources = (wsId: string): DataSource[] =>
    cacheDS.value[wsId] || [];

  const getOntologies = (wsId: string): SidebarOntology[] =>
    cacheOntologies.value[wsId] || [];

  const isLoadingConv = (wsId: string): boolean => !!loadingConv.value[wsId];
  const isLoadingDS = (wsId: string): boolean => !!loadingDS.value[wsId];
  const isLoadedConv = (wsId: string): boolean => !!loadedConv.value[wsId];
  const isLoadedDS = (wsId: string): boolean => !!loadedDS.value[wsId];
  const isLoadingOntology = (wsId: string): boolean => !!loadingOntology.value[wsId];
  const isLoadedOntology = (wsId: string): boolean => !!loadedOntology.value[wsId];

  const removeConversation = async (wsId: string, id: string) => {
    await apiDeleteConversation(id);
    if (cacheConvs.value[wsId]) {
      cacheConvs.value[wsId] = cacheConvs.value[wsId].filter(c => c.id !== id);
    }
  };

  const removeDataSource = async (wsId: string, id: string) => {
    await apiDeleteDataSource(id);
    if (cacheDS.value[wsId]) {
      cacheDS.value[wsId] = cacheDS.value[wsId].filter(d => d.id !== id);
    }
  };

  const upsertOntology = (wsId: string, m: SidebarOntology) => {
    if (!wsId) return;
    const arr = cacheOntologies.value[wsId] ? [...cacheOntologies.value[wsId]] : [];
    const idx = arr.findIndex(x => x.id === m.id);
    if (idx >= 0) arr[idx] = m;
    else arr.unshift(m);
    arr.sort((a, b) => b.updatedAt - a.updatedAt);
    cacheOntologies.value[wsId] = arr;
    loadedOntology.value[wsId] = true;
  };

  /** 新增/更新数据源后回填到缓存,不需要重新拉取接口。 */
  const upsertDataSource = (wsId: string, ds: DataSource) => {
    if (!wsId) return;
    const arr = cacheDS.value[wsId] ? [...cacheDS.value[wsId]] : [];
    const idx = arr.findIndex(x => x.id === ds.id);
    if (idx >= 0) arr[idx] = ds;
    else arr.unshift(ds);
    arr.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    cacheDS.value[wsId] = arr;
    loadedDS.value[wsId] = true;
  };

  /** 外部 useConversations 持久化后回填(只对当前 wsId 有效)。 */
  const upsertConversation = (wsId: string, c: SidebarConversation) => {
    if (!wsId) return;
    const arr = cacheConvs.value[wsId] || [];
    const idx = arr.findIndex(x => x.id === c.id);
    if (idx >= 0) arr[idx] = c;
    else arr.unshift(c);
    arr.sort((a, b) => b.updatedAt - a.updatedAt);
    cacheConvs.value[wsId] = arr;
    loadedConv.value[wsId] = true;
  };

  /** 清缓存:不带参清全部;带 wsId 清单个。切换当前工作空间时调用避免脏数据。 */
  const clearCache = (wsId?: string) => {
    if (wsId) {
      delete cacheConvs.value[wsId];
      delete cacheDS.value[wsId];
      delete cacheOntologies.value[wsId];
      delete loadedConv.value[wsId];
      delete loadedDS.value[wsId];
      delete loadedOntology.value[wsId];
    } else {
      cacheConvs.value = {};
      cacheDS.value = {};
      cacheOntologies.value = {};
      loadedConv.value = {};
      loadedDS.value = {};
      loadedOntology.value = {};
    }
  };

  return {
    loadConversations,
    loadDataSources,
    loadOntologies,
    getConversations,
    getDataSources,
    getOntologies,
    isLoadingConv,
    isLoadingDS,
    isLoadingOntology,
    isLoadedConv,
    isLoadedDS,
    isLoadedOntology,
    removeConversation,
    removeDataSource,
    upsertConversation,
    upsertDataSource,
    upsertOntology,
    clearCache,
  };
}
