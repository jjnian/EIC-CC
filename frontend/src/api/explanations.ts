// P1-7 / P1-8：推演节点解释与原始 prompt 接口
import { request, sse, type SseHandle } from './http';
import type { NodeExplanation } from '../types';

/** P1-8：取该分支推演时使用的完整 prompt 文本快照。 */
export function getRawPrompt(scenarioId: string) {
  return request<{ rawPrompt: string }>(`/api/scenarios/${encodeURIComponent(scenarioId)}/raw-prompt`);
}

export interface ExplainPayload {
  nodeId: string;
  modelOverride?: string;
  configId?: string;
  forceRegenerate?: boolean;
}

export interface ExplainHandlers {
  /** 每段解释（evidence / assumptions / counterexamples）的内容到达。 */
  onChunk?: (field: 'evidence' | 'assumptions' | 'counterexamples', text: string) => void;
  /** 全部完成。cached=true 表示来自缓存，没有调 LLM。 */
  onComplete?: (explanation: NodeExplanation, cached: boolean) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

/** P1-7：对推演节点请求三段式解释（依据/假设/反例），SSE 流式返回。 */
export function explainStream(scenarioId: string, payload: ExplainPayload, handlers: ExplainHandlers): SseHandle {
  return sse(`/api/scenarios/${encodeURIComponent(scenarioId)}/explain`, payload, {
    onEvent: (event, data) => {
      try {
        switch (event) {
          case 'chunk': {
            const { field, text } = JSON.parse(data) as { field: ExplainHandlers extends never ? never : 'evidence' | 'assumptions' | 'counterexamples'; text: string };
            handlers.onChunk?.(field, text);
            break;
          }
          case 'complete': {
            const parsed = JSON.parse(data) as { explanation: NodeExplanation; cached?: boolean };
            handlers.onComplete?.(parsed.explanation, !!parsed.cached);
            break;
          }
          case 'error':
            handlers.onError?.(data);
            break;
        }
      } catch (err) {
        handlers.onError?.(`解释 SSE 数据解析失败: ${(err as Error).message}`);
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
