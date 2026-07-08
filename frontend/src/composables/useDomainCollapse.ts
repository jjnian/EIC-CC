import { ref, computed } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';

/** 领域聚合节点的 id 前缀（区分真实节点，交互层据此拦截）。 */
export const DOMAIN_NODE_PREFIX = 'dom__';
/** 聚合跨域边的 id 前缀。 */
export const DOMAIN_EDGE_PREFIX = 'dome__';
const UNDOMAIN = '未分域';

/**
 * 领域聚合折叠：贴合实际的图必然大（千级节点），平铺不可用。
 * 把选中的领域(domain)折叠成一个「超级节点」（标签带成员数），域内边隐藏、
 * 跨域边自动聚合（×N），点击超级节点即展开——复杂而不混乱的逐层下钻。
 * <p>纯派生视图：不改动真实 nodes/edges，折叠态只存在于渲染层；
 * 编辑/选择/血缘分析仍作用于真实图。
 */
export function useDomainCollapse(
  getNodes: () => OntologyNode[],
  getEdges: () => OntologyEdge[],
) {
  /** 当前处于折叠态的领域名集合。 */
  const collapsed = ref<Set<string>>(new Set());

  const domainOf = (n: OntologyNode) => (n.domain || '').trim() || UNDOMAIN;

  /** 图中出现的领域清单（按成员数降序）。 */
  const domains = computed(() => {
    const m = new Map<string, number>();
    for (const n of getNodes()) m.set(domainOf(n), (m.get(domainOf(n)) || 0) + 1);
    return [...m.entries()]
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count);
  });

  /** 是否值得展示领域面板：至少两个领域，或唯一领域不是"未分域"。 */
  const hasDomains = computed(() =>
    domains.value.length > 1
    || (domains.value.length === 1 && domains.value[0].name !== UNDOMAIN));

  /** 渲染层节点：折叠域的成员被一个超级节点（居于成员质心）替换。 */
  const displayNodes = computed<OntologyNode[]>(() => {
    if (!collapsed.value.size) return getNodes();
    const out: OntologyNode[] = [];
    const agg = new Map<string, { sx: number; sy: number; count: number }>();
    for (const n of getNodes()) {
      const d = domainOf(n);
      if (!collapsed.value.has(d)) { out.push(n); continue; }
      const a = agg.get(d) || { sx: 0, sy: 0, count: 0 };
      a.sx += n.x || 0; a.sy += n.y || 0; a.count++;
      agg.set(d, a);
    }
    for (const [d, a] of agg) {
      out.push({
        id: DOMAIN_NODE_PREFIX + d,
        label: `🗂 ${d}（${a.count}）`,
        type: 'domain',
        domain: d,
        x: a.count ? Math.round(a.sx / a.count) : 0,
        y: a.count ? Math.round(a.sy / a.count) : 0,
      } as OntologyNode);
    }
    return out;
  });

  /** 渲染层边：域内边隐藏；跨域边重定向到超级节点并按 (from,to) 聚合为一条（标签 ×N）。 */
  const displayEdges = computed<OntologyEdge[]>(() => {
    if (!collapsed.value.size) return getEdges();
    const nodeDomain = new Map<string, string>();
    for (const n of getNodes()) nodeDomain.set(n.id, domainOf(n));

    const out: OntologyEdge[] = [];
    const agg = new Map<string, { from: string; to: string; label?: string; rel_type?: string; count: number }>();
    for (const e of getEdges()) {
      const fd = nodeDomain.get(e.from);
      const td = nodeDomain.get(e.to);
      const from = fd && collapsed.value.has(fd) ? DOMAIN_NODE_PREFIX + fd : e.from;
      const to = td && collapsed.value.has(td) ? DOMAIN_NODE_PREFIX + td : e.to;
      if (from === e.from && to === e.to) { out.push(e); continue; }   // 两端都没折叠，原样保留
      if (from === to) continue;                                       // 域内边折叠后隐藏
      const key = from + '→' + to;
      const hit = agg.get(key);
      if (hit) hit.count++;
      else agg.set(key, { from, to, label: e.label, rel_type: e.rel_type, count: 1 });
    }
    let i = 0;
    for (const a of agg.values()) {
      out.push({
        id: DOMAIN_EDGE_PREFIX + (i++),
        from: a.from,
        to: a.to,
        label: a.count > 1 ? `${a.count} 条关系` : a.label,
        rel_type: a.rel_type,
      } as OntologyEdge);
    }
    return out;
  });

  const isDomainNode = (id: string | null | undefined) => !!id && id.startsWith(DOMAIN_NODE_PREFIX);
  const isDomainEdge = (id: string | null | undefined) => !!id && id.startsWith(DOMAIN_EDGE_PREFIX);
  const domainOfNodeId = (id: string) => id.slice(DOMAIN_NODE_PREFIX.length);

  const toggle = (name: string) => {
    const s = new Set(collapsed.value);
    if (s.has(name)) s.delete(name); else s.add(name);
    collapsed.value = s;
  };
  const expand = (name: string) => {
    if (!collapsed.value.has(name)) return;
    const s = new Set(collapsed.value);
    s.delete(name);
    collapsed.value = s;
  };
  const collapseAll = () => { collapsed.value = new Set(domains.value.map(d => d.name)); };
  const expandAll = () => { collapsed.value = new Set(); };

  return {
    collapsed, domains, hasDomains,
    displayNodes, displayEdges,
    isDomainNode, isDomainEdge, domainOfNodeId,
    toggle, expand, collapseAll, expandAll,
  };
}
