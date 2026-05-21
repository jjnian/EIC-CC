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
