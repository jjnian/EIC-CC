import { request } from './http';
import type { ModelConfig } from '../types';

export type { ModelConfig };

export function listModels() {
  return request<ModelConfig[]>('/api/models');
}
