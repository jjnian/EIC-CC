import { request } from './http';

export type DataSourceKind =
  | 'file' | 'url'                // 旧的导入历史
  | 'mysql' | 'pgsql' | 'file_stored' | 'https_api';

export interface DataSource {
  id: string;
  kind: DataSourceKind;
  name: string;
  mime?: string;
  size?: number;
  createdAt: number;
  status?: 'idle' | 'connected' | 'error';
  lastTestedAt?: number;
  lastError?: string;
  updatedAt?: number;
  /** 列表接口里返回的是 maskSensitive 后的 config；编辑表单请用 detail 端点 */
  config?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface DataSourceTestResult {
  success: boolean;
  message?: string;
  latencyMs?: number | null;
}

export interface TablePreview {
  columns: string[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
}

export interface SqlExecuteResult extends TablePreview {
  durationMs: number;
}

export interface HttpExecuteResult {
  success: boolean;
  statusCode?: number;
  headers?: Record<string, string>;
  body?: string;
  errorMsg?: string;
  durationMs: number;
  truncated: boolean;
}

export interface FetchLog {
  id: number;
  dataSourceId: string;
  fetchedAt: number;
  statusCode?: number;
  success: boolean;
  responseBody?: string;
  errorMsg?: string;
  durationMs: number;
}

export function listDataSources(opts?: { workspaceId?: string; kind?: DataSourceKind }) {
  const qs = new URLSearchParams();
  if (opts?.workspaceId) qs.set('workspaceId', opts.workspaceId);
  if (opts?.kind) qs.set('kind', opts.kind);
  const tail = qs.toString() ? `?${qs}` : '';
  return request<DataSource[]>(`/api/data-sources${tail}`);
}

export function getDataSource(id: string) {
  return request<DataSource>(`/api/data-sources/${encodeURIComponent(id)}`);
}

export function createDataSource(payload: { name: string; kind: DataSourceKind; config: Record<string, unknown> }) {
  return request<DataSource>('/api/data-sources', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateDataSource(id: string, payload: { name?: string; config?: Record<string, unknown> }) {
  return request<DataSource>(`/api/data-sources/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function deleteDataSource(id: string) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export function testDataSource(id: string) {
  return request<DataSourceTestResult>(`/api/data-sources/${encodeURIComponent(id)}/test`, {
    method: 'POST',
  });
}

export function testDataSourceInline(payload: { kind: DataSourceKind; config: Record<string, unknown> }) {
  return request<DataSourceTestResult>('/api/data-sources/test-inline', {
    method: 'POST',
    body: JSON.stringify({ name: '__inline__', ...payload }),
  });
}

// ---------- 数据库专用 ----------

export function listTables(id: string) {
  return request<string[]>(`/api/data-sources/${encodeURIComponent(id)}/tables`);
}

export function previewTable(id: string, name: string, limit = 50) {
  return request<TablePreview>(
    `/api/data-sources/${encodeURIComponent(id)}/tables/${encodeURIComponent(name)}/preview?limit=${limit}`,
  );
}

export function executeSql(id: string, sql: string, limit = 100) {
  return request<SqlExecuteResult>(`/api/data-sources/${encodeURIComponent(id)}/sql`, {
    method: 'POST',
    body: JSON.stringify({ sql, limit }),
  });
}

// ---------- 文件专用 ----------

export function uploadFileDataSource(file: File, name?: string) {
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);
  return request<DataSource>('/api/data-sources/file', {
    method: 'POST',
    body: form,
  });
}

export function readFileContent(id: string, offset = 0, length = 10000) {
  const qs = `?offset=${offset}&length=${length}`;
  return request<string>(`/api/data-sources/${encodeURIComponent(id)}/content${qs}`);
}

export function fileDownloadUrl(id: string) {
  return `/api/data-sources/${encodeURIComponent(id)}/download`;
}

// ---------- HTTPS 专用 ----------

export function executeHttp(id: string) {
  return request<HttpExecuteResult>(`/api/data-sources/${encodeURIComponent(id)}/execute`, {
    method: 'POST',
  });
}

export function listFetchLogs(id: string) {
  return request<FetchLog[]>(`/api/data-sources/${encodeURIComponent(id)}/logs`);
}

export function updateSchedule(id: string, enabled: boolean, intervalSec?: number) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}/schedule`, {
    method: 'PUT',
    body: JSON.stringify({ enabled, intervalSec }),
  });
}
