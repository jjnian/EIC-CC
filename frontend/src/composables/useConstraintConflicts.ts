import { computed } from 'vue';
import type { OntologyEdge } from '../types';

type ConstraintMode = 'force' | 'block' | 'probability';
type Constraint = { nodeId: string; mode: ConstraintMode; probability?: number };

interface ConflictWarning { message: string; }

/**
 * 约束冲突检测：检查 force/block 之间是否存在直接因果边。
 *
 * 数据来源通过 getter 注入：
 *  - getConstraints: 当前约束列表
 *  - getEdges: 当前图谱边
 *  - getNodeLabel: 节点 id → 展示标签（原逻辑用 nodeMap.value[id]?.label || id，
 *    组件内 nodeMap 还被模板其它处复用，故只注入只读取标签的解析函数）
 */
export function useConstraintConflicts(
  getConstraints: () => Constraint[],
  getEdges: () => OntologyEdge[],
  getNodeLabel: (id: string) => string,
) {
  const constraintConflicts = computed<ConflictWarning[]>(() => {
    const constraints = getConstraints();
    if (constraints.length < 2) return [];
    const forced = new Set(constraints.filter(c => c.mode === 'force').map(c => c.nodeId));
    const blocked = new Set(constraints.filter(c => c.mode === 'block').map(c => c.nodeId));
    if (!forced.size || !blocked.size) return [];
    const warnings: ConflictWarning[] = [];
    for (const edge of (getEdges() || [])) {
      if (forced.has(edge.from) && blocked.has(edge.to)) {
        const fl = getNodeLabel(edge.from);
        const bl = getNodeLabel(edge.to);
        warnings.push({ message: `「${fl}」(必然) → 「${bl}」(禁止): 存在直接因果关系` });
      }
      if (blocked.has(edge.from) && forced.has(edge.to)) {
        const bl = getNodeLabel(edge.from);
        const fl = getNodeLabel(edge.to);
        warnings.push({ message: `「${bl}」(禁止) → 「${fl}」(必然): 禁止上游可能阻断必然节点` });
      }
    }
    return warnings;
  });

  return { constraintConflicts };
}
