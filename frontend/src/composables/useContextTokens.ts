import { computed, type Ref } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import type { ChatMsg } from './useConversations';

/** 粗略 token 估算：约 4 字符 ≈ 1 token。 */
const estimateTokens = (text: string) => Math.ceil(text.length / 4);

/**
 * 估算当前对话要发给模型的上下文 token 量（图谱节点/边 + 最近 40 条对话历史），并格式化展示。
 * 从 ChatPanel.vue 抽出，纯计算无副作用。
 */
export function useContextTokens(deps: {
  nodes: () => OntologyNode[];
  edges: () => OntologyEdge[];
  msgs: Ref<ChatMsg[]>;
}) {
  const contextTokenEstimate = computed(() => {
    let total = 0;
    const nodes = deps.nodes();
    const edges = deps.edges();
    if (nodes?.length) {
      const nodesStr = JSON.stringify(nodes.map(n => ({ id: n.id, label: n.label, type: n.type })));
      total += estimateTokens(nodesStr);
    }
    if (edges?.length) {
      const edgesStr = JSON.stringify(edges.map(e => ({ id: e.id, from: e.from, to: e.to, label: e.label || '' })));
      total += estimateTokens(edgesStr);
    }
    // 对话历史（最近 40 条）
    const history = deps.msgs.value
      .filter(m => (m.role === 'u' || m.role === 'a') && m.text)
      .slice(-40);
    for (const m of history) total += estimateTokens(m.text);
    return total;
  });

  const formatTokens = (n: number) => (n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n));

  return { contextTokenEstimate, formatTokens };
}
