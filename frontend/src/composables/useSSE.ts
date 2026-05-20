// Minimal fetch-based SSE client with proper multi-line / framing semantics.
//
// Frames are separated by a blank line. Within a frame, multiple `data:` lines
// are concatenated with '\n'. The `event:` field persists across `data:` lines
// inside the SAME frame and is reset only at the frame boundary (blank line).
//
//   const ctrl = streamSSE('/api/foo', body, {
//     onEvent: (name, data) => { ... },
//     onError: (err) => { ... },
//     onComplete: () => { ... },
//   });
//   // later:
//   ctrl.abort();

export interface SSEHandlers {
  onEvent?(name: string, data: string): void;
  onError?(err: Error): void;
  onComplete?(): void;
}

export interface SSEController {
  abort(): void;
}

export function streamSSE(url: string, body: any, handlers: SSEHandlers): SSEController {
  const ctrl = new AbortController();
  let aborted = false;

  const run = async () => {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });

      if (!res.ok) {
        let errBody = '';
        try { errBody = await res.text(); } catch {}
        throw new Error(errBody || ('HTTP ' + res.status));
      }
      if (!res.body) throw new Error('no response body');

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      let curEvent = 'message';
      let curData: string[] = [];

      const flushFrame = () => {
        if (curData.length === 0 && curEvent === 'message') {
          curEvent = 'message';
          return;
        }
        const data = curData.join('\n');
        if (curData.length || curEvent !== 'message') {
          handlers.onEvent?.(curEvent, data);
        }
        curEvent = 'message';
        curData = [];
      };

      const processLine = (raw: string) => {
        // Trim trailing CR (CRLF handling).
        const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
        if (line === '') { flushFrame(); return; }
        if (line.startsWith(':')) return; // comment line
        if (line.startsWith('event:')) {
          curEvent = line.slice(6).replace(/^\s/, '').trim() || 'message';
        } else if (line.startsWith('data:')) {
          curData.push(line.slice(5).replace(/^\s/, ''));
        }
        // id: / retry: ignored
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        // Split off every complete line.
        while ((idx = buffer.indexOf('\n')) !== -1) {
          const line = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 1);
          processLine(line);
        }
      }
      // Flush trailing buffer (one last partial line without newline).
      if (buffer.length) {
        processLine(buffer);
        buffer = '';
      }
      // Flush any pending frame at EOF.
      flushFrame();

      if (!aborted) handlers.onComplete?.();
    } catch (err: any) {
      if (aborted || err?.name === 'AbortError') {
        // silent on intentional abort
        return;
      }
      handlers.onError?.(err instanceof Error ? err : new Error(String(err)));
    }
  };

  run();

  return {
    abort: () => {
      if (aborted) return;
      aborted = true;
      try { ctrl.abort(); } catch {}
    },
  };
}
