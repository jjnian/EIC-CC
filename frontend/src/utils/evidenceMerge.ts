import type { OntologyEdge } from '../types';

/**
 * 多源证据聚合（方向：同一条边被多个来源各说一遍 = 互相佐证，比孤证更可信）。
 * 与后端 ExtractionGraphMerger.aggregateDuplicateEdge 同一套规则，前后端口径一致：
 * - 证据去重合并进 evidences（上限 6 条），evidence 保留首条作兼容展示；
 * - 置信度取较高者；独立证据 >1 条时每条 +0.1、封顶 0.9（确定性 1.0 不动）；
 * - 来源取更强者：derived > manual > preset > inferred。
 */

const MAX_EVIDENCES = 6;
const CORROBORATED_CAP = 0.9;
const CORROBORATION_STEP = 0.1;

const norm = (s: string) => s.trim().toLowerCase().replace(/\s+/g, ' ');

const sourceRank = (s?: string) =>
  s === 'derived' ? 3 : s === 'manual' ? 2 : s === 'preset' ? 1 : 0;

/** 收集一条边的全部证据文本（evidence 单值 + evidences 列表）。 */
function collectEvidences(e: Partial<OntologyEdge>, out: string[], seen: Set<string>) {
  const cands = [e.evidence || '', ...(e.evidences || [])];
  for (const c of cands) {
    const v = (c || '').trim();
    if (!v) continue;
    const k = norm(v);
    if (!seen.has(k)) { seen.add(k); out.push(v); }
  }
}

/**
 * 把重复边 dup 的证据/置信度/来源聚合进已存在的边 kept，返回聚合后的字段 patch
 * （不修改入参）；dup 没带来任何新信息时返回 null。
 */
export function mergeDuplicateEdgeEvidence(
  kept: OntologyEdge,
  dup: Partial<OntologyEdge>,
): Partial<OntologyEdge> | null {
  const evidences: string[] = [];
  const seen = new Set<string>();
  collectEvidences(kept, evidences, seen);
  const before = evidences.length;
  collectEvidences(dup, evidences, seen);
  const gained = evidences.length > before;

  const kc = kept.confidence ?? 0;
  const dc = dup.confidence ?? 0;
  const max = Math.max(kc, dc);
  let confidence: number | undefined;
  if (max < 1.0 && evidences.length > 1) {
    confidence = Math.min(CORROBORATED_CAP, max + CORROBORATION_STEP * (evidences.length - 1));
    confidence = Math.round(confidence * 100) / 100;
  } else if (dc > kc) {
    confidence = dc;
  }

  const strongerSource = sourceRank(dup.source) > sourceRank(kept.source) ? dup.source : undefined;

  if (!gained && confidence === undefined && !strongerSource) return null;

  const patch: Partial<OntologyEdge> = {};
  if (evidences.length > 1) {
    patch.evidences = evidences.slice(0, MAX_EVIDENCES);
    patch.evidence = evidences[0];
  }
  if (confidence !== undefined) patch.confidence = confidence;
  if (strongerSource) patch.source = strongerSource;
  return patch;
}
