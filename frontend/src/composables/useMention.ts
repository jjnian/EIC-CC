import { ref, computed, nextTick, type Ref, type ComputedRef } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';

export interface MentionItem {
  kind: 'graph' | 'relation' | 'node' | 'datasource';
  id: string;
  label: string;
  sub: string;
}

/**
 * 已经被用户选中并插入到输入框的引用记录。
 * <p>token 是插入到输入框里的可见字符串(如 "@节点名"),
 * 输入框文本变化时,我们通过子串匹配来检测哪些 active 引用已被用户手动删除,
 * 从而保持 mentions 与 input 文本同步。
 */
export interface ActiveMention {
  kind: 'graph' | 'relation' | 'node' | 'datasource';
  id: string;
  label: string;
  token: string;
}

export interface MentionTreeItem {
  item: MentionItem;
  index: number;
}

export interface MentionTreeGroup {
  key: string;
  label: string;
  items: MentionTreeItem[];
}

export interface MentionTreeSection {
  key: string;
  label: string;
  groups: MentionTreeGroup[];
}

export interface MentionCtx {
  input: Ref<string>;
  inputRef: Ref<HTMLTextAreaElement | null>;
  nodes: ComputedRef<OntologyNode[]> | Ref<OntologyNode[]> | (() => OntologyNode[]);
  edges: ComputedRef<OntologyEdge[]> | Ref<OntologyEdge[]> | (() => OntologyEdge[]);
  graphLabel?: ComputedRef<string> | Ref<string> | (() => string);
  dataSources?: ComputedRef<{ id: string; name: string }[]> | Ref<{ id: string; name: string }[]> | (() => { id: string; name: string }[]);
}

function unwrap<T>(v: ComputedRef<T> | Ref<T> | (() => T)): T {
  if (typeof v === 'function') return (v as () => T)();
  return (v as Ref<T>).value;
}

export function useMention(ctx: MentionCtx) {
  const mentionOpen = ref(false);
  const mentionQuery = ref('');
  const mentionIndex = ref(0);
  const mentionStart = ref(-1);
  // 用户已经选中并插入到输入框的所有 @ 引用 — 后端据此做定向上下文
  const activeMentions = ref<ActiveMention[]>([]);

  const mentionItems = computed<MentionItem[]>(() => {
    const q = mentionQuery.value.toLowerCase().trim();
    const nodes = unwrap(ctx.nodes) || [];
    const edges = unwrap(ctx.edges) || [];
    const graphLabel = ctx.graphLabel ? unwrap(ctx.graphLabel) : '';
    const dataSources = ctx.dataSources ? unwrap(ctx.dataSources) || [] : [];

    const items: MentionItem[] = [];
    if (graphLabel) {
      items.push({ kind: 'graph', id: 'graph', label: graphLabel, sub: '当前图谱' });
    }

    for (const n of nodes) {
      items.push({
        kind: 'node',
        id: n.id,
        label: n.label || n.id,
        sub: n.type || '实体',
      });
    }

    for (const e of edges) {
      const fromN = nodes.find(n => n.id === e.from);
      const toN = nodes.find(n => n.id === e.to);
      items.push({
        kind: 'relation',
        id: e.id,
        label: e.label || '关系',
        sub: `${fromN?.label || e.from} → ${toN?.label || e.to}`,
      });
    }

    for (const ds of dataSources) {
      items.push({
        kind: 'datasource',
        id: ds.id,
        label: ds.name,
        sub: '数据源',
      });
    }

    if (!q) return items.slice(0, 24);
    return items
      .filter(it => it.label.toLowerCase().includes(q) || it.sub.toLowerCase().includes(q))
      .slice(0, 24);
  });

  const mentionTree = computed<MentionTreeSection[]>(() => {
    const sections: MentionTreeSection[] = [
      {
        key: 'graph',
        label: '图谱',
        groups: [
          { key: 'graph-self', label: '当前图谱', items: [] },
          { key: 'nodes', label: '节点', items: [] },
          { key: 'relations', label: '关系', items: [] },
        ],
      },
      {
        key: 'datasource',
        label: '数据源',
        groups: [
          { key: 'datasources', label: '本空间数据源', items: [] },
        ],
      },
    ];

    mentionItems.value.forEach((item, index) => {
      const row = { item, index };
      if (item.kind === 'graph') sections[0].groups[0].items.push(row);
      else if (item.kind === 'node') sections[0].groups[1].items.push(row);
      else if (item.kind === 'relation') sections[0].groups[2].items.push(row);
      else if (item.kind === 'datasource') sections[1].groups[0].items.push(row);
    });

    return sections
      .map(section => ({
        ...section,
        groups: section.groups.filter(group => group.items.length > 0),
      }))
      .filter(section => section.groups.length > 0);
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
    const token = it.kind === 'graph' ? `@图谱:${it.label}`
      : it.kind === 'relation' ? `@关系:${it.label}`
      : it.kind === 'datasource' ? `@数据源:${it.label}`
      : `@${it.label}`;
    ctx.input.value = before + token + ' ' + after;
    mentionOpen.value = false;
    // 记录这次引用 (按 token + kind + id 三元组去重,允许同 label 不同 id 共存)
    const sig = `${it.kind}|${it.id}|${token}`;
    if (!activeMentions.value.some(m => `${m.kind}|${m.id}|${m.token}` === sig)) {
      activeMentions.value.push({ kind: it.kind, id: it.id, label: it.label, token });
    }
    nextTick(() => {
      if (ta) {
        ta.focus();
        const pos = (before + token + ' ').length;
        ta.setSelectionRange(pos, pos);
      }
    });
  };

  /**
   * 输入文本变化时调用,把已经被用户手动删除 token 的 mention 清出 activeMentions。
   * <p>简单匹配 token 是否还在 input 中即可;同一 token 出现多次时也只保留一份引用。
   */
  const syncActiveMentions = () => {
    if (activeMentions.value.length === 0) return;
    const txt = ctx.input.value;
    activeMentions.value = activeMentions.value.filter(m => txt.includes(m.token));
  };

  /** 发送后清空已激活的 mentions（与 input 一起清掉）。 */
  const consumeActiveMentions = (): ActiveMention[] => {
    const out = activeMentions.value.slice();
    activeMentions.value = [];
    return out;
  };

  const mentionListRef = ref<HTMLElement | null>(null);

  const scrollActiveIntoView = () => {
    nextTick(() => {
      const list = mentionListRef.value;
      if (!list) return;
      const items = list.querySelectorAll('.mention-item');
      const active = items[mentionIndex.value] as HTMLElement | undefined;
      if (active) active.scrollIntoView({ block: 'nearest' });
    });
  };

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
    mentionTree,
    mentionListRef,
    activeMentions,
    checkMention,
    selectMention,
    handleKeydown,
    closeMention,
    syncActiveMentions,
    consumeActiveMentions,
  };
}
