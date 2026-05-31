<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { NT } from '../constants';

const props = defineProps<{
  node: any | null;
  nodes: any[];
  edges: any[];
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update-node-props', id: string, props: { key: string; value: any; source?: string }[]): void;
  (e: 'delete-edge', edgeId: string): void;
  (e: 'update-node-schema', id: string, patch: any): void;
  (e: 'edit-edge-relation', edgeId: string): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
}>();

const tab = ref(0);
const height = ref(250);
const dragging = ref(false);

const t = computed(() => props.node ? (NT as any)[props.node.type] || NT.class : null);
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

const outgoing = computed(() => props.node ? props.edges.filter(e => e.from === props.node.id) : []);
const incoming = computed(() => props.node ? props.edges.filter(e => e.to === props.node.id) : []);

// 关系上的约束: 当前选中节点参与的所有边里,把带约束的提出来,概览页直接展示一遍,
// 这样"点对象"也能看到关联关系的约束,不必再切到关系 Tab。
// 同时记录 edgeId 和该约束在 edge.constraints 数组里的下标，方便就地删除。
const relatedEdgeConstraints = computed(() => {
  const out: { c: any; relLabel: string; edgeId: string; index: number }[] = [];
  if (!props.node) return out;
  const nm = nmap.value;
  for (const e of props.edges) {
    if (e.from !== props.node.id && e.to !== props.node.id) continue;
    const cons = e.constraints || [];
    for (let i = 0; i < cons.length; i++) {
      const c = cons[i];
      const fromL = nm[e.from]?.label || e.from;
      const toL = nm[e.to]?.label || e.to;
      out.push({ c, relLabel: `${fromL} —${e.label || ''}→ ${toL}`, edgeId: e.id, index: i });
    }
  }
  return out;
});

const removeRelatedEdgeConstraint = (edgeId: string, index: number) => {
  const edge = props.edges.find(e => e.id === edgeId);
  if (!edge) return;
  const cons = [...(edge.constraints || [])];
  cons.splice(index, 1);
  emit('update-edge-schema', edgeId, { constraints: cons });
};

// 关系约束的"展开/收起"状态: 同一节点可以展开多条关系
const expandedEdges = ref(new Set<string>());
const toggleEdgeExpand = (id: string) => {
  const s = new Set(expandedEdges.value);
  s.has(id) ? s.delete(id) : s.add(id);
  expandedEdges.value = s;
};

// 切换选中节点时,折叠掉之前展开的关系约束
watch(() => props.node?.id, () => { expandedEdges.value = new Set(); });

const kindLabel = (k?: string) => ({
  cardinality: '基数',
  exclusive:   '互斥',
  symmetric:   '对称',
  transitive:  '传递',
  custom:      '自定义',
} as Record<string, string>)[k || 'custom'] || k || '约束';

// 来源徽章: AI 推理 / 文本提取 / 手动 / 系统预置
const sourceBadge = (s?: string) => {
  if (s === 'inferred') return { text: 'AI推理', color: '#bb77ff', bg: 'rgba(187,119,255,0.12)' };
  if (s === 'derived')  return { text: '文本提取', color: '#22dd88', bg: 'rgba(34,221,136,0.12)' };
  if (s === 'manual')   return { text: '手动', color: '#3d9bff', bg: 'rgba(61,155,255,0.12)' };
  return { text: '预置', color: 'rgba(255,255,255,0.5)', bg: 'rgba(255,255,255,0.06)' };
};

// 属性物理列来源：有来源表时显示 "表.列"，否则 "列"
const attrColumnText = (a: any, node: any): string => {
  if (!a?.column) return '';
  const tables = node?.derived_tables || [];
  const table = tables.length === 1 ? tables[0] : '';
  return table ? `${table}.${a.column}` : a.column;
};

// 属性编辑状态
const editableProps = ref<{ key: string; value: any; source?: string }[]>([]);

watch(() => props.node, (n) => {
  editableProps.value = n?.props ? n.props.map((p: any) => ({ ...p })) : [];
}, { immediate: true });

const propsChanged = computed(() => {
  return JSON.stringify(editableProps.value) !== JSON.stringify(props.node?.props || []);
});

const addEditableProp = () => {
  editableProps.value.push({ key: '', value: '', source: 'manual' });
};

