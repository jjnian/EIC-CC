// 统一前端网络层 — 所有接口调用经过这里
// 非 2xx 抛出 ApiError;SSE 用 fetch + ReadableStream 解析

export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, message: string, body: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

async function parseError(res: Response): Promise<{ msg: string; body: unknown }> {
  const text = await res.text();
  if (!text) return { msg: `HTTP ${res.status}`, body: null };
  try {
    const json = JSON.parse(text);
    const msg = (json && typeof json === 'object' && 'error' in json)
      ? String((json as { error: unknown }).error)
      : `HTTP ${res.status}`;
    return { msg, body: json };
  } catch {
    return { msg: text || `HTTP ${res.status}`, body: text };
  }
}

/** 统一 JSON 请求。非 2xx 抛 ApiError;204 / 空响应返回 undefined。 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers || {});
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const res = await fetch(path, { ...init, headers });
  if (!res.ok) {
    const { msg, body } = await parseError(res);
    throw new ApiError(res.status, msg, body);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

export interface SseHandlers {
  onEvent: (event: string, data: string) => void;
  onError?: (err: Error) => void;
  onClose?: () => void;
}

export interface SseHandle {
  abort: () => void;
}

/**
 * POST 一份 JSON body,服务端返回 text/event-stream;按 SSE 行协议解析后回调 onEvent。
 * 调用方负责把 onEvent 的 data 字符串(可能是 JSON)再解一层。
 */
export function sse(path: string, body: unknown, handlers: SseHandlers): SseHandle {
  const ctrl = new AbortController();
  (async () => {
    try {
      const res = await fetch(path, {
        method: 'POST',
        headers: {
          'Accept': 'text/event-stream',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
      if (!res.ok || !res.body) {
        const { msg } = await parseError(res);
        handlers.onError?.(new ApiError(res.status, msg, null));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '');
          buffer = buffer.slice(idx + 1);
          if (line === '') { currentEvent = 'message'; continue; }
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            handlers.onEvent(currentEvent, data);
          }
        }
      }
      handlers.onClose?.();
    } catch (err) {
      if ((err as Error).name === 'AbortError') return;
      handlers.onError?.(err as Error);
    }
  })();
  return { abort: () => ctrl.abort() };
}
