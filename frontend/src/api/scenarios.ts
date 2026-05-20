import { request, sse, type SseHandle } from './http';
import type { Scenario, Constraint } from '../types';

export function listScenarios(modelId?: string) {
  const q = modelId ? `?modelId=${encodeURIComponent(modelId)}` : '';
  return request<Scenario[]>(`/api/scenarios${q}`);
}

export function getScenario(id: string) {
  return request<Scenario>(`/api/scenarios/${encodeURIComponent(id)}`);
}

export function deleteScenario(id: string) {
  return request<{ success: boolean; count: number }>(
    `/api/scenarios/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function migrateScenarios() {
  return request<Record<string, number>>('/api/scenarios/migrate', { method: 'POST' });
}

export interface PredictPayload {
  modelId: string;
  parentBranchId?: string;
  name?: string;
  intent?: 'forward' | 'backward';
  seeds: string[];
  steps: number;
  prompt?: string;
  nodes: unknown[];
  edges: unknown[];
  modelOverride?: string;
  configId?: string;
  constraints?: Constraint[];
}

export interface PredictHandlers {
  onStep?: (data: unknown) => void;
  onNotice?: (data: unknown) => void;
  onComplete?: (data: Scenario) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

export function predictStream(payload: PredictPayload, handlers: PredictHandlers): SseHandle {
  return sse('/api/scenarios', payload, {
    onEvent: (event, data) => {
      try {
        switch (event) {
          case 'step':     handlers.onStep?.(JSON.parse(data)); break;
          case 'notice':   handlers.onNotice?.(JSON.parse(data)); break;
          case 'complete': handlers.onComplete?.(JSON.parse(data) as Scenario); break;
          case 'error':    handlers.onError?.(data); break;
        }
      } catch (err) {
        handlers.onError?.(`SSE 数据解析失败: ${(err as Error).message}`);
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
