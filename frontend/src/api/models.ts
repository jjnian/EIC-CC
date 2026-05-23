import { request } from './http';
import type { ModelConfig } from '../types';

export type { ModelConfig };

export function listModels() {
  return request<ModelConfig[]>('/api/models');
}

export interface TestModelResult {
  status: 'ok' | 'error';
  latencyMs?: number;
  error?: string;
}

export function testModel(id: string): Promise<TestModelResult> {
  return request<TestModelResult>(`/api/models/${id}/test`, { method: 'POST' });
}