const removeEditableProp = (i: number) => {
  editableProps.value.splice(i, 1);
};

const saveProps = () => {
  if (!props.node) return;
  const cleaned = editableProps.value.filter(p => p.key.trim());
  emit('update-node-props', props.node.id, cleaned);
};

const addAttribute = () => {
  if (!props.node) return;
  const attrs = [...(props.node.attributes || []), { name: '', valueSpace: '', source: 'manual' }];
  emit('update-node-schema', props.node.id, { attributes: attrs });
};

const removeAttribute = (i: number) => {
  if (!props.node) return;
  const attrs = [...(props.node.attributes || [])];
  attrs.splice(i, 1);
  emit('update-node-schema', props.node.id, { attributes: attrs });
};

const updateAttribute = (i: number, field: 'name' | 'valueSpace', value: string) => {
  if (!props.node) return;
  const attrs = (props.node.attributes || []).map((a: any, idx: number) =>
    idx === i ? { ...a, [field]: value } : { ...a }
  );
  emit('update-node-schema', props.node.id, { attributes: attrs });
};

const addConstraint = () => {
  if (!props.node) return;
  const cons = [...(props.node.constraints || []), { kind: 'custom', note: '', source: 'manual' }];
  emit('update-node-schema', props.node.id, { constraints: cons });
};

const removeConstraint = (i: number) => {
  if (!props.node) return;
  const cons = [...(props.node.constraints || [])];
  cons.splice(i, 1);
  emit('update-node-schema', props.node.id, { constraints: cons });
};

