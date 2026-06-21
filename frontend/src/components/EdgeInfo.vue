<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { NT } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';

const props = defineProps<{
  edge: OntologyEdge | null;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'edit-relation', edgeId: string): void;
  (e: 'delete-edge', edgeId: string): void;
  (e: 'add-edges', payload: { label: string; inputs: string[]; outputs: string[] }): void;
  (e: 'delete-relation', edgeId: string): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
}>();

const tab = ref(0);
const height = ref(250);
const dragging = ref(false);
// 端点 Tab 下"+ 添加输入/输出节点"的展开状态
const addingInput = ref(false);
const addingOutput = ref(false);

const SCHEMA_ONLY_TYPES = new Set(['attribute', 'constraint']);

const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));
const fromNode = computed(() => props.edge ? nmap.value[props.edge.from] || null : null);
const toNode = computed(() => props.edge ? nmap.value[props.edge.to] || null : null);

// 同名关系的所有边（用于"输入×输出"视图）
const siblingEdges = computed(() => {
  if (!props.edge) return [];
  const lbl = props.edge.label || '';
  if (!lbl) return [props.edge];
  return props.edges.filter(e => e.label === lbl);
});
const siblingInputs = computed(() => {
  const ids = new Set<string>();
  for (const e of siblingEdges.value) ids.add(e.from);
  return [...ids].map(id => nmap.value[id]).filter(Boolean) as OntologyNode[];
});
const siblingOutputs = computed(() => {
  const ids = new Set<string>();
  for (const e of siblingEdges.value) ids.add(e.to);
  return [...ids].map(id => nmap.value[id]).filter(Boolean) as OntologyNode[];
});

// 端点 Tab 中可被加入的候选节点：排除 attribute/constraint 这种纯 Schema 节点，
// 同时输入候选要排除已经是输出的节点，输出候选要排除已经是输入的节点，避免同节点既输入又输出。
const inputCandidates = computed(() => {
  const existing = new Set(siblingInputs.value.map(n => n.id));
  const opposite = new Set(siblingOutputs.value.map(n => n.id));
  return props.nodes.filter(n => !SCHEMA_ONLY_TYPES.has(n.type) && !existing.has(n.id) && !opposite.has(n.id));
});
const outputCandidates = computed(() => {
  const existing = new Set(siblingOutputs.value.map(n => n.id));
  const opposite = new Set(siblingInputs.value.map(n => n.id));
  return props.nodes.filter(n => !SCHEMA_ONLY_TYPES.has(n.type) && !existing.has(n.id) && !opposite.has(n.id));
});

const addInputNode = (nodeId: string) => {
  if (!props.edge) return;
  const outs = siblingOutputs.value.map(n => n.id);
  if (outs.length === 0) return;
  emit('add-edges', { label: props.edge.label || '', inputs: [nodeId], outputs: outs });
  addingInput.value = false;
};
const addOutputNode = (nodeId: string) => {
  if (!props.edge) return;
  const ins = siblingInputs.value.map(n => n.id);
  if (ins.length === 0) return;
  emit('add-edges', { label: props.edge.label || '', inputs: ins, outputs: [nodeId] });
  addingOutput.value = false;
};

const typeColor = (n: OntologyNode | null) => {
  if (!n) return '#2f86d6';
  const t = (NT as any)[n.type] || NT.class;
  return t.color || '#2f86d6';
};
const typeLabel = (n: OntologyNode | null) => {
  if (!n) return '—';
  const t = (NT as any)[n.type] || NT.class;
  return t.label || n.type;
};

const sourceBadge = (s?: string) => {
  if (s === 'inferred') return { text: 'AI推理', color: '#bb77ff', bg: 'rgba(187,119,255,0.12)' };
  if (s === 'derived')  return { text: '文本提取', color: '#22dd88', bg: 'rgba(34,221,136,0.12)' };
  if (s === 'manual')   return { text: '手动', color: '#3d9bff', bg: 'rgba(61,155,255,0.12)' };
  if (s === 'predicted') return { text: '推演', color: '#fbbf24', bg: 'rgba(251,191,36,0.12)' };
  return { text: '预置', color: 'rgba(255,255,255,0.5)', bg: 'rgba(255,255,255,0.06)' };
};

// 受控关系语义类型 → 中文（血缘可读）
const relTypeLabel = (t?: string) => ({
  produces: '产出 →',
  consumes: '消耗 ←',
  derived_from: '派生自 ←',
  depends_on: '依赖 ←',
  triggers: '触发 →',
  governs: '治理/约束',
  composed_of: '组成 ⊃',
  transforms: '转换 →',
  flows_to: '流向 →',
  associated_with: '关联',
} as Record<string, string>)[t || ''] || t || '关系';

