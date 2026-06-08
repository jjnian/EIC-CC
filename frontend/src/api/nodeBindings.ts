import { request } from './http';

/** 列→属性映射项。 */
export interface ColumnMapItem {
  column: string;
  attribute?: string;
}

/** 节点数据供血绑定：把图节点绑定到数据源的表/列，运行时取数供血。 */
export interface NodeBinding {
  id: string;
  modelId: string;
  nodeId: string;
  dataSourceId: string;
  tableName?: string;
  /** 列→属性映射（JSON 字符串，存库原样） */
  columnMap?: string;
  /** 可选只读 WHERE 片段（不含 where 关键字） */
  filterSql?: string;
  createdAt: number;
  updatedAt?: number;
}

export interface BindingFetchResult {
  columns: string[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
  dataSourceId: string;
  tableName: string;
}

export function listNodeBindings(modelId: string, nodeId?: string) {
  const qs = new URLSearchParams({ modelId });
  if (nodeId) qs.set('nodeId', nodeId);
  return request<NodeBinding[]>(`/api/node-bindings?${qs}`);
}

export function createNodeBinding(payload: {
  modelId: string; nodeId: string; dataSourceId: string;
  tableName?: string; columnMap?: string; filterSql?: string;
}) {
  return request<NodeBinding>('/api/node-bindings', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateNodeBinding(id: string, payload: {
  dataSourceId?: string; tableName?: string; columnMap?: string; filterSql?: string;
}) {
  return request<NodeBinding>(`/api/node-bindings/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function deleteNodeBinding(id: string) {
  return request<{ success: boolean }>(`/api/node-bindings/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

/** 按绑定取数供血。 */
export function fetchNodeBindingData(id: string, limit = 50) {
  return request<BindingFetchResult>(`/api/node-bindings/${encodeURIComponent(id)}/fetch`, {
    method: 'POST',
    body: JSON.stringify({ limit }),
  });
}
