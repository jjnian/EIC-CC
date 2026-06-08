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
import { listFolders, type DataSourceFolder } from '../api/folders';
import { listExperienceFolders, type ExperienceFolder } from '../api/experienceFolders';
import {
  listExperiences,
  deleteExperience as apiDeleteExperience,
  type Experience,
} from '../api/experiences';
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
const cacheFolders = ref<Record<string, DataSourceFolder[]>>({});
const cacheExpFolders = ref<Record<string, ExperienceFolder[]>>({});
const cacheExp = ref<Record<string, Experience[]>>({});
const loadingConv = ref<Record<string, boolean>>({});
const loadingDS = ref<Record<string, boolean>>({});
const loadingFolders = ref<Record<string, boolean>>({});
const loadingExpFolders = ref<Record<string, boolean>>({});
const loadingExp = ref<Record<string, boolean>>({});
const loadedConv = ref<Record<string, boolean>>({});
const loadedDS = ref<Record<string, boolean>>({});
const loadedFolders = ref<Record<string, boolean>>({});
const loadedExpFolders = ref<Record<string, boolean>>({});
const loadedExp = ref<Record<string, boolean>>({});

const toSidebar = (d: ConversationDto): SidebarConversation => ({
  id: d.id,
  title: d.title || '新对话',
  updatedAt: d.updatedAt || d.createdAt || 0,
});