const updateConstraint = (i: number, field: 'kind' | 'note', value: string) => {
  if (!props.node) return;
  const cons = (props.node.constraints || []).map((c: any, idx: number) =>
    idx === i ? { ...c, [field]: value } : { ...c }
  );
  emit('update-node-schema', props.node.id, { constraints: cons });
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
  <div :class="['node-info', { open: isOpen || !!node, dragging }]" :style="isOpen || node ? { height: height + 'px' } : {}">
    <template v-if="isOpen || node">
      <div class="ni-drag-handle" @mousedown="startResize" />
      <div class="ni-inner">
        <div class="ni-right">
          <div class="ni-header">
            <div class="ni-header-l">
              <div class="ni-tabs">
                <button v-for="(lb, i) in ['概览', '属性', '关系', '约束']" :key="i" :class="['ni-tab', { on: tab === i }]" @click="tab = i">{{ lb }}</button>
              </div>
            </div>
            <div class="ni-header-r">
              <span v-if="node" class="ni-current">
                <span class="ni-current-dot" :style="t ? { background: t.color || '#42b883' } : {}" />
                <span class="ni-current-lb">{{ node.label }}</span>
              </span>
              <span v-else class="ni-current ni-current-global">
                <span class="ni-current-dot" />
                <span class="ni-current-lb">全局模型</span>
              </span>
              <button class="ni-list-close" @click="emit('close')" title="关闭">×</button>
            </div>
          </div>

          <div class="ni-body">
            <!-- Node Context -->
            <template v-if="node">
              <template v-if="tab === 0">
                <div class="ni-card">
                  <div class="ni-card-title">节点详情</div>
                  <div class="ni-grid">
                    <div class="ni-cell"><div class="ni-cell-k">ID</div><div class="ni-cell-v mono">{{ node.id }}</div></div>
                    <div class="ni-cell"><div class="ni-cell-k">名称</div><div class="ni-cell-v strong">{{ node.label }}</div></div>
                    <div class="ni-cell"><div class="ni-cell-k">类型</div><div class="ni-cell-v"><span class="ni-chip">{{ t?.label || '—' }}</span></div></div>
                    <div class="ni-cell"><div class="ni-cell-k">来源</div>
                      <div class="ni-cell-v">
                        <span class="ni-badge" :style="{color: sourceBadge(node.source).color, background: sourceBadge(node.source).bg}">{{ sourceBadge(node.source).text }}</span>
                      </div>
                    </div>
                    <div class="ni-cell"><div class="ni-cell-k">状态</div><div class="ni-cell-v"><span class="ni-chip ok">● 已激活</span></div></div>
                  </div>
                </div>
                <div v-if="node.derived_source || node.derived_database || (node.derived_tables || []).length" class="ni-card">
                  <div class="ni-card-title">数据来源</div>
                  <div class="ni-grid">
                    <div class="ni-cell"><div class="ni-cell-k">数据源</div><div class="ni-cell-v">{{ node.derived_source || '—' }}</div></div>
                    <div class="ni-cell"><div class="ni-cell-k">数据库</div><div class="ni-cell-v mono">{{ node.derived_database || '—' }}</div></div>
                    <div class="ni-cell ni-cell-wide"><div class="ni-cell-k">来源表</div>
                      <div class="ni-cell-v">
                        <span v-for="(tb, i) in (node.derived_tables || [])" :key="'dt'+i" class="ni-src-chip">{{ tb }}</span>
                        <span v-if="!(node.derived_tables || []).length" class="ni-dim">暂无来源表</span>
                      </div>
                    </div>
                  </div>
                </div>
              </template>
              <template v-if="tab === 1">
                <!-- TBox 属性: 类节点上的属性定义 -->
                <div class="ni-card">
                  <div class="ni-card-title">本体属性 <span class="ni-card-count">{{ (node.attributes || []).length }}</span></div>
                  <div class="ni-attr-list">
                    <div v-for="(a, i) in (node.attributes || [])" :key="'tba'+i" class="ni-attr-item">
                      <div class="ni-attr-name">
                        <input class="ni-inline-input" :value="a.name" placeholder="属性名" @change="(e: any) => updateAttribute(i, 'name', e.target.value)" />
                        <span class="ni-badge" :style="{color: sourceBadge(a.source).color, background: sourceBadge(a.source).bg}">{{ sourceBadge(a.source).text }}</span>
                        <button class="ni-prop-del" @click="removeAttribute(i)" title="删除">✕</button>
                      </div>
                      <div class="ni-attr-meta">
                        <input class="ni-inline-input mono ni-attr-vs" :value="a.valueSpace" placeholder="取值空间" @change="(e: any) => updateAttribute(i, 'valueSpace', e.target.value)" />
                        <span v-if="a.column" class="ni-attr-col" :title="attrColumnText(a, node)">· {{ attrColumnText(a, node) }}</span>
                      </div>
                    </div>
                    <div v-if="(node.attributes || []).length === 0" class="ni-empty">暂无本体属性</div>
                    <div class="ni-prop-actions">
                      <button class="ni-prop-add" @click="addAttribute">+ 新增属性</button>
                    </div>
                  </div>
                </div>

                <!-- 自定义 K-V 属性: 用户在此处编辑 -->
                <div class="ni-card">
                  <div class="ni-card-title">自定义属性 <span class="ni-card-count">{{ editableProps.length }}</span></div>
                  <div class="ni-prop-list">
                    <div v-for="(p, i) in editableProps" :key="i" class="ni-prop-row">
                      <input v-model="p.key" class="ni-prop-key-input" placeholder="键" />
                      <input v-model="p.value" class="ni-prop-val-input" placeholder="值" />
                      <span class="ni-badge" :style="{color: sourceBadge(p.source).color, background: sourceBadge(p.source).bg}">{{ sourceBadge(p.source).text }}</span>
                      <button class="ni-prop-del" @click="removeEditableProp(i)" title="删除">✕</button>
                    </div>
                    <div v-if="editableProps.length === 0" class="ni-empty">暂无自定义属性</div>
                    <div class="ni-prop-actions">
                      <button class="ni-prop-add" @click="addEditableProp">+ 新增属性</button>
                      <button v-if="propsChanged" class="ni-prop-save" @click="saveProps">保存属性</button>
                    </div>
                  </div>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-card">
                  <div class="ni-card-title">节点关系 <span class="ni-card-count">{{ outgoing.length + incoming.length }}</span></div>
                  <table v-if="outgoing.length || incoming.length" class="ni-rel-table">
                    <thead>
                      <tr>
                        <th class="ni-th">方向</th>
                        <th class="ni-th">关系名称</th>
                        <th class="ni-th">来源</th>
                        <th class="ni-th">目标节点</th>
                        <th class="ni-th" style="width:64px"></th>
                      </tr>
                    </thead>
                    <tbody>
                      <template v-for="e in outgoing" :key="e.id">
                        <tr v-if="nmap[e.to]" class="ni-tr">
                          <td class="ni-td">
                            <span class="ni-dir out">→ 输出</span>
                            <span v-if="e.rule_driven" class="ni-rule-icon" title="规则驱动">⚡</span>
                            <span v-if="(e.constraints?.length || 0) > 0" class="ni-lock-inline" :title="(e.constraints || []).map((c: any) => kindLabel(c.kind) + ': ' + c.note).join('\n')">🔒</span>
                          </td>
                          <td class="ni-td amber">{{ e.label || '(未命名)' }}</td>
                          <td class="ni-td">
                            <span v-if="e.source === 'inferred'" class="ni-src purple">AI推理</span>
                            <span v-else-if="e.source === 'derived'" class="ni-src green">文本提取</span>
                            <span v-else class="ni-src dim">预置</span>
                          </td>
                          <td class="ni-td">{{ nmap[e.to].label }}</td>
                          <td class="ni-td ni-td-actions">
                            <button class="ni-edge-edit" @click="emit('edit-edge-relation', e.id)" title="编辑关系">✎</button>
                            <button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button>
                          </td>
                        </tr>
                      </template>
                      <template v-for="e in incoming" :key="e.id">
                        <tr v-if="nmap[e.from]" class="ni-tr">
                          <td class="ni-td">
                            <span class="ni-dir in">← 输入</span>
                            <span v-if="e.rule_driven" class="ni-rule-icon" title="规则驱动">⚡</span>
                            <span v-if="(e.constraints?.length || 0) > 0" class="ni-lock-inline" :title="(e.constraints || []).map((c: any) => kindLabel(c.kind) + ': ' + c.note).join('\n')">🔒</span>
                          </td>
                          <td class="ni-td amber">{{ e.label || '(未命名)' }}</td>
                          <td class="ni-td">
                            <span v-if="e.source === 'inferred'" class="ni-src purple">AI推理</span>
                            <span v-else-if="e.source === 'derived'" class="ni-src green">文本提取</span>
                            <span v-else class="ni-src dim">预置</span>
                          </td>
                          <td class="ni-td">{{ nmap[e.from].label }}</td>
                          <td class="ni-td ni-td-actions">
                            <button class="ni-edge-edit" @click="emit('edit-edge-relation', e.id)" title="编辑关系">✎</button>
                            <button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button>
                          </td>
                        </tr>
                      </template>
                    </tbody>
                  </table>
                  <div v-if="!outgoing.length && !incoming.length" class="ni-empty">暂无关系</div>
                </div>
              </template>
              <template v-if="tab === 3">
                <div class="ni-card">
                  <div class="ni-card-title">节点约束 <span class="ni-card-count">{{ (node.constraints || []).length }}</span></div>
                  <div v-if="(node.constraints?.length || 0) > 0" class="ni-cons-list">
                    <div v-for="(c, i) in (node.constraints || [])" :key="'c'+i" class="ni-cons-item">
                      <div class="ni-cons-head">
                        <select class="ni-inline-select" :value="c.kind || 'custom'" @change="(e: any) => updateConstraint(i, 'kind', e.target.value)">
                          <option value="cardinality">基数</option>
                          <option value="exclusive">互斥</option>
                          <option value="symmetric">对称</option>
                          <option value="transitive">传递</option>
                          <option value="custom">自定义</option>
                        </select>
                        <span class="ni-badge" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
                        <button class="ni-prop-del" @click="removeConstraint(i)" title="删除">✕</button>
                      </div>
                      <input class="ni-inline-input" :value="c.note" placeholder="约束说明" @change="(e: any) => updateConstraint(i, 'note', e.target.value)" />
                    </div>
                  </div>
                  <div v-else class="ni-empty">暂无节点约束</div>
                  <div class="ni-prop-actions">
                    <button class="ni-prop-add" @click="addConstraint">+ 新增约束</button>
                  </div>
                </div>

                <div v-if="relatedEdgeConstraints.length > 0" class="ni-card">
                  <div class="ni-card-title">所在关系的约束 <span class="ni-card-count">{{ relatedEdgeConstraints.length }}</span></div>
                  <div class="ni-cons-list">
                    <div v-for="(row, i) in relatedEdgeConstraints" :key="'rec'+i" class="ni-cons-item">
                      <div class="ni-cons-head">
                        <span class="ni-cons-kind">{{ kindLabel(row.c.kind) }}</span>
                        <span class="ni-badge" :style="{color: sourceBadge(row.c.source).color, background: sourceBadge(row.c.source).bg}">{{ sourceBadge(row.c.source).text }}</span>
                        <span class="ni-cons-rel">{{ row.relLabel }}</span>
                        <button class="ni-prop-del" style="margin-left:auto" @click="removeRelatedEdgeConstraint(row.edgeId, row.index)" title="删除该约束">✕</button>
                      </div>
                      <div class="ni-cons-note">{{ row.c.note }}</div>
                    </div>
                  </div>
                </div>
              </template>
            </template>

            <!-- Global Context -->
            <template v-else>
              <template v-if="tab === 0">
                <div class="ni-card">
                  <div class="ni-card-title">全局模型概览</div>
                  <div class="ni-grid">
                    <div class="ni-cell"><div class="ni-cell-k">总节点数</div><div class="ni-cell-v strong">{{ nodes.length }} <span class="ni-unit">实体</span></div></div>
                    <div class="ni-cell"><div class="ni-cell-k">总关系数</div><div class="ni-cell-v strong">{{ edges.length }} <span class="ni-unit">流向</span></div></div>
                    <div class="ni-cell"><div class="ni-cell-k">推演引擎</div><div class="ni-cell-v"><span class="ni-chip ok">● 实时就绪</span></div></div>
                  </div>
                </div>
              </template>
              <template v-if="tab === 1">
                <div class="ni-card">
                  <div class="ni-card-title">模型属性概要</div>
                  <div class="ni-grid">
                    <div class="ni-cell"><div class="ni-cell-k">节点总数</div><div class="ni-cell-v strong">{{ nodes.length }}</div></div>
                    <div class="ni-cell"><div class="ni-cell-k">关系总数</div><div class="ni-cell-v strong">{{ edges.length }}</div></div>
                  </div>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-card">
                  <div class="ni-card-title">全局拓扑关系表 <span class="ni-card-count">{{ edges.length }}</span></div>
                  <table v-if="edges.length" class="ni-rel-table">
                    <thead>
                      <tr>
                        <th class="ni-th">关系名称</th>
                        <th class="ni-th">起始节点</th>
                        <th class="ni-th">目标节点</th>
                      </tr>
                    </thead>
                    <tbody>
                      <template v-for="e in edges" :key="e.id">
                        <tr v-if="nmap[e.from] && nmap[e.to]" class="ni-tr">
                          <td class="ni-td amber">{{ e.label || '(未命名)' }}</td>
                          <td class="ni-td">{{ nmap[e.from].label }}</td>
                          <td class="ni-td">{{ nmap[e.to].label }}</td>
                        </tr>
                      </template>
                    </tbody>
                  </table>
                  <div v-if="!edges.length" class="ni-empty">暂无全局关系</div>
                </div>
              </template>
              <template v-if="tab === 3">
                <div class="ni-card">
                  <div class="ni-card-title">全局约束概要</div>
                  <div class="ni-grid">
                    <div class="ni-cell"><div class="ni-cell-k">带约束的节点</div><div class="ni-cell-v strong">{{ nodes.filter(n => (n.constraints?.length || 0) > 0).length }} <span class="ni-unit">个</span></div></div>
                    <div class="ni-cell"><div class="ni-cell-k">带约束的关系</div><div class="ni-cell-v strong">{{ edges.filter(e => (e.constraints?.length || 0) > 0).length }} <span class="ni-unit">条</span></div></div>
                  </div>
                </div>
              </template>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ni-src-chip {
  display: inline-block;
  padding: 2px 8px;
  margin: 2px 4px 2px 0;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #22dd88;
  background: rgba(34, 221, 136, 0.12);
  border-radius: 6px;
}
.ni-dim { color: rgba(255,255,255,0.4); font-size: 12px; }
.ni-cell-wide { grid-column: 1 / -1; }
.ni-attr-meta { display: flex; align-items: center; gap: 8px; }
.ni-attr-vs { flex: 0 1 auto; }
.ni-attr-col {
  flex: 0 1 auto;
  min-width: 0;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  color: rgba(255,255,255,0.45);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
