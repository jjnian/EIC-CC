import { request } from './http';

export interface ProviderInfo {
  code: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  apiKeyEnvName: string;
}

export interface ModelConfigInfo {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  enabled: boolean;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
}

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
