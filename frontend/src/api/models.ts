import { request } from './http';

export interface ModelConfig {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string;
  enabled: boolean;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
  createdAt?: number;
  updatedAt?: number;
}

export interface ModelConfigPayload {
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
}

export function listModels() {
  return request<ModelConfig[]>('/api/models');
}

export function createModel(payload: ModelConfigPayload) {
  return request<ModelConfig>('/api/models', { method: 'POST', body: JSON.stringify(payload) });
}

export function updateModel(id: string, payload: ModelConfigPayload) {
  return request<ModelConfig>(`/api/models/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) });
}

export function deleteModel(id: string) {
  return request<{ success: boolean; count: number }>(`/api/models/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function toggleModel(id: string) {
  return request<{ success: true }>(`/api/models/${encodeURIComponent(id)}/toggle`, { method: 'PATCH' });
}
