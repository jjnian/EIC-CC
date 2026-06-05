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
