// 系统级接口：健康检查与 LLM 用量统计
import { request } from './http';

/** LLM 单模型统计 */
export interface LlmModelStat {
  model: string;
  calls: number;
  errors: number;
}

/** LLM 用量统计（与后端 LlmMetricsService.getStats 对齐） */
export interface LlmMetrics {
  totalCalls: number;
  totalErrors: number;
  avgLatencyMs: number;
  byModel: LlmModelStat[];
}

export function getLlmMetrics() {
  return request<LlmMetrics>('/api/system/metrics/llm');
}

export function resetLlmMetrics() {
  return request<{ success?: boolean }>('/api/system/metrics/llm/reset', { method: 'POST' });
}
