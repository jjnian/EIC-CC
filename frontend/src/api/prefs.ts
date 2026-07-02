import { request } from './http';

export interface Prefs {
  showEdgeLabels: boolean;
  autoFit: boolean;
  graphFontSize: number;
  defaultModelConfigId: string;
  [k: string]: unknown;
}

export function getPrefs() {
  return request<Prefs>('/api/prefs');
}

export function savePrefs(patch: Partial<Prefs>) {
  return request<Prefs>('/api/prefs', { method: 'PUT', body: JSON.stringify(patch) });
}

