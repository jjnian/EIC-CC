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
