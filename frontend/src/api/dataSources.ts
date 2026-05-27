import { request } from './http';

export interface DataSource {
  id: string;
  kind: 'file' | 'url';
  name: string;
  mime?: string;
  size?: number;
  createdAt: number;
  /** 透传自后端 extra_json 的剩余字段（pages / chars / title / truncated 等） */
  [key: string]: unknown;
}

export function listDataSources() {
  return request<DataSource[]>('/api/data-sources');
}

export function deleteDataSource(id: string) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