// 置信度配色 / 提示：高=绿、中=黄、低=红
const confColor = (c: number) => (c >= 0.85 ? '#22dd88' : c >= 0.55 ? '#ffcc44' : '#ff7755');
const confHint = (c: number) => (c >= 0.85 ? '高' : c >= 0.55 ? '中' : '低（多为推断，需复核）');

const kindLabel = (k?: string) => ({
  cardinality: '基数',
  exclusive:   '互斥',
  symmetric:   '对称',
  transitive:  '传递',
  custom:      '自定义',
} as Record<string, string>)[k || 'custom'] || k || '约束';

// 切换边时回到概览页
watch(() => props.edge?.id, () => {
  tab.value = 0;
  addingInput.value = false;
  addingOutput.value = false;
});

const addEdgeConstraint = () => {
  if (!props.edge) return;
  const cons = [...(props.edge.constraints || []), { kind: 'custom', note: '', source: 'manual' }];
  emit('update-edge-schema', props.edge.id, { constraints: cons });
};
const removeEdgeConstraint = (i: number) => {
  if (!props.edge) return;
  const cons = [...(props.edge.constraints || [])];
  cons.splice(i, 1);
  emit('update-edge-schema', props.edge.id, { constraints: cons });
};
const updateEdgeConstraint = (i: number, field: 'kind' | 'note', value: string) => {
  if (!props.edge) return;
  const cons = (props.edge.constraints || []).map((c: any, idx: number) =>
    idx === i ? { ...c, [field]: value } : { ...c }
  );
  emit('update-edge-schema', props.edge.id, { constraints: cons });
};

const startResize = (e: MouseEvent) => {
  e.preventDefault();
  const startY = e.clientY;
  const startH = height.value;
  dragging.value = true;
  const mv = (ev: MouseEvent) => {
    const delta = startY - ev.clientY;
    height.value = Math.max(80, Math.min(520, startH + delta));
  };
  const up = () => {
    dragging.value = false;
    document.removeEventListener('mousemove', mv);
    document.removeEventListener('mouseup', up);
  };
  document.addEventListener('mousemove', mv);
  document.addEventListener('mouseup', up);
};
</script>

