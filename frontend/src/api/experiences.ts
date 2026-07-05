import { request, sse } from './http';
import type { SseHandle } from './http';

/** 接入 web 系统的连接配置。后端回传时 password 已遮蔽为 ********，storageState 不回传原文。 */
export interface WebSystemConnection {
  baseUrl: string;
  username?: string;
  /** 回传时为遮蔽串 ********（已设置）或空（未设置）；提交时留空/遮蔽串表示不修改。 */
  password?: string;
  maxSteps?: number;
  readOnly?: boolean;
  /** 提交时可粘贴 storageState JSON；回传时不含原文。 */
  storageState?: string;
  /** 后端回传：是否已配置 storageState 预登录态。 */
  hasStorageState?: boolean;
}

export interface WebSystemPayload {
  title?: string;
  baseUrl: string;
  username?: string;
  password?: string;
  maxSteps?: number;
  readOnly?: boolean;
  storageState?: string;
}

/** 接入一个 web 系统并保存为经验条目（origin=websystem），不立即探索。 */
export function createWebSystem(payload: WebSystemPayload) {
  return request<Experience>('/api/experiences/websystem', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** 编辑已接入 web 系统的连接配置（密码/ storageState 留空表示保留原值）。 */
export function updateWebSystem(id: string, payload: WebSystemPayload) {
  return request<Experience>(`/api/experiences/websystem/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

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

/**
 * 把数据源抽取成一条经验并自动建索引：关系型库导 DDL（CREATE TABLE/VIEW），HTTPS 接口导请求配置 + 最近响应样例。
 * @param sampleRows >0 时（仅库类）为每张基表附带前 N 行样例数据（INSERT 形式）；默认 0 仅导结构。
 */
export function createExperienceFromDdl(dataSourceId: string, sampleRows = 0) {
  return request<Experience>('/api/experiences/from-ddl', {
    method: 'POST',
    body: JSON.stringify(sampleRows > 0 ? { dataSourceId, sampleRows } : { dataSourceId }),
  });
}

export interface Experience {
  id: string;
  /** 所属工作空间 id（总览/跨工作空间时返回） */
  workspaceId?: string;
  /** 所属文件夹 id；缺省 = 工作空间根目录（不在任何文件夹内） */
  folderId?: string;
  title: string;
  content?: string;
  /** 逗号分隔的标签 */
  tags?: string;
  createdAt: number;
  updatedAt?: number;
  /** 向量索引状态：none | indexing | indexed | error */
  indexStatus?: 'none' | 'indexing' | 'indexed' | 'error';
  /** 来源：manual（手写）| upload（上传文件）| ddl（数据源结构导出供血）| websystem（接入的 web 系统）| explore（自动探索系统产物） */
  origin?: 'manual' | 'upload' | 'ddl' | 'websystem' | 'explore';
  /** DDL 抽取经验的来源数据源 id（origin=ddl 时返回），前端据此实时解析「来自哪个数据库」。 */
  sourceDataSourceId?: string;
  /** 接入 web 系统的连接配置（origin=websystem 时返回，密码已遮蔽、storageState 仅返回是否已配置）。 */
  connection?: WebSystemConnection;
  /** 上传文件的原始文件名（origin=upload） */
  fileName?: string;
  /** 上传文件的 MIME 类型 */
  fileMime?: string;
  /** 上传文件的字节大小 */
  fileSize?: number;
  /** 是否有可预览/下载的归档原件 */
  hasFile?: boolean;
}

/** 经验原始上传文件的预览/下载 URL（带工作空间查询参数，供 iframe/img/下载直接使用）。 */
export function experienceFileUrl(id: string, opts?: { download?: boolean; wsId?: string }) {
  const qs = new URLSearchParams();
  if (opts?.download) qs.set('download', 'true');
  if (opts?.wsId) qs.set('wsId', opts.wsId);
  const tail = qs.toString() ? `?${qs}` : '';
  return `/api/experiences/${encodeURIComponent(id)}/file${tail}`;
}

export function listExperiences(opts?: { workspaceId?: string }) {
  const qs = new URLSearchParams();
  if (opts?.workspaceId) qs.set('workspaceId', opts.workspaceId);
  const tail = qs.toString() ? `?${qs}` : '';
  return request<Experience[]>(`/api/experiences${tail}`);
}

/** 跨工作空间的全量经验列表，每条带 workspaceId（归属工作空间）。用于公共「经验库」总览页。 */
export function listAllExperiences() {
  return request<Experience[]>('/api/experiences?all=true');
}

export interface ExperiencePage { items: Experience[]; total: number; page: number; size: number; }

/** 分页版全量经验列表（经验库达千/万篇时用）。workspaceId 非空则按归属工作空间过滤。 */
export function listExperiencesPaged(opts: { page: number; size: number; workspaceId?: string | null }) {
  const qs = new URLSearchParams({ page: String(opts.page), size: String(opts.size) });
  if (opts.workspaceId) qs.set('workspaceId', opts.workspaceId);
  return request<ExperiencePage>(`/api/experiences/page?${qs}`);
}

/** 有经验存在的归属工作空间 id（供列表筛选条，避免为此拉全量经验）。 */
export function listExperienceWorkspaces() {
  return request<string[]>('/api/experiences/workspaces');
}

/** 当前工作空间「尚未引用」的公共经验（引用选择器列出可引入的经验）。 */
export function listReferencableExperiences() {
  return request<Experience[]>('/api/experiences/referencable');
}

/** 把一批公共经验引用进当前工作空间（已引用的跳过）。 */
export function referenceExperiences(experienceIds: string[]) {
  return request<{ added: number }>('/api/experiences/refs', {
    method: 'POST',
    body: JSON.stringify({ experienceIds }),
  });
}

/** 取消当前工作空间对某经验的引用（不删除经验本体）。 */
export function unreferenceExperience(id: string) {
  return request<{ success: boolean }>(`/api/experiences/${encodeURIComponent(id)}/ref`, {
    method: 'DELETE',
  });
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

/** 经验库全量补索引（embedding 配置变更/文件过多时一键补齐）。立即返回调度概况，索引后台排队进行。 */
export function reindexAllExperiences(force = false) {
  return request<{ configured: boolean; total: number; scheduled: number; skipped: number }>(
    `/api/experiences/reindex-all?force=${force ? 'true' : 'false'}`,
    { method: 'POST' },
  );
}

/** 经验库索引状态汇总：总数 + 各 index_status 计数，用于查看补索引进度。 */
export function getExperienceIndexSummary() {
  return request<{ configured: boolean; total: number; byStatus: Record<string, number> }>(
    '/api/experiences/index-summary',
  );
}

/**
 * 一键从「当前工作空间的整个经验库」构建本体血缘图（SSE 流）。
 * 这是新数据流的主入口：本体血缘图由经验库文件构建，数据源只负责供血。
 */
/**
 * 联网调研业务知识（SSE）：搜索主题 → 抓取网页 → LLM 归纳成《业务知识文档》→ 存为经验。
 * 事件：step* → complete{experience} / error。
 */
export function webResearch(
  body: { topic: string; maxPages?: number; modelOverride?: string; configId?: string },
  handlers: {
    onStep?: (key: string, label: string) => void;
    onComplete?: (payload: { experience: Experience }) => void;
    onError?: (msg: string) => void;
    onClose?: () => void;
  },
): SseHandle {
  return sse('/api/experiences/web-research', body, {
    onEvent: (event, data) => {
      if (event === 'step') {
        try {
          const d = JSON.parse(data);
          handlers.onStep?.(d.key, d.label);
        } catch { /* noop */ }
      } else if (event === 'complete') {
        try {
          handlers.onComplete?.(JSON.parse(data));
        } catch {
          handlers.onError?.('结果解析失败');
        }
      } else if (event === 'error') {
        handlers.onError?.(data);
      }
    },
    onClose: handlers.onClose,
  });
}

export interface BuildManifestEntry { experienceId: string; contentHash: string }

export function extractOntologyFromExperiences(
  body: {
    modelOverride?: string; configId?: string; hint?: string; experienceIds?: string[];
    /** 增量建图：传目标模型 id 时按其构建记录跳过内容未变化的经验 */
    incrementalModelId?: string;
  },
  handlers: {
    onStep?: (key: string, label: string) => void;
    onComplete?: (payload: {
      nodes: unknown[];
      edges: unknown[];
      reply: string;
      salt: string;
      sourceCount?: number;
      incremental?: boolean;
      skippedUnchanged?: number;
      manifest?: BuildManifestEntry[];
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

/** 全量建图落库摘要（服务端建成新模型）。 */
export interface FullBuildResult {
  modelId: string;
  title: string;
  sourceCount: number;
  nodeCount: number;
  edgeCount: number;
}

/**
 * 全量建图（SSE 流式，面向海量经验：千个/万个）：不封顶经验数、分域分批处理全部经验，
 * 服务端直接落成新模型并回写构建记录，返回摘要（不把整图回传前端合并）。
 */
export function buildFullGraph(
  body: { modelOverride?: string; configId?: string; hint?: string; title?: string },
  handlers: {
    onStep?: (key: string, label: string) => void;
    onComplete?: (r: FullBuildResult) => void;
    onError?: (msg: string) => void;
    onClose?: () => void;
  },
): SseHandle {
  return sse('/api/experiences/build-full', body, {
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
