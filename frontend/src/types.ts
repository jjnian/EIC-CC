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
}

export interface Constraint {
  nodeId: string;
  mode: 'force' | 'block';
  note?: string;
}

export interface PredictionDag {
  intent?: 'forward' | 'backward';
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  chain: ChainStep[];
  constraints?: Constraint[];
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