<template>
  <div :class="['node-info', { open: !!edge, dragging }]" :style="edge ? { height: height + 'px' } : {}">
    <template v-if="edge">
      <div class="ni-drag-handle" @mousedown="startResize" />
      <div class="ni-inner">
        <div class="ni-right">
          <div class="ni-header">
            <div class="ni-header-l">
              <div class="ni-tabs">
                <button v-for="(lb, i) in ['概览', '端点', '约束']" :key="i" :class="['ni-tab', { on: tab === i }]" @click="tab = i">{{ lb }}</button>
              </div>
            </div>
            <div class="ni-header-r">
              <span class="ni-current">
                <span class="ni-current-dot" style="background:#fbbf24" />
                <span class="ni-current-lb">{{ edge.label || '(未命名关系)' }}</span>
              </span>
              <button class="ni-prop-add ei-edit-btn" @click="emit('edit-relation', edge.id)" title="编辑输入/输出">编辑输入输出</button>
              <button class="ei-del-btn" @click="emit('delete-relation', edge.id)" title="删除整组关系">删除关系</button>
              <button class="ni-list-close" @click="emit('close')" title="关闭">×</button>
            </div>
          </div>

          <div class="ni-body">
            <!-- 概览 -->
            <template v-if="tab === 0">
              <div class="ni-card">
                <div class="ni-card-title">关系详情</div>
                <table class="ni-kv">
                  <tbody>
                    <tr><td class="ni-kv-k">ID</td><td class="ni-kv-v mono">{{ edge.id }}</td></tr>
                    <tr><td class="ni-kv-k">名称</td><td class="ni-kv-v strong">{{ edge.label || '(未命名)' }}</td></tr>
                    <tr><td class="ni-kv-k">来源</td><td class="ni-kv-v"><span class="ni-badge" :style="{color: sourceBadge(edge.source).color, background: sourceBadge(edge.source).bg}">{{ sourceBadge(edge.source).text }}</span></td></tr>
                    <tr v-if="edge.rel_type">
                      <td class="ni-kv-k">关系类型</td>
                      <td class="ni-kv-v"><span class="ni-chip" :title="edge.rel_type">{{ relTypeLabel(edge.rel_type) }}</span></td>
                    </tr>
                    <tr v-if="edge.confidence != null">
                      <td class="ni-kv-k">置信度</td>
                      <td class="ni-kv-v">
                        <span class="ei-conf" :style="{ color: confColor(edge.confidence) }">{{ (edge.confidence * 100).toFixed(0) }}%</span>
                        <span class="ei-conf-hint">{{ confHint(edge.confidence) }}</span>
                      </td>
                    </tr>
                    <tr>
                      <td class="ni-kv-k">规则驱动</td>
                      <td class="ni-kv-v">
                        <span v-if="edge.rule_driven" class="ni-chip" style="color:#ff3399;background:rgba(255,51,153,0.12)">⚡ 是</span>
                        <span v-else class="ni-chip">否</span>
                      </td>
                    </tr>
                    <tr><td class="ni-kv-k">约束数</td><td class="ni-kv-v strong">{{ (edge.constraints || []).length }} <span class="ni-unit">条</span></td></tr>
                  </tbody>
                </table>
                <!-- 血缘证据：该关系所依据的 FK列/原文引文/命名依据 -->
                <div v-if="edge.evidence" class="ei-evidence">
                  <span class="ei-evidence-icon" title="血缘证据">❝</span>
                  <span class="ei-evidence-text">{{ edge.evidence }}</span>
                </div>
              </div>

              <div v-if="edge.derived_source || edge.derived_database || (edge.derived_tables || []).length" class="ni-card ni-card-full">
                <div class="ni-card-title">数据来源血缘</div>
                <table class="ni-kv">
                  <tbody>
                    <tr :class="{ missing: !edge.derived_source }">
                      <td class="ni-kv-k">数据源</td>
                      <td class="ni-kv-v">
                        <span v-if="edge.derived_source" class="ni-src-val">{{ edge.derived_source }}</span>
                        <span v-else class="ni-kv-empty">未关联</span>
                      </td>
                    </tr>
                    <tr :class="{ missing: !edge.derived_database }">
                      <td class="ni-kv-k">数据库</td>
                      <td class="ni-kv-v">
                        <span v-if="edge.derived_database" class="ni-src-val mono">{{ edge.derived_database }}</span>
                        <span v-else class="ni-kv-empty">未关联</span>
                      </td>
                    </tr>
                    <tr :class="{ missing: !(edge.derived_tables || []).length }">
                      <td class="ni-kv-k">来源表 <span v-if="(edge.derived_tables || []).length" class="ni-kv-count">{{ edge.derived_tables.length }}</span></td>
                      <td class="ni-kv-v">
                        <span v-for="(tb, i) in (edge.derived_tables || [])" :key="'edt'+i" class="ei-src-chip">{{ tb }}</span>
                        <span v-if="!(edge.derived_tables || []).length" class="ni-kv-empty">未关联</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div class="ni-card ni-card-full">
                <div class="ni-card-title">边方向</div>
                <div class="ei-flow">
                  <div class="ei-endpoint" v-if="fromNode">
                    <span class="ei-dot" :style="{ background: typeColor(fromNode) }" />
                    <div class="ei-ep-meta">
                      <div class="ei-ep-label">{{ fromNode.label }}</div>
                      <div class="ei-ep-type">{{ typeLabel(fromNode) }}</div>
                    </div>
                  </div>
                  <div class="ei-arrow">
                    <span class="ei-arrow-line" />
                    <span class="ei-arrow-label">{{ edge.label || '关系' }}</span>
                    <span class="ei-arrow-head">▶</span>
                  </div>
                  <div class="ei-endpoint" v-if="toNode">
                    <span class="ei-dot" :style="{ background: typeColor(toNode) }" />
                    <div class="ei-ep-meta">
                      <div class="ei-ep-label">{{ toNode.label }}</div>
                      <div class="ei-ep-type">{{ typeLabel(toNode) }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <!-- 端点（同名关系全部输入×输出） -->
            <template v-if="tab === 1">
              <div class="ni-card">
                <div class="ni-card-title">输入节点 <span class="ni-card-count">{{ siblingInputs.length }}</span></div>
                <div v-if="siblingInputs.length" class="ei-chip-list">
                  <span v-for="n in siblingInputs" :key="'in'+n.id" class="ei-chip" :style="{ borderColor: typeColor(n) + '66' }">
                    <span class="ei-dot" :style="{ background: typeColor(n) }" />
                    <span>{{ n.label }}</span>
                    <span class="ei-chip-type">{{ typeLabel(n) }}</span>
                  </span>
                </div>
                <div v-else class="ni-empty">暂无输入</div>
                <div class="ei-add-row">
                  <button v-if="!addingInput" class="ni-prop-add" :disabled="siblingOutputs.length === 0 || inputCandidates.length === 0"
                    :title="siblingOutputs.length === 0 ? '需要先有输出节点' : (inputCandidates.length === 0 ? '没有可添加的节点' : '')"
                    @click="addingInput = true">+ 添加输入节点</button>
                  <template v-else>
                    <div class="ei-add-picker">
                      <div class="ei-add-picker-hd">
                        <span class="ei-add-picker-tt">选择要加入的输入节点</span>
                        <button class="ei-add-picker-x" @click="addingInput = false">×</button>
                      </div>
                      <div v-if="inputCandidates.length" class="ei-chip-list">
                        <button v-for="n in inputCandidates" :key="'addin'+n.id" class="ei-chip ei-chip-btn" :style="{ borderColor: typeColor(n) + '66' }" @click="addInputNode(n.id)">
                          <span class="ei-dot" :style="{ background: typeColor(n) }" />
                          <span>{{ n.label }}</span>
                          <span class="ei-chip-type">{{ typeLabel(n) }}</span>
                        </button>
                      </div>
                      <div v-else class="ni-empty">没有可添加的节点</div>
                    </div>
                  </template>
                </div>
              </div>
              <div class="ni-card">
                <div class="ni-card-title">输出节点 <span class="ni-card-count">{{ siblingOutputs.length }}</span></div>
                <div v-if="siblingOutputs.length" class="ei-chip-list">
                  <span v-for="n in siblingOutputs" :key="'out'+n.id" class="ei-chip" :style="{ borderColor: typeColor(n) + '66' }">
                    <span class="ei-dot" :style="{ background: typeColor(n) }" />
                    <span>{{ n.label }}</span>
                    <span class="ei-chip-type">{{ typeLabel(n) }}</span>
                  </span>
                </div>
                <div v-else class="ni-empty">暂无输出</div>
                <div class="ei-add-row">
                  <button v-if="!addingOutput" class="ni-prop-add" :disabled="siblingInputs.length === 0 || outputCandidates.length === 0"
                    :title="siblingInputs.length === 0 ? '需要先有输入节点' : (outputCandidates.length === 0 ? '没有可添加的节点' : '')"
                    @click="addingOutput = true">+ 添加输出节点</button>
                  <template v-else>
                    <div class="ei-add-picker">
                      <div class="ei-add-picker-hd">
                        <span class="ei-add-picker-tt">选择要加入的输出节点</span>
                        <button class="ei-add-picker-x" @click="addingOutput = false">×</button>
                      </div>
                      <div v-if="outputCandidates.length" class="ei-chip-list">
                        <button v-for="n in outputCandidates" :key="'addout'+n.id" class="ei-chip ei-chip-btn" :style="{ borderColor: typeColor(n) + '66' }" @click="addOutputNode(n.id)">
                          <span class="ei-dot" :style="{ background: typeColor(n) }" />
                          <span>{{ n.label }}</span>
                          <span class="ei-chip-type">{{ typeLabel(n) }}</span>
                        </button>
                      </div>
                      <div v-else class="ni-empty">没有可添加的节点</div>
                    </div>
                  </template>
                </div>
              </div>
              <div class="ni-card ni-card-full" v-if="siblingEdges.length > 1">
                <div class="ni-card-title">同名边明细 <span class="ni-card-count">{{ siblingEdges.length }}</span></div>
                <table class="ni-rel-table">
                  <thead>
                    <tr>
                      <th class="ni-th">起点</th>
                      <th class="ni-th">终点</th>
                      <th class="ni-th">来源</th>
                      <th class="ni-th" style="width:32px"></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="e in siblingEdges" :key="e.id" class="ni-tr">
                      <td class="ni-td">{{ nmap[e.from]?.label || e.from }}</td>
                      <td class="ni-td">{{ nmap[e.to]?.label || e.to }}</td>
                      <td class="ni-td">
                        <span class="ni-badge" :style="{color: sourceBadge(e.source).color, background: sourceBadge(e.source).bg}">{{ sourceBadge(e.source).text }}</span>
                      </td>
                      <td class="ni-td"><button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除这条边">✕</button></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>

            <!-- 约束 -->
            <template v-if="tab === 2">
              <div class="ni-card">
                <div class="ni-card-head">
                  <div class="ni-card-title">关系约束 <span class="ni-card-count">{{ (edge.constraints || []).length }}</span></div>
                  <button class="ni-prop-add ni-prop-add--head" @click="addEdgeConstraint">+ 新增约束</button>
                </div>
                <div v-if="(edge.constraints?.length || 0) > 0" class="ni-cons-list">
                  <div v-for="(c, i) in (edge.constraints || [])" :key="'ec'+i" class="ni-cons-item">
                    <div class="ni-cons-head">
                      <select class="ni-inline-select" :value="c.kind || 'custom'" @change="(ev: any) => updateEdgeConstraint(i, 'kind', ev.target.value)">
                        <option value="cardinality">基数</option>
                        <option value="exclusive">互斥</option>
                        <option value="symmetric">对称</option>
                        <option value="transitive">传递</option>
                        <option value="custom">自定义</option>
                      </select>
                      <span class="ni-badge" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
                      <button class="ni-prop-del" @click="removeEdgeConstraint(i)" title="删除">✕</button>
                    </div>
                    <input class="ni-inline-input" :value="c.note" placeholder="约束说明" @change="(ev: any) => updateEdgeConstraint(i, 'note', ev.target.value)" />
                  </div>
                </div>
                <div v-else class="ni-empty">暂无约束</div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ei-edit-btn { padding: 4px 10px; font-size: 12px; }
.ei-del-btn {
  padding: 4px 10px;
  font-size: 12px;
  background: transparent;
  border: 1px solid rgba(239, 68, 68, 0.4);
  color: #ef4444;
  border-radius: 6px;
  cursor: pointer;
  font-family: inherit;
  transition: background-color .15s;
}
.ei-del-btn:hover { background: rgba(239, 68, 68, 0.12); }
.ei-flow { display: flex; align-items: center; gap: 12px; padding: 12px 4px; }
.ei-endpoint { flex: 1; display: flex; align-items: center; gap: 8px; min-width: 0; padding: 8px 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06); border-radius: 8px; }
.ei-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.ei-ep-meta { min-width: 0; }
.ei-ep-label { font-size: 13px; color: var(--text-main); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ei-ep-type { font-size: 11px; color: var(--text-dim); margin-top: 2px; }
.ei-arrow { display: flex; align-items: center; gap: 6px; color: #fbbf24; font-size: 12px; flex-shrink: 0; }
.ei-arrow-line { width: 28px; height: 1px; background: #fbbf24; }
.ei-arrow-label { font-family: 'JetBrains Mono', monospace; font-size: 11px; padding: 2px 8px; background: rgba(251,191,36,0.1); border-radius: 100px; }
.ei-arrow-head { font-size: 10px; }
.ei-chip-list { display: flex; flex-wrap: wrap; gap: 6px; }
.ei-chip { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08); border-radius: 100px; font-size: 12px; color: var(--text-main); }
.ei-chip-type { color: var(--text-dim); font-size: 11px; }
.ei-chip-btn { cursor: pointer; font-family: inherit; transition: background-color .15s, border-color .15s; }
.ei-chip-btn:hover { background: rgba(255,255,255,0.08); }
.ei-add-row { margin-top: 8px; }
.ei-add-picker { background: rgba(255,255,255,0.03); border: 1px dashed rgba(255,255,255,0.12); border-radius: 8px; padding: 8px 10px; }
.ei-add-picker-hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.ei-add-picker-tt { font-size: 11px; color: var(--text-dim); }
.ei-add-picker-x { background: transparent; border: none; color: var(--text-dim); font-size: 16px; line-height: 1; cursor: pointer; padding: 0 4px; }
.ei-add-picker-x:hover { color: var(--text-main); }
.ei-src-chip {
  display: inline-block;
  padding: 2px 8px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #22dd88;
  background: rgba(34, 221, 136, 0.12);
  border-radius: 6px;
}
.ei-conf { font-weight: 700; font-family: 'JetBrains Mono', monospace; }
.ei-conf-hint { margin-left: 8px; font-size: 11px; color: rgba(255,255,255,0.45); }
.ei-evidence {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.5;
  font-style: italic;
  color: rgba(255,255,255,0.78);
  background: rgba(34,221,136,0.06);
  border-left: 2px solid rgba(34,221,136,0.5);
  border-radius: 4px;
}
.ei-evidence-icon { color: #22dd88; font-weight: 700; font-style: normal; flex-shrink: 0; }
</style>
