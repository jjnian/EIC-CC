import { request } from './http';

export interface HypothesisTemplate {
  id: string;
  modelId: string;
  name: string;
  seeds: string[];
  steps: number;
  intent: string;
  constraints: { nodeId: string; mode: 'force' | 'block' }[];
  prompt: string;
  createdAt: number;
  lastUsedAt: number;
}

export function listTemplates(modelId?: string) {
  const q = modelId ? `?modelId=${encodeURIComponent(modelId)}` : '';
  return request<HypothesisTemplate[]>(`/api/hypothesis-templates${q}`);
}

export function saveTemplate(template: Partial<HypothesisTemplate>) {
  return request<HypothesisTemplate>('/api/hypothesis-templates', {
    method: 'POST',
    body: JSON.stringify(template),
  });
}

export function touchTemplate(id: string) {
  return request<{ success: boolean }>(`/api/hypothesis-templates/${encodeURIComponent(id)}/touch`, {
    method: 'POST',
  });
}

export function deleteTemplate(id: string) {
  return request<{ success: boolean }>(`/api/hypothesis-templates/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
