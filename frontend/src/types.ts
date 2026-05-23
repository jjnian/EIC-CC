// Core domain types shared by App.vue and components.

export type NodeSource = 'predicted' | 'derived' | 'inferred' | 'preset' | string;

export interface OntologyNode {
  id: string;
  label: string;
  type: string;
  source?: NodeSource;
  x?: number;
  y?: number;
  properties?: Record<string, any>;
  props?: { key: string; value: any; source?: string }[];
  predictedStep?: number;
  predictedIntent?: 'forward' | 'backward';
  confidence?: number;
  effectiveProbability?: number;
  explanation?: string;
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
  isNew?: boolean;
  [k: string]: any;
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

export interface ChainStep {
  step: number;
  nodeId: string;
  label: string;
  type: string;
  triggeredBy?: string[];
  ruleId?: string | null;
  explanation?: string;
  confidence?: number;
  effectiveProbability?: number;
  cumulativeCredibility?: number;
}

export interface Constraint {
  nodeId: string;
  mode: 'force' | 'block' | 'probability';
  note?: string;
  // 仅 mode='probability' 时使用，0..1
  probability?: number;
}

/** P1-7：预测节点的三段式解释缓存。 */
export interface NodeExplanation {
  evidence: string;
  assumptions: string;
  counterexamples: string;
  generatedAt: number;
  modelName?: string;
}

export interface PredictionDag {
  intent?: 'forward' | 'backward';
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  chain: ChainStep[];
  constraints?: Constraint[];
  // P1-7：按预测节点 id 缓存的解释
  explanations?: Record<string, NodeExplanation>;
}

export interface Scenario {
  id: string;
  name: string;
  modelId: string;
  parentBranchId?: string | null;
  intent?: 'forward' | 'backward';
  steps: number;
  seeds: string[];
  prompt?: string;
  createdAt: number;
  dag?: PredictionDag;
  // P1-8：本次推演完整 prompt 快照（按需通过 raw-prompt 端点获取，默认列表不带）
  rawPrompt?: string;
  // legacy v0.5 flat snapshot fields:
  nodes?: OntologyNode[];
  edges?: OntologyEdge[];
  chain?: ChainStep[];
}

export interface SourceMeta {
  name: string;
  type: 'pdf' | 'image' | 'skipped' | string;
  size: number;
  chars?: number;
  pages?: number;
  truncated?: boolean;
  renderedPages?: number;
  reason?: string;
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
