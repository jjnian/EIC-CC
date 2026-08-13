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

/** 运行时启停模型（内存生效，重启回落配置文件） */
export function updateModel(id: string, patch: { enabled: boolean }) {
  return request<ModelConfig>(`/api/models/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch),
  });
}
