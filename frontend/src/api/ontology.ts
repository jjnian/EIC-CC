import { request, sseForm, type SseHandle } from './http';
import type { OntologyModel, OntologyNode, OntologyEdge, SourceMeta } from '../types';

export function listOntologies() {
  return request<OntologyModel[]>('/api/ontology-models');
}

export function getOntology(id: string) {
  return request<OntologyModel>(`/api/ontology-models/${encodeURIComponent(id)}`);
}

export function saveOntology(model: OntologyModel) {
  return request<OntologyModel>('/api/ontology-models', {
    method: 'POST',
    body: JSON.stringify(model),
  });
}

export function updateOntology(id: string, model: OntologyModel) {
  return request<OntologyModel>(`/api/ontology-models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(model),
  });
}

export function deleteOntology(id: string) {
  return request<{ success: boolean; count: number }>(
    `/api/ontology-models/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

// ── 大图服务端查询层（面向千张/万张表：不整图渲染，按需取子图/领域） ──
export interface ModelSummary { nodeCount: number; edgeCount: number; domainCount: number; }
export interface ModelSubgraph { nodes: OntologyNode[]; edges: OntologyEdge[]; truncated: boolean; seed: string; }
export interface DomainRollup {
  domains: { domain: string; nodeCount: number }[];
  edges: { from: string; to: string; count: number }[];
}

/** 模型规模摘要（不加载整图），供前端判定是否进「大图模式」。 */
export function getModelSummary(id: string) {
  return request<ModelSummary>(`/api/ontology-models/${encodeURIComponent(id)}/summary`);
}

/** 聚焦子图：某节点 N 跳邻域（node 为空则取度数最高的入口枢纽）。 */
export function getModelSubgraph(id: string, opts?: { node?: string; depth?: number; dir?: 'up' | 'down' | 'both'; limit?: number }) {
  const qs = new URLSearchParams();
  if (opts?.node) qs.set('node', opts.node);
  if (opts?.depth != null) qs.set('depth', String(opts.depth));
  if (opts?.dir) qs.set('dir', opts.dir);
  if (opts?.limit != null) qs.set('limit', String(opts.limit));
  const q = qs.toString();
  return request<ModelSubgraph>(`/api/ontology-models/${encodeURIComponent(id)}/subgraph${q ? '?' + q : ''}`);
}

/** 领域汇总：节点按 domain 聚成超级节点 + 跨领域流向计数。 */
export function getModelDomains(id: string) {
  return request<DomainRollup>(`/api/ontology-models/${encodeURIComponent(id)}/domains`);
}

/** 外科手术式局部编辑结果。 */
export interface PatchResult { applied: number; skipped: number; nodeCount: number; edgeCount: number; }
/** 一条局部操作：增/删/改 单节点或单边。 */
export type GraphPatchOp = Record<string, unknown>;

/** 对已落库的大模型只改动指定节点/边（不加载/不重存整图）。 */
export function patchModel(id: string, ops: GraphPatchOp[]) {
  return request<PatchResult>(`/api/ontology-models/${encodeURIComponent(id)}/patch`, {
    method: 'POST',
    body: JSON.stringify({ ops }),
  });
}

/** 对话驱动改图结果。 */
export interface GraphEditResult {
  reply: string;
  applied: number;
  skipped: number;
  ops: GraphPatchOp[];
  nodeCount: number;
  edgeCount: number;
}

/** 对话驱动精准改图：自然语言 → 检索相关子图 → LLM 产 patch → 局部应用（大图也能改）。 */
export function chatEditModel(id: string, message: string, opts?: { modelOverride?: string; configId?: string }) {
  return request<GraphEditResult>(`/api/ontology-models/${encodeURIComponent(id)}/chat-edit`, {
    method: 'POST',
    body: JSON.stringify({ message, ...(opts || {}) }),
  });
}

export interface ExtractResult {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  reply: string;
  sources: SourceMeta[];
  salt: string;
}

export function extractFromFiles(
  files: File[],
  opts?: { urls?: string[]; modelOverride?: string; configId?: string; signal?: AbortSignal }
) {
  const fd = new FormData();
  for (const f of files) fd.append('files', f);
  if (opts?.urls?.length) {
    for (const u of opts.urls) {
      const trimmed = u.trim();
      if (trimmed) fd.append('urls', trimmed);
    }
  }
  if (opts?.modelOverride) fd.append('modelOverride', opts.modelOverride);
  if (opts?.configId) fd.append('configId', opts.configId);
  return request<ExtractResult>('/api/ontology-models/extract', { method: 'POST', body: fd, signal: opts?.signal });
}

export interface ExtractStep {
  key: string;
  label: string;
}

export interface ExtractStreamHandlers {
  onStep?: (step: ExtractStep) => void;
  onComplete?: (result: ExtractResult) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

/**
 * 流式抽取本体：服务端按阶段推送 step 事件,完成时 complete 事件携带结果。
 * 返回 SseHandle,调用方可 abort() 中断。
 */
export function extractFromFilesStream(
  files: File[],
  opts: { urls?: string[]; modelOverride?: string; configId?: string },
  handlers: ExtractStreamHandlers
): SseHandle {
  const fd = new FormData();
  for (const f of files) fd.append('files', f);
  if (opts?.urls?.length) {
    for (const u of opts.urls) {
      const trimmed = u.trim();
      if (trimmed) fd.append('urls', trimmed);
    }
  }
  if (opts?.modelOverride) fd.append('modelOverride', opts.modelOverride);
  if (opts?.configId) fd.append('configId', opts.configId);
  return sseForm('/api/ontology-models/extract/stream', fd, {
    onEvent: (event, data) => {
      switch (event) {
        case 'step':
          try { handlers.onStep?.(JSON.parse(data) as ExtractStep); }
          catch { /* 忽略坏帧 */ }
          break;
        case 'complete':
          try { handlers.onComplete?.(JSON.parse(data) as ExtractResult); }
          catch { handlers.onError?.('解析抽取结果失败'); }
          break;
        case 'error':
          handlers.onError?.(data);
          break;
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}

// 版本历史相关 API
export function listVersions(modelId: string) {
  return request<{ timestamp: number; nodeCount: number; edgeCount: number; fileSize: number }[]>(
    `/api/ontology-models/${modelId}/versions`
  );
}

export function restoreVersion(modelId: string, timestamp: number) {
  return request<any>(`/api/ontology-models/${modelId}/versions/${timestamp}/restore`, { method: 'POST' });
}

// 图谱模板库 API
export function listGraphTemplates() {
  return request<any[]>('/api/templates');
}

export function saveGraphTemplate(template: any) {
  return request<any>('/api/templates', {
    method: 'POST',
    body: JSON.stringify(template),
  });
}

export function deleteGraphTemplate(id: string) {
  return request<void>(`/api/templates/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** 回写建图来源记录：经验库建图结果合并进模型后调用，供下次增量建图跳过未变更经验。 */
export function recordBuildSources(modelId: string, sources: { experienceId: string; contentHash: string }[]) {
  return request<{ recorded: number }>(`/api/ontology-models/${encodeURIComponent(modelId)}/build-sources`, {
    method: 'POST',
    body: JSON.stringify({ sources }),
  });
}

/** Schema 漂移检测结果。 */
export interface SchemaDriftResult {
  database: string;
  schemaTables: number;
  referencedTables: number;
  okTables: number;
  missingTables: { table: string; nodes: string[]; edges: string[] }[];
  missingColumns: { table: string; column: string; nodes: string[] }[];
}

/** Schema 漂移检测：比对图上表/列引用与数据源最新 schema，报告失效引用。 */
export function detectSchemaDrift(modelId: string, dataSourceId: string) {
  return request<SchemaDriftResult>(`/api/ontology-models/${encodeURIComponent(modelId)}/schema-drift`, {
    method: 'POST',
    body: JSON.stringify({ dataSourceId }),
  });
}
