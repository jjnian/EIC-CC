import type { OntologyEdge } from '../types';
import type { ContainmentCheckResult } from '../api/dataSources';

/** 值包含检验的目标四元组：child.col ⊆ parent.col。 */
export interface ContainmentTarget {
  childTable: string;
  childColumn: string;
  parentTable: string;
  parentColumn: string;
}

/**
 * 从边的 label / evidence 解析出可验证的 表.列 四元组。
 * 兼容 schema 抽取 Rule 3（"order_items.order_id → orders.id"）与
 * Rule 4（"a.col ≈ b.col (按命名推断…)" / "naming:col↔parent.id"）两种产出格式。
 * 解析不出（信息不足）返回 null——这类边只能在边详情里手动补全后验证。
 */
export function parseContainmentTarget(edge: OntologyEdge): ContainmentTarget | null {
  const pair =
    (edge.label || '').match(/([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)\s*[→≈~-]+\s*([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)/)
    || (edge.evidence || '').match(/([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)\s*[→≈↔~-]+\s*([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)/);
  if (pair) {
    return { childTable: pair[1], childColumn: pair[2], parentTable: pair[3], parentColumn: pair[4] };
  }
  const naming = (edge.evidence || '').match(/naming:([A-Za-z_][\w]*)\s*↔\s*([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)/);
  if (naming) {
    const childTable = (edge.derived_tables || [])[0];
    if (!childTable) return null;
    return { childTable, childColumn: naming[1], parentTable: naming[2], parentColumn: naming[3] };
  }
  return null;
}

/** 验证结论 → 写回边的置信度。 */
export function verdictConfidence(verdict: ContainmentCheckResult['verdict']): number {
  return verdict === 'confirmed' ? 0.95 : verdict === 'likely' ? 0.8 : 0.2;
}

/** 验证结果 → 写回边的证据文本（含匹配率与检验目标，可审计）。 */
export function verdictEvidence(r: ContainmentCheckResult): string {
  return `数据验证:匹配率${(r.matchRate * 100).toFixed(1)}% (${r.checkedRows - r.orphanRows}/${r.checkedRows}${r.sampled ? ',采样' : ''}) `
    + `${r.childTable}.${r.childColumn}→${r.parentTable}.${r.parentColumn}`;
}

/** 该边是否已带数据验证证据（避免重复验证）。 */
export function hasVerification(edge: OntologyEdge): boolean {
  return (edge.evidence || '').includes('数据验证:');
}
