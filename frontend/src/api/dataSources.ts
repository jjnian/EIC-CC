import { request, sse, type SseHandle } from './http';

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
  /** 音频数据源的转写正文（抽取流程 ASR 后落 extra_json，可能已按上限截断） */
  transcript?: string;
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

/** 血缘值包含检验结果。verdict: confirmed(≥99.5%) / likely(≥90%) / rejected / empty。 */
export interface ContainmentCheckResult {
  childTable: string;
  childColumn: string;
  parentTable: string;
  parentColumn: string;
  checkedRows: number;
  orphanRows: number;
  matchRate: number;
  sampled: boolean;
  verdict: 'confirmed' | 'likely' | 'rejected' | 'empty';
  durationMs: number;
}

/** 血缘值包含检验：验证 child.col 的值是否都能在 parent.col 中找到（推断血缘边的数据证据）。 */
export function verifyContainment(id: string, body: {
  childTable: string; childColumn: string;
  parentTable: string; parentColumn: string;
  sampleLimit?: number;
}) {
  return request<ContainmentCheckResult>(`/api/data-sources/${encodeURIComponent(id)}/verify-containment`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function getDataSource(id: string) {
  return request<DataSource>(`/api/data-sources/${encodeURIComponent(id)}`);
}

/** 确定性结构建图结果摘要（表/视图/列/外键/节点/边计数 + 新模型 id）。 */
export interface StructuralBuildResult {
  modelId: string;
  title: string;
  database: string;
  tableCount: number;
  viewCount: number;
  columnCount: number;
  fkCount: number;
  nodeCount: number;
  edgeCount: number;
}

/**
 * 从关系型数据源全库内省，确定性构建结构血缘图（绕过 LLM，面向千张/万张表），落成一个新本体模型。
 * 大库内省 + 落库可能耗时较长（同步；大库建议用 {@link buildStructuralGraphStream} 流式版）。
 */
export function buildStructuralGraph(id: string, title?: string) {
  return request<StructuralBuildResult>(`/api/data-sources/${encodeURIComponent(id)}/build-structural-graph`, {
    method: 'POST',
    body: JSON.stringify(title ? { title } : {}),
  });
}

/** 结构建图（SSE 流式）：流式回进度，避免万张表大库同步请求超时。 */
export function buildStructuralGraphStream(
  id: string,
  handlers: {
    onStep?: (key: string, label: string) => void;
    onComplete?: (r: StructuralBuildResult) => void;
    onError?: (msg: string) => void;
    onClose?: () => void;
  },
  title?: string,
): SseHandle {
  return sse(`/api/data-sources/${encodeURIComponent(id)}/build-structural-graph/stream`, title ? { title } : {}, {
    onEvent: (event, data) => {
      if (event === 'step') {
        try { const j = JSON.parse(data) as { key: string; label: string }; handlers.onStep?.(j.key, j.label); }
        catch { /* ignore */ }
      } else if (event === 'complete') {
        try { handlers.onComplete?.(JSON.parse(data)); }
        catch (e) { handlers.onError?.((e as Error).message); }
      } else if (event === 'error') {
        handlers.onError?.(data);
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
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
