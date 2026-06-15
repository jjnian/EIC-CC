import { ref } from 'vue';

/**
 * 侧边栏三个区段（历史对话/血缘图/数据源/经验库）的折叠状态。
 * <p>用 localStorage 持久化，全局共享（所有工作空间共用一份偏好，避免每个 ws 都要单独点开）。
 * 从 WorkspaceNode.vue 抽出，独立可测、零外部依赖。
 */
export type SectionKey = 'convs' | 'ontologies' | 'ds' | 'exp';

const SECTIONS_KEY = 'tuiyan.sidebar-sections';

const defaultSections = (): Record<SectionKey, boolean> =>
  ({ convs: true, ontologies: true, ds: true, exp: true });

const readSections = (): Record<SectionKey, boolean> => {
  try {
    const raw = localStorage.getItem(SECTIONS_KEY);
    if (!raw) return defaultSections();
    const parsed = JSON.parse(raw) as Partial<Record<SectionKey, boolean>>;
    return {
      convs: parsed.convs !== false,
      ontologies: parsed.ontologies !== false,
      ds: parsed.ds !== false,
      exp: parsed.exp !== false,
    };
  } catch { return defaultSections(); }
};

export function useSidebarSections() {
  const sections = ref<Record<SectionKey, boolean>>(readSections());

  const toggleSection = (key: SectionKey, e: Event) => {
    e.stopPropagation();
    sections.value = { ...sections.value, [key]: !sections.value[key] };
    try { localStorage.setItem(SECTIONS_KEY, JSON.stringify(sections.value)); } catch { /* noop */ }
  };

  return { sections, toggleSection };
}
