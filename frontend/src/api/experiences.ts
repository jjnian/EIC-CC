import { request, sse } from './http';
import type { SseHandle } from './http';

/** 上传文件建经验：抽取文本作正文、文件名作标题，后端自动建向量索引。 */
export function uploadExperienceFile(file: File, title?: string) {
  const form = new FormData();
  form.append('file', file);
  if (title) form.append('title', title);
  return request<Experience>('/api/experiences/file', {
    method: 'POST',
    body: form,
  });
}

/** 从数据库数据源导出 DDL 并存为一条经验（CREATE TABLE/VIEW 作正文，自动建索引）。 */
export function createExperienceFromDdl(dataSourceId: string) {
  return request<Experience>('/api/experiences/from-ddl', {
    method: 'POST',
    body: JSON.stringify({ dataSourceId }),
  });
}

export interface Experience {
  id: string;
  /** 所属工作空间 id（总览/跨工作空间时返回） */
  workspaceId?: string;
  title: string;
  content?: string;
  /** 逗号分隔的标签 */
  tags?: string;
  createdAt: number;
  updatedAt?: number;
  /** 向量索引状态：none | indexing | indexed | error */
  indexStatus?: 'none' | 'indexing' | 'indexed' | 'error';
}

export function listExperiences(opts?: { workspaceId?: string }) {
  const qs = new URLSearchParams();
  if (opts?.workspaceId) qs.set('workspaceId', opts.workspaceId);
  const tail = qs.toString() ? `?${qs}` : '';
  return request<Experience[]>(`/api/experiences${tail}`);
}

export function getExperience(id: string) {
  return request<Experience>(`/api/experiences/${encodeURIComponent(id)}`);
}

export function createExperience(payload: { title: string; content?: string; tags?: string }) {
  return request<Experience>('/api/experiences', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateExperience(id: string, payload: { title?: string; content?: string; tags?: string }) {
  return request<Experience>(`/api/experiences/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function deleteExperience(id: string) {
  return request<{ success: boolean }>(`/api/experiences/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

/** 手动触发重建该条经验的向量索引（embedding 配置变更后补建）。 */
export function reindexExperience(id: string) {
  return request<{ triggered: boolean; configured: boolean }>(
    `/api/experiences/${encodeURIComponent(id)}/reindex`,
    { method: 'POST' },
  );
}

export function getExperienceIndexStatus(id: string) {
  return request<{ status: string; chunkCount: number }>(
    `/api/experiences/${encodeURIComponent(id)}/index-status`,
  );
}

/**
 * 一键从「当前工作空间的整个经验库」构建本体血缘图（SSE 流）。
 * 这是新数据流的主入口：本体血缘图由经验库文件构建，数据源只负责供血。
 */
export function extractOntologyFromExperiences(
  body: { modelOverride?: string; configId?: string; hint?: string },
  handlers: {
    onStep?: (key: string, label: string) => void;
    onComplete?: (payload: {
      nodes: unknown[];
      edges: unknown[];
      reply: string;
      salt: string;
      sourceCount?: number;
    }) => void;
    onError?: (msg: string) => void;
    onClose?: () => void;
  },
): SseHandle {
  return sse('/api/experiences/extract-ontology', body, {
    onEvent: (event, data) => {
      if (event === 'step') {
        try {
          const j = JSON.parse(data) as { key: string; label: string };
          handlers.onStep?.(j.key, j.label);
        } catch { /* ignore */ }
      } else if (event === 'complete') {
        try {
          handlers.onComplete?.(JSON.parse(data));
        } catch (e) {
          handlers.onError?.((e as Error).message);
        }
      } else if (event === 'error') {
        handlers.onError?.(data);
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
