// 对外的 SSE 客户端封装。
// 内部委托 api/http.ts 的 sse(),保持原有 SSEHandlers / SSEController 接口给现有调用方。
import { sse, type SseHandle } from '../api/http';

export interface SSEHandlers {
  onEvent?(name: string, data: string): void;
  onError?(err: Error): void;
  onComplete?(): void;
}

export interface SSEController {
  abort(): void;
}

export function streamSSE(url: string, body: any, handlers: SSEHandlers): SSEController {
  let aborted = false;
  const handle: SseHandle = sse(url, body, {
    onEvent: (name, data) => { handlers.onEvent?.(name, data); },
    onError: (err) => {
      if (aborted) return;
      handlers.onError?.(err);
    },
    onClose: () => {
      if (aborted) return;
      handlers.onComplete?.();
    },
  });
  return {
    abort: () => {
      if (aborted) return;
      aborted = true;
      handle.abort();
    },
  };
}
