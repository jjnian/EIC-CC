import { request } from './http';

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
