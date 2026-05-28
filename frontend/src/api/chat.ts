import { request, sse, type SseHandle } from './http';

export interface ChatHistoryItem { role: 'user' | 'assistant'; content: string }
export interface ChatAttachment   { type: string; dataUrl?: string; [k: string]: unknown }

export interface ChatPayload {
  message: string;
  nodes?: unknown[];
  edges?: unknown[];
  modelOverride?: string;
  configId?: string;
  history?: ChatHistoryItem[];
  attachments?: ChatAttachment[];
}

export interface ChatQuestionOption {
  label: string;
  value?: string;
}

export interface ChatQuestion {
  text: string;
  options?: ChatQuestionOption[];
}

export interface ChatResult {
  reply: string;
  add_nodes?: unknown[];
  add_edges?: unknown[];
  question?: ChatQuestion;
}

export function chat(payload: ChatPayload) {
  return request<ChatResult>('/api/chat', { method: 'POST', body: JSON.stringify(payload) });
}

export interface BuildStep {
  key: string;
  label: string;
}

export interface ChatStreamHandlers {
  onText?: (chunk: string) => void;
  onStep?: (step: BuildStep) => void;
  onComplete?: (result: ChatResult) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

export function chatStream(payload: ChatPayload, handlers: ChatStreamHandlers): SseHandle {
  return sse('/api/chat/stream', payload, {
    onEvent: (event, data) => {
      switch (event) {
        case 'text':     handlers.onText?.(data); break;
        case 'step':
          try { handlers.onStep?.(JSON.parse(data) as BuildStep); }
          catch { /* ignore malformed step */ }
          break;
        case 'complete':
          try { handlers.onComplete?.(JSON.parse(data) as ChatResult); }
          catch { handlers.onError?.('完成事件 JSON 解析失败'); }
          break;
        case 'error':    handlers.onError?.(data); break;
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
