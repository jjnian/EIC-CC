import { request } from './http';
import type { ModelConfig } from '../types';

export interface ProviderInfo {
  code: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  apiKeyEnvName: string;
}

/** ConfigResponse 中嵌入的 customModels 与全局 ModelConfig 同结构,直接复用。 */
export type ModelConfigInfo = ModelConfig;

export interface ConfigResponse {
  provider: string;
  baseUrl: string;
  modelName: string;
  providers: ProviderInfo[];
  customModels: ModelConfigInfo[];
}

export function getConfig() {
  return request<ConfigResponse>('/api/config');
}

export function saveConfig(payload: { provider: string; baseUrl: string; modelName: string; apiKey?: string }) {
  return request<{ success: true }>('/api/config', { method: 'POST', body: JSON.stringify(payload) });
}
