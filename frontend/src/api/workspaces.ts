import { request } from './http';

export interface Workspace {
  id: string;
  name: string;
  description?: string;
  isDefault?: boolean;
  sortNo?: number;
  createdAt?: number;
  updatedAt?: number;
}

export function listWorkspaces() {
  return request<Workspace[]>('/api/workspaces');
}

export function getWorkspace(id: string) {
  return request<Workspace>(`/api/workspaces/${encodeURIComponent(id)}`);
}

export function createWorkspace(input: { name: string; description?: string }) {
  return request<Workspace>('/api/workspaces', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateWorkspace(id: string, input: Partial<Workspace>) {
  return request<Workspace>(`/api/workspaces/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  });
}

export function deleteWorkspace(id: string) {
  return request<{ success: boolean }>(`/api/workspaces/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
