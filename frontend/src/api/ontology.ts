import { request } from './http';
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

export function extractFromFiles(files: File[], opts?: { modelOverride?: string; configId?: string }) {
  const fd = new FormData();
  for (const f of files) fd.append('files', f);
  if (opts?.modelOverride) fd.append('modelOverride', opts.modelOverride);
  if (opts?.configId) fd.append('configId', opts.configId);
  return request<ExtractResult>('/api/ontology-models/extract', { method: 'POST', body: fd });
}
