import { request } from './http';

export type DataSourceKind =
  | 'file' | 'url'                // 旧的导入历史
  | 'mysql' | 'pgsql' | 'oracle' | 'dm' | 'gbase' | 'file_stored' | 'https_api';

export interface DataSource {
  id: string;
  /** 所属工作空间 id（总览页 all=true 时返回，用于标注归属/跨工作空间操作） */
  workspaceId?: string;
  kind: DataSourceKind;
  name: string;
  mime?: string;
  size?: number;
  createdAt: number;
  status?: 'idle' | 'connected' | 'error';
  lastTestedAt?: number;
  lastError?: string;
  updatedAt?: number;
  /** 所属文件夹 id（缺省 = 工作空间根，不在任何文件夹内） */
  folderId?: string;
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

/** 跨工作空间的全量数据源列表，每条带 workspaceId。用于「数据源」总览页。 */
export function listAllDataSources() {
  return request<DataSource[]>('/api/data-sources?all=true');
}

/**
 * 「数据源 id → 引用它的工作空间 id 列表」映射（跨工作空间）。
 * 公共数据源总览页据此展示每个数据源被哪些工作空间通过节点供血绑定引用。
 */
export function listDataSourceReferences() {
  return request<Record<string, string[]>>('/api/data-sources/references');
}

/** 当前工作空间「尚未引用」的公共数据源（引用选择器列出可引入的数据源）。 */
export function listReferencableDataSources() {
  return request<DataSource[]>('/api/data-sources/referencable');
}

/** 把一批公共数据源引用进当前工作空间（已引用的跳过）。 */
export function referenceDataSources(dataSourceIds: string[]) {
  return request<{ added: number }>('/api/data-sources/refs', {
    method: 'POST',
    body: JSON.stringify({ dataSourceIds }),
  });
}

/** 取消当前工作空间对某数据源的引用（不删除数据源本体）。 */
export function unreferenceDataSource(id: string) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}/ref`, {
    method: 'DELETE',
  });
}

/** 操作其它工作空间的数据源时，显式带上该数据源所属的 workspaceId 作为请求头，绕过当前上下文。 */
function wsHeader(workspaceId?: string): RequestInit {
  return workspaceId ? { headers: { 'X-Workspace-Id': workspaceId } } : {};
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

export function deleteDataSource(id: string, workspaceId?: string) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    ...wsHeader(workspaceId),
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

// ---------- Schema 内省 & 一键提取本体血缘图 ----------

export interface ColumnSchema {
  name: string;
  dataType: string;
  nullable: boolean;
  defaultValue?: string;
  comment?: string;
  primaryKey: boolean;
}

export interface ForeignKeySchema {
  constraintName: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
}

export interface UniqueKeySchema {
  name: string;
  columns: string[];
}

export interface TableSchema {
  name: string;
  comment?: string;
  estimatedRows?: number;
  columns: ColumnSchema[];
  foreignKeys: ForeignKeySchema[];
  uniqueKeys: UniqueKeySchema[];
}

export interface DatabaseSchema {
  kind: 'mysql' | 'pgsql' | 'oracle' | 'dm' | 'gbase';
  database: string;
  tables: TableSchema[];
}

/** 内省整库 schema：表 + 列 + 外键 + 唯一键。前端可直接展示血缘预览。 */
export function getDatabaseSchema(id: string) {
  return request<DatabaseSchema>(`/api/data-sources/${encodeURIComponent(id)}/schema`);
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
