import { ref, computed, watch, type Ref } from 'vue';
import type { OntologyNode } from '../types';

export interface GraphSearchCtx {
  nodes: () => OntologyNode[];
  selectNode: (id: string) => void;
  focusNode: (id: string) => void;
}

/**
 * 图谱画布内的搜索逻辑：输入关键词 → 匹配节点列表 → 上/下跳转 → 高亮。
 * 从 GraphCanvas.vue 抽出来降低主组件行数。
 */
export function useGraphSearch(ctx: GraphSearchCtx) {
  const searchRef = ref<HTMLInputElement | null>(null);
  const searchQuery = ref('');
  const searchIdx = ref(0);

  const searchMatches = computed(() => {
    const q = searchQuery.value.trim().toLowerCase();
    if (!q) return [];
    return ctx.nodes().filter(n =>
      n.label.toLowerCase().includes(q) ||
      n.type.toLowerCase().includes(q) ||
      (n.id && n.id.toLowerCase().includes(q))
    );
  });

  watch(searchQuery, () => { searchIdx.value = 0; });

  const jumpToNext = () => {
    if (!searchMatches.value.length) return;
    searchIdx.value = (searchIdx.value + 1) % searchMatches.value.length;
    const target = searchMatches.value[searchIdx.value];
    ctx.selectNode(target.id);
    ctx.focusNode(target.id);
  };

  const jumpToPrev = () => {
    if (!searchMatches.value.length) return;
    searchIdx.value = (searchIdx.value - 1 + searchMatches.value.length) % searchMatches.value.length;
    const target = searchMatches.value[searchIdx.value];
    ctx.selectNode(target.id);
    ctx.focusNode(target.id);
  };

  const clearSearch = () => {
    searchQuery.value = '';
    searchIdx.value = 0;
  };

  const isSearchMatch = (n: OntologyNode) => {
    if (!searchQuery.value) return false;
    return searchMatches.value.some(m => m.id === n.id);
  };

  const isCurrentSearchTarget = (n: OntologyNode) => {
    if (!searchMatches.value.length) return false;
    return searchMatches.value[searchIdx.value]?.id === n.id;
  };

  return {
    searchRef,
    searchQuery,
    searchIdx,
    searchMatches,
    jumpToNext,
    jumpToPrev,
    clearSearch,
    isSearchMatch,
    isCurrentSearchTarget,
  };
}
