import { request, sse, type SseHandle } from './http';

export interface IndexStatus {
  status: 'none' | 'indexing' | 'indexed' | 'error';
  chunkCount: number;
}

export interface IndexStep {
  key: string;
  label: string;
}

export interface IndexStreamHandlers {
  onStep?: (step: IndexStep) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

export function indexDataSource(dataSourceId: string, handlers: IndexStreamHandlers): SseHandle {
  return sse(`/api/index/${dataSourceId}`, {}, {
    onEvent: (event, data) => {
      switch (event) {
        case 'step':
          try { handlers.onStep?.(JSON.parse(data) as IndexStep); }
          catch { /* ignore */ }
          break;
        case 'error': handlers.onError?.(data); break;
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}

export function getIndexStatus(dataSourceId: string): Promise<IndexStatus> {
  return request<IndexStatus>(`/api/index/${dataSourceId}/status`);
}

export function deleteIndex(dataSourceId: string): Promise<void> {
  return request<void>(`/api/index/${dataSourceId}`, { method: 'DELETE' });
}

export function isEmbeddingConfigured(): Promise<{ configured: boolean }> {
  return request<{ configured: boolean }>('/api/index/configured');
}