/** 侧栏树:按 workspaceId 索引懒加载对话/数据源,跨工作空间互不影响。 */
export function useSidebarTree() {
  // 历史改造前 ChatPanel 会把仅含欢迎语的空会话也持久化进 DB(每次新建对话/切换工作空间都留一条)。
  // 现在持久化端已经修正,但 DB 里残留的「新对话」记录还在 — 这里在侧栏直接过滤掉,只显示用户真正发过话的会话。
  const hasUserMessage = (d: ConversationDto): boolean =>
    !!(d.msgs && d.msgs.some((m: any) => m && m.role === 'u'));

  const loadConversations = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedConv.value[wsId]) return;
    if (loadingConv.value[wsId]) return;
    loadingConv.value[wsId] = true;
    try {
      const list = (await listConversations({ workspaceId: wsId })) || [];
      cacheConvs.value[wsId] = list
        .filter(hasUserMessage)
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

  const loadFolders = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedFolders.value[wsId]) return;
    if (loadingFolders.value[wsId]) return;
    loadingFolders.value[wsId] = true;
    try {
      cacheFolders.value[wsId] = (await listFolders(wsId)) || [];
      loadedFolders.value[wsId] = true;
    } catch (e) {
      console.warn('listFolders failed', wsId, e);
    } finally {
      loadingFolders.value[wsId] = false;
    }
  };

  const loadExpFolders = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedExpFolders.value[wsId]) return;
    if (loadingExpFolders.value[wsId]) return;
    loadingExpFolders.value[wsId] = true;
    try {
      cacheExpFolders.value[wsId] = (await listExperienceFolders(wsId)) || [];
      loadedExpFolders.value[wsId] = true;
    } catch (e) {
      console.warn('listExperienceFolders failed', wsId, e);
    } finally {
      loadingExpFolders.value[wsId] = false;
    }
  };

  const loadExperiences = async (wsId: string, force = false): Promise<void> => {
    if (!wsId) return;
    if (!force && loadedExp.value[wsId]) return;
    if (loadingExp.value[wsId]) return;
    loadingExp.value[wsId] = true;
    try {
      cacheExp.value[wsId] = ((await listExperiences({ workspaceId: wsId })) || [])
        .sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
      loadedExp.value[wsId] = true;
    } catch (e) {
      console.warn('listExperiences failed', wsId, e);
    } finally {
      loadingExp.value[wsId] = false;
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

  const getFolders = (wsId: string): DataSourceFolder[] =>
    cacheFolders.value[wsId] || [];

  const getExpFolders = (wsId: string): ExperienceFolder[] =>
    cacheExpFolders.value[wsId] || [];

  const getOntologies = (wsId: string): SidebarOntology[] =>
    cacheOntologies.value[wsId] || [];

  const getExperiences = (wsId: string): Experience[] =>
    cacheExp.value[wsId] || [];

  const isLoadingConv = (wsId: string): boolean => !!loadingConv.value[wsId];
  const isLoadingDS = (wsId: string): boolean => !!loadingDS.value[wsId];
  const isLoadingExp = (wsId: string): boolean => !!loadingExp.value[wsId];
  const isLoadingFolders = (wsId: string): boolean => !!loadingFolders.value[wsId];
  const isLoadingExpFolders = (wsId: string): boolean => !!loadingExpFolders.value[wsId];
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

  const renameConversation = (wsId: string, id: string, title: string, updatedAt = Date.now()) => {
    const list = cacheConvs.value[wsId] || [];
    const current = list.find(c => c.id === id);
    if (current) {
      const next = { ...current, title, updatedAt };
      const idx = list.findIndex(c => c.id === id);
      if (idx >= 0) list[idx] = next;
      cacheConvs.value[wsId] = [...list].sort((a, b) => b.updatedAt - a.updatedAt);
    }
    else if (wsId) {
      cacheConvs.value[wsId] = [{ id, title, updatedAt }, ...list];
      loadedConv.value[wsId] = true;
    }
  };

  const renameOntology = (wsId: string, id: string, name: string, updatedAt = Date.now()) => {
    const list = cacheOntologies.value[wsId] || [];
    const current = list.find(m => m.id === id);
    if (current) {
      const idx = list.findIndex(m => m.id === id);
      if (idx >= 0) list[idx] = { ...current, name, updatedAt };
      cacheOntologies.value[wsId] = [...list].sort((a, b) => b.updatedAt - a.updatedAt);
    } else if (wsId) {
      cacheOntologies.value[wsId] = [{ id, name, updatedAt }, ...list];
      loadedOntology.value[wsId] = true;
    }
  };

  const removeOntology = (wsId: string, id: string) => {
    if (cacheOntologies.value[wsId]) {
      cacheOntologies.value[wsId] = cacheOntologies.value[wsId].filter(m => m.id !== id);
    }
  };

  const removeDataSource = async (wsId: string, id: string) => {
    await apiDeleteDataSource(id);
    if (cacheDS.value[wsId]) {
      cacheDS.value[wsId] = cacheDS.value[wsId].filter(d => d.id !== id);
    }
  };

  const removeExperience = async (wsId: string, id: string) => {
    await apiDeleteExperience(id);
    if (cacheExp.value[wsId]) {
      cacheExp.value[wsId] = cacheExp.value[wsId].filter(e => e.id !== id);
    }
  };

  /** 新增/更新经验后回填到缓存,不需要重新拉取接口。 */
  const upsertExperience = (wsId: string, exp: Experience) => {
    if (!wsId) return;
    const arr = cacheExp.value[wsId] ? [...cacheExp.value[wsId]] : [];
    const idx = arr.findIndex(x => x.id === exp.id);
    if (idx >= 0) arr[idx] = exp;
    else arr.unshift(exp);
    arr.sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
    cacheExp.value[wsId] = arr;
    loadedExp.value[wsId] = true;
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
      delete cacheFolders.value[wsId];
      delete cacheExpFolders.value[wsId];
      delete cacheOntologies.value[wsId];
      delete cacheExp.value[wsId];
      delete loadedConv.value[wsId];
      delete loadedDS.value[wsId];
      delete loadedFolders.value[wsId];
      delete loadedExpFolders.value[wsId];
      delete loadedOntology.value[wsId];
      delete loadedExp.value[wsId];
    } else {
      cacheConvs.value = {};
      cacheDS.value = {};
      cacheFolders.value = {};
      cacheExpFolders.value = {};
      cacheOntologies.value = {};
      cacheExp.value = {};
      loadedConv.value = {};
      loadedDS.value = {};
      loadedFolders.value = {};
      loadedExpFolders.value = {};
      loadedOntology.value = {};
      loadedExp.value = {};
    }
  };

  return {
    loadConversations,
    loadDataSources,
    loadFolders,
    loadExpFolders,
    loadOntologies,
    loadExperiences,
    getConversations,
    getDataSources,
    getFolders,
    getExpFolders,
    getOntologies,
    getExperiences,
    isLoadingConv,
    isLoadingDS,
    isLoadingExp,
    isLoadingFolders,
    isLoadingExpFolders,
    isLoadingOntology,
    isLoadedConv,
    isLoadedDS,
    isLoadedOntology,
    removeConversation,
    renameConversation,
    renameOntology,
    removeOntology,
    removeDataSource,
    removeExperience,
    upsertConversation,
    upsertDataSource,
    upsertExperience,
    upsertOntology,
    clearCache,
  };
}
