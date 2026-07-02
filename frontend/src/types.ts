// Core domain types shared by App.vue and components.

export type NodeSource = 'derived' | 'inferred' | 'preset' | 'manual' | string;

export interface OntologyNode {
  id: string;
  label: string;
  type: string;
  source?: NodeSource;
  x?: number;
  y?: number;
  properties?: Record<string, any>;
  props?: { key: string; value: any; source?: string }[];
  // 静态本体 TBox: 类节点上的属性定义与约束(不渲染为图节点,只在 Schema 面板里)
  attributes?: OntologyAttribute[];
  constraints?: OntologyConstraint[];
  // 数据来源（DB schema 抽取时打标）
  derived_tables?: string[];
  derived_source?: string;
  derived_database?: string;
  confidence?: number;
  /** 血缘证据：抽取该节点所依据的原文引文/出处（≤30字），可审计。 */
  evidence?: string;
  isNew?: boolean;
  [k: string]: any;
}

export interface OntologyEdge {
  id: string;
  from: string;
  to: string;
  label?: string;
  source?: NodeSource;
  rule_driven?: boolean;
  ruleId?: string;
  /** 受控关系语义类型：produces/consumes/derived_from/depends_on/triggers/governs/composed_of/transforms/flows_to/associated_with。血缘遍历据此判方向。 */
  rel_type?: string;
  // 关系类型上的约束: 基数/对称/传递…(不渲染在边上,只在 Schema 面板里 + 边上一个🔒)
  constraints?: OntologyConstraint[];
  derived_tables?: string[];
  derived_source?: string;
  derived_database?: string;
  /** 血缘证据：该关系所依据的 FK列/原文引文/命名依据（≤30字）。 */
  evidence?: string;
  confidence?: number;
  isNew?: boolean;
  [k: string]: any;
}

/**
 * chat 一轮对话对图谱产生的「增删改」集合,经 update 事件从 ChatPanel 透传到 App 的合并器。
 * 用单一对象承载,避免在多层组件间透传一长串位置参数。
 */
export interface GraphMutation {
  /** 新增节点 */
  addNodes?: OntologyNode[];
  /** 新增关系 */
  addEdges?: OntologyEdge[];
  /** 待删除的现有节点 id(连带删除其相关边) */
  removeNodeIds?: string[];
  /** 待删除的现有边 id */
  removeEdgeIds?: string[];
  /** 对现有节点的局部 patch(须含 id);只覆盖给出的字段(label/type/props/attributes…)。 */
  updateNodes?: (Partial<OntologyNode> & { id: string })[];
  /** 对现有边的局部 patch(须含 id);只覆盖给出的字段(label/rel_type/from/to…)。 */
  updateEdges?: (Partial<OntologyEdge> & { id: string })[];
}

/** 属性来源方式: 数据库提取 / 文件提取 / 自定义 */
export type AttrSourceMethod = 'db' | 'file' | 'custom';

/** TBox 属性定义: 类节点上挂的属性 + 取值范围 */
export interface OntologyAttribute {
  name: string;
  valueSpace?: string;
  /** 物理表 */
  table?: string;
  /** 物理字段 */
  column?: string;
  description?: string;
  source?: NodeSource;
  /** 来源方式（数据库提取 / 文件提取 / 自定义），缺省时由 column/source 推断 */
  sourceMethod?: AttrSourceMethod;
}

/** TBox 约束: 基数限制 / 互斥 / 对称 / 传递 … */
export interface OntologyConstraint {
  kind?: 'cardinality' | 'exclusive' | 'symmetric' | 'transitive' | 'custom';
  note: string;
  source?: NodeSource;
}

export interface OntologyModel {
  id: string;
  name?: string;
  title?: string;
  description?: string;
  desc?: string;
  graphData: { nodes: OntologyNode[]; edges: OntologyEdge[] };
  updated?: string;
  [k: string]: any;
}

export interface SourceMeta {
  name: string;
  type: 'pdf' | 'image' | 'docx' | 'audio' | 'url' | 'skipped' | string;
  size: number;
  chars?: number;
  pages?: number;
  paragraphs?: number;
  tables?: number;
  title?: string;
  truncated?: boolean;
  renderedPages?: number;
  usedHeadless?: boolean;
  durationSec?: number;
  model?: string;
  reason?: string;
  /** 音频转写正文（ASR）：随抽取结果回传，可在导入弹窗展开查看 */
  transcript?: string;
}

// ===== 模型配置(LLM 模型管理) =====

/** 共享的模型配置元字段 — 命名/URL/能力等,不含 id 与 enabled。 */
export interface ModelConfigCore {
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
}

/** 完整的服务端模型配置:含 id、enabled、时间戳。 */
export interface ModelConfig extends ModelConfigCore {
  id: string;
  enabled: boolean;
  createdAt?: number;
  updatedAt?: number;
}
