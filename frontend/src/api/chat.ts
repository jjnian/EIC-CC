import { request, sse, type SseHandle } from './http';

export interface ChatHistoryItem { role: 'user' | 'assistant'; content: string }
export interface ChatAttachment   { type: string; dataUrl?: string; [k: string]: unknown }

export interface ChatMentionRef {
  kind: 'graph' | 'node' | 'relation' | 'datasource' | 'experience';
  id: string;
  label: string;
}

export interface ChatPayload {
  message: string;
  nodes?: unknown[];
  edges?: unknown[];
  modelOverride?: string;
  configId?: string;
  history?: ChatHistoryItem[];
  attachments?: ChatAttachment[];
  /** 用户用 @ 引用的对象,后端据此做定向上下文(过滤数据源、聚焦子图等)。 */
  mentions?: ChatMentionRef[];
}

export interface ChatQuestionOption {
  label: string;
  value?: string;
}

export interface ChatQuestion {
  /** 简短主题标签(≤6 字),如「客户类型」「建模视角」。 */
  header?: string;
  text: string;
  /** true 时该问题可多选,需点「提交回答」后统一发送。 */
  multiSelect?: boolean;
  options?: ChatQuestionOption[];
}

export interface ChatResult {
  reply: string;
  add_nodes?: unknown[];
  add_edges?: unknown[];
  /** LLM 抛回的澄清问题(支持一次多个、单题多选)。 */
  questions?: ChatQuestion[];
  /** 旧格式:单个问题。新后端只发 questions,保留以兼容历史会话解析。 */
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
