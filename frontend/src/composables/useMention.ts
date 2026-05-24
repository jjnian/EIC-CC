import { ref, computed, nextTick, type Ref, type ComputedRef } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';

export interface MentionItem {
  kind: 'node' | 'edge';
  id: string;
  label: string;
  sub: string;
}

export interface MentionCtx {
  input: Ref<string>;
  inputRef: Ref<HTMLTextAreaElement | null>;
  nodes: ComputedRef<OntologyNode[]> | Ref<OntologyNode[]> | (() => OntologyNode[]);
  edges: ComputedRef<OntologyEdge[]> | Ref<OntologyEdge[]> | (() => OntologyEdge[]);
}

function unwrap<T>(v: ComputedRef<T> | Ref<T> | (() => T)): T {
  if (typeof v === 'function') return (v as () => T)();
  return (v as Ref<T>).value;
}

/**
 * 输入框 @mention 弹窗:节点/关系搜索 + 键盘导航 + 插入 token。
 */
export function useMention(ctx: MentionCtx) {
  const mentionOpen = ref(false);
  const mentionQuery = ref('');
  const mentionIndex = ref(0);
  const mentionStart = ref(-1);

  const mentionItems = computed<MentionItem[]>(() => {
    const q = mentionQuery.value.toLowerCase().trim();
    const nodes = unwrap(ctx.nodes) || [];
    const edges = unwrap(ctx.edges) || [];
    const nodeItems: MentionItem[] = nodes.map(n => ({
      kind: 'node' as const,
      id: n.id,
      label: n.label || n.id,
      sub: n.type || '实体',
    }));
    const edgeItems: MentionItem[] = edges.map(e => {
      const fromN = nodes.find(n => n.id === e.from);
      const toN = nodes.find(n => n.id === e.to);
      return {
        kind: 'edge' as const,
        id: e.id,
        label: e.label || '关系',
        sub: `${fromN?.label || e.from} → ${toN?.label || e.to}`,
      };
    });
    const all = [...nodeItems, ...edgeItems];
    if (!q) return all.slice(0, 12);
    return all
      .filter(it => it.label.toLowerCase().includes(q) || it.sub.toLowerCase().includes(q))
      .slice(0, 12);
  });

  const checkMention = () => {
    const ta = ctx.inputRef.value;
    if (!ta) { mentionOpen.value = false; return; }
    const cursor = ta.selectionStart || 0;
    const before = ctx.input.value.slice(0, cursor);
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
    const ta = ctx.inputRef.value;
    const queryLen = mentionQuery.value.length;
    const start = mentionStart.value;
    if (start < 0) return;
    const before = ctx.input.value.slice(0, start);
    const after = ctx.input.value.slice(start + 1 + queryLen);
    const token = it.kind === 'node' ? `@${it.label}` : `@「${it.label}」`;
    ctx.input.value = before + token + ' ' + after;
    mentionOpen.value = false;
    nextTick(() => {
      if (ta) {
        ta.focus();
        const pos = (before + token + ' ').length;
        ta.setSelectionRange(pos, pos);
      }
    });
  };

  const mentionListRef = ref<HTMLElement | null>(null);

  const scrollActiveIntoView = () => {
    nextTick(() => {
      const list = mentionListRef.value;
      if (!list) return;
      const items = list.querySelectorAll('.mention-item');
      const active = items[mentionIndex.value] as HTMLElement | undefined;
      if (active) {
        active.scrollIntoView({ block: 'nearest' });
      }
    });
  };

  /** 键盘事件:处理 ↑↓Enter/Tab/Esc,返回 true 表示已消费(调用方不再处理 Enter 发送)。 */
  const handleKeydown = (e: KeyboardEvent): boolean => {
    if (!mentionOpen.value || mentionItems.value.length === 0) return false;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      mentionIndex.value = (mentionIndex.value + 1) % mentionItems.value.length;
      scrollActiveIntoView();
      return true;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      mentionIndex.value = (mentionIndex.value - 1 + mentionItems.value.length) % mentionItems.value.length;
      scrollActiveIntoView();
      return true;
    }
    if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      const it = mentionItems.value[mentionIndex.value];
      if (it) selectMention(it);
      return true;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      mentionOpen.value = false;
      return true;
    }
    return false;
  };

  const closeMention = () => { mentionOpen.value = false; };

  return {
    mentionOpen,
    mentionQuery,
    mentionIndex,
    mentionItems,
    mentionListRef,
    checkMention,
    selectMention,
    handleKeydown,
    closeMention,
  };
}
