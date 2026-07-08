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
  /** 态势层·状态查询：只读 SQL，首行首列作为节点状态值 */
  statusQuery?: string;
  /** 态势层·阈值规则（JSON 字符串）：[{level,op,value}]，首个命中生效 */
  statusRules?: string;
  /** 态势层·是否纳入定时刷新 */
  statusEnabled?: boolean;
  /** 态势层·刷新间隔（秒，最小 60） */
  statusIntervalSec?: number;
  createdAt: number;
  updatedAt?: number;
}

/** 节点当前状态（态势层）。 */
export interface NodeState {
  bindingId: string;
  nodeId: string;
  value?: string | null;
  level?: 'normal' | 'warn' | 'alert' | 'error' | string;
  message?: string;
  updatedAt: number;
}

/** 阈值规则项。 */
export interface StatusRule {
  level: 'warn' | 'alert' | string;
  op: 'gt' | 'gte' | 'lt' | 'lte' | 'eq' | 'ne' | 'contains' | string;
  value: string;
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

// ---------- 态势层 ----------

/** 配置绑定的状态源（状态查询 SQL + 阈值规则 + 定时刷新）。 */
export function updateBindingStatusConfig(id: string, payload: {
  statusQuery?: string; statusRules?: string; statusEnabled?: boolean; statusIntervalSec?: number;
}) {
  return request<NodeBinding>(`/api/node-bindings/${encodeURIComponent(id)}/status-config`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

/** 手动刷新一次状态，返回最新状态。 */
export function refreshBindingStatus(id: string) {
  return request<NodeState>(`/api/node-bindings/${encodeURIComponent(id)}/status/refresh`, {
    method: 'POST',
  });
}

/** 某模型下全部节点的当前状态（态势画布轮询用）。 */
export function listNodeStates(modelId: string) {
  return request<NodeState[]>(`/api/node-bindings/states?modelId=${encodeURIComponent(modelId)}`);
}

/** 某绑定的状态历史（新→旧）。 */
export function bindingStatusHistory(id: string, limit = 100) {
  return request<{ value?: string; level?: string; collectedAt: number }[]>(
    `/api/node-bindings/${encodeURIComponent(id)}/status/history?limit=${limit}`);
}
