<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { NT } from '../constants';
import type { AttrSourceMethod } from '../types';

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

// 来源方式：显式 sourceMethod 优先；否则由物理字段 / 来源推断
//   有物理列 → 数据库提取；文本/AI 提取 → 文件提取；其余 → 自定义
const sourceMethodOf = (a: any): AttrSourceMethod => {
  if (a?.sourceMethod) return a.sourceMethod;
  if (a?.column) return 'db';
  if (a?.source === 'derived' || a?.source === 'inferred') return 'file';
  return 'custom';
};

// 物理表展示值：属性自身未显式标表时，回退到节点单一来源表（DB 抽取的表名挂在节点的
// derived_tables 上，而非逐属性记录），多表/无表则留空让用户手填。
const displayTable = (a: any, node: any): string => {
  if (a?.table) return String(a.table);
  if (sourceMethodOf(a) !== 'db') return '';
  const tables = node?.derived_tables || [];
  return tables.length === 1 ? String(tables[0]) : '';
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

const updateAttribute = (i: number, field: 'name' | 'valueSpace' | 'table' | 'column' | 'sourceMethod', value: string) => {
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
                  <table class="ni-kv">
                    <tbody>
                      <tr><td class="ni-kv-k">ID</td><td class="ni-kv-v mono">{{ node.id }}</td></tr>
                      <tr><td class="ni-kv-k">名称</td><td class="ni-kv-v strong">{{ node.label }}</td></tr>
                      <tr><td class="ni-kv-k">类型</td><td class="ni-kv-v"><span class="ni-chip">{{ t?.label || '—' }}</span></td></tr>
                      <tr><td class="ni-kv-k">来源</td><td class="ni-kv-v"><span class="ni-badge" :style="{color: sourceBadge(node.source).color, background: sourceBadge(node.source).bg}">{{ sourceBadge(node.source).text }}</span></td></tr>
                      <tr><td class="ni-kv-k">状态</td><td class="ni-kv-v"><span class="ni-chip ok">● 已激活</span></td></tr>
                    </tbody>
                  </table>
                </div>
                <div v-if="node.derived_source || node.derived_database || (node.derived_tables || []).length" class="ni-card ni-card-full">
                  <div class="ni-card-title">数据来源血缘</div>
                  <table class="ni-kv">
                    <tbody>
                      <tr :class="{ missing: !node.derived_source }">
                        <td class="ni-kv-k">数据源</td>
                        <td class="ni-kv-v">
                          <span v-if="node.derived_source" class="ni-src-val">{{ node.derived_source }}</span>
                          <span v-else class="ni-kv-empty">未关联</span>
                        </td>
                      </tr>
                      <tr :class="{ missing: !node.derived_database }">
                        <td class="ni-kv-k">数据库</td>
                        <td class="ni-kv-v">
                          <span v-if="node.derived_database" class="ni-src-val mono">{{ node.derived_database }}</span>
                          <span v-else class="ni-kv-empty">未关联</span>
                        </td>
                      </tr>
                      <tr :class="{ missing: !(node.derived_tables || []).length }">
                        <td class="ni-kv-k">来源表 <span v-if="(node.derived_tables || []).length" class="ni-kv-count">{{ node.derived_tables.length }}</span></td>
                        <td class="ni-kv-v">
                          <span v-for="(tb, i) in (node.derived_tables || [])" :key="'dt'+i" class="ni-src-chip">{{ tb }}</span>
                          <span v-if="!(node.derived_tables || []).length" class="ni-kv-empty">未关联</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>
              <template v-if="tab === 1">
                <!-- TBox 属性: 类节点上的属性定义 -->
                <div class="ni-card ni-card-full">
                  <div class="ni-card-head">
                    <div class="ni-card-title">本体属性 <span class="ni-card-count">{{ (node.attributes || []).length }}</span></div>
                    <button class="ni-prop-add ni-prop-add--head" @click="addAttribute">+ 新增属性</button>
                  </div>
                  <div v-if="(node.attributes || []).length" class="ni-attr-table ni-attr-table--onto">
                    <div class="ni-attr-thead">
                      <div class="ni-attr-th ni-col-name">名称</div>
                      <div class="ni-attr-th ni-col-type">类型</div>
                      <div class="ni-attr-th ni-col-method">来源方式</div>
                      <div class="ni-attr-th ni-col-ptable">物理表</div>
                      <div class="ni-attr-th ni-col-pcol">物理字段</div>
                      <div class="ni-attr-th ni-col-act"></div>
                    </div>
                    <div v-for="(a, i) in (node.attributes || [])" :key="'tba'+i" class="ni-attr-row">
                      <div class="ni-attr-cell ni-col-name">
                        <input class="ni-inline-input" :value="a.name" placeholder="属性名" @change="(e: any) => updateAttribute(i, 'name', e.target.value)" />
                      </div>
                      <div class="ni-attr-cell ni-col-type">
                        <input class="ni-inline-input mono" :value="a.valueSpace" placeholder="类型" @change="(e: any) => updateAttribute(i, 'valueSpace', e.target.value)" />
                      </div>
                      <div class="ni-attr-cell ni-col-method">
                        <select class="ni-inline-select" :value="sourceMethodOf(a)" @change="(e: any) => updateAttribute(i, 'sourceMethod', e.target.value)">
                          <option value="db">数据库提取</option>
                          <option value="file">文件提取</option>
                          <option value="custom">自定义</option>
                        </select>
                      </div>
                      <div class="ni-attr-cell ni-col-ptable">
                        <input class="ni-inline-input mono" :value="displayTable(a, node)" placeholder="物理表" @change="(e: any) => updateAttribute(i, 'table', e.target.value)" />
                      </div>
                      <div class="ni-attr-cell ni-col-pcol">
                        <input class="ni-inline-input mono" :value="a.column" placeholder="物理字段" @change="(e: any) => updateAttribute(i, 'column', e.target.value)" />
                      </div>
                      <div class="ni-attr-cell ni-col-act">
                        <button class="ni-prop-del" @click="removeAttribute(i)" title="删除">✕</button>
                      </div>
                    </div>
                  </div>
                  <div v-else class="ni-empty">暂无本体属性</div>
                </div>

                <!-- 自定义 K-V 属性: 用户在此处编辑 -->
                <div class="ni-card ni-card-full">
                  <div class="ni-card-head">
                    <div class="ni-card-title">自定义属性 <span class="ni-card-count">{{ editableProps.length }}</span></div>
                    <div class="ni-card-head-actions">
                      <button v-if="propsChanged" class="ni-prop-save" @click="saveProps">保存属性</button>
                      <button class="ni-prop-add ni-prop-add--head" @click="addEditableProp">+ 新增属性</button>
                    </div>
                  </div>
                  <div v-if="editableProps.length" class="ni-attr-table ni-attr-table--kv">
                    <div class="ni-attr-thead">
                      <div class="ni-attr-th ni-col-name">键</div>
                      <div class="ni-attr-th ni-col-type">值</div>
                      <div class="ni-attr-th ni-col-source">来源</div>
                      <div class="ni-attr-th ni-col-act"></div>
                    </div>
                    <div v-for="(p, i) in editableProps" :key="i" class="ni-attr-row">
                      <div class="ni-attr-cell ni-col-name">
                        <input v-model="p.key" class="ni-inline-input" placeholder="键" />
                      </div>
                      <div class="ni-attr-cell ni-col-type">
                        <input v-model="p.value" class="ni-inline-input" placeholder="值" />
                      </div>
                      <div class="ni-attr-cell ni-col-source">
                        <span class="ni-badge" :style="{color: sourceBadge(p.source).color, background: sourceBadge(p.source).bg}">{{ sourceBadge(p.source).text }}</span>
                      </div>
                      <div class="ni-attr-cell ni-col-act">
                        <button class="ni-prop-del" @click="removeEditableProp(i)" title="删除">✕</button>
                      </div>
                    </div>
                  </div>
                  <div v-else class="ni-empty">暂无自定义属性</div>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-card ni-card-full">
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
                <div class="ni-card ni-card-full">
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

                <div v-if="relatedEdgeConstraints.length > 0" class="ni-card ni-card-full">
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
                  <table class="ni-kv">
                    <tbody>
                      <tr><td class="ni-kv-k">总节点数</td><td class="ni-kv-v strong">{{ nodes.length }} <span class="ni-unit">实体</span></td></tr>
                      <tr><td class="ni-kv-k">总关系数</td><td class="ni-kv-v strong">{{ edges.length }} <span class="ni-unit">流向</span></td></tr>
                      <tr><td class="ni-kv-k">推演引擎</td><td class="ni-kv-v"><span class="ni-chip ok">● 实时就绪</span></td></tr>
                    </tbody>
                  </table>
                </div>
              </template>
              <template v-if="tab === 1">
                <div class="ni-card">
                  <div class="ni-card-title">模型属性概要</div>
                  <table class="ni-kv">
                    <tbody>
                      <tr><td class="ni-kv-k">节点总数</td><td class="ni-kv-v strong">{{ nodes.length }}</td></tr>
                      <tr><td class="ni-kv-k">关系总数</td><td class="ni-kv-v strong">{{ edges.length }}</td></tr>
                    </tbody>
                  </table>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-card ni-card-full">
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
                  <table class="ni-kv">
                    <tbody>
                      <tr><td class="ni-kv-k">带约束的节点</td><td class="ni-kv-v strong">{{ nodes.filter(n => (n.constraints?.length || 0) > 0).length }} <span class="ni-unit">个</span></td></tr>
                      <tr><td class="ni-kv-k">带约束的关系</td><td class="ni-kv-v strong">{{ edges.filter(e => (e.constraints?.length || 0) > 0).length }} <span class="ni-unit">条</span></td></tr>
                    </tbody>
                  </table>
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
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #22dd88;
  background: rgba(34, 221, 136, 0.12);
  border-radius: 6px;
}

/* ===== 本体属性表格 ===== */
.ni-attr-table {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px;
  overflow: hidden;
}
.ni-attr-thead,
.ni-attr-row {
  display: grid;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
}
/* 本体属性表：名称 / 类型 / 来源方式 / 物理表 / 物理字段 / 操作 */
.ni-attr-table--onto .ni-attr-thead,
.ni-attr-table--onto .ni-attr-row {
  grid-template-columns: minmax(96px, 1.2fr) minmax(72px, 0.8fr) minmax(94px, 0.9fr) minmax(90px, 1fr) minmax(90px, 1fr) 32px;
}
/* 自定义属性表：键 / 值 / 来源 / 操作 */
.ni-attr-table--kv .ni-attr-thead,
.ni-attr-table--kv .ni-attr-row {
  grid-template-columns: minmax(120px, 1fr) minmax(160px, 1.6fr) auto 32px;
}
.ni-attr-thead {
  background: rgba(255,255,255,0.025);
  border-bottom: 1px solid rgba(255,255,255,0.06);
  padding: 6px 10px;
}
.ni-attr-th {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: rgba(255,255,255,0.45);
}
.ni-attr-row {
  border-bottom: 1px dashed rgba(255,255,255,0.05);
}
.ni-attr-row:last-child { border-bottom: none; }
.ni-attr-row:hover { background: rgba(255,255,255,0.02); }
.ni-attr-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ni-col-act { justify-content: flex-end; }
/* 单元格内的输入/下拉占满列宽 */
.ni-col-method .ni-inline-select { width: 100%; }

/* 分区标题栏：标题在左，操作按钮（新增/保存）靠右上 */
.ni-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 9px;
  margin-bottom: 2px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.ni-card-head .ni-card-title {
  padding-bottom: 0;
  margin-bottom: 0;
  border-bottom: none;
}
.ni-card-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
/* 标题栏里的"新增属性"按钮：不再拉伸占整行 */
.ni-prop-add--head {
  flex: none;
  padding: 4px 12px;
  white-space: nowrap;
}

/* 紧凑面板下让属性表自适应竖排，避免水平挤压 */
@media (max-width: 720px) {
  .ni-attr-thead { display: none; }
  /* 本体属性表竖排 */
  .ni-attr-table--onto .ni-attr-row {
    grid-template-columns: 1fr 32px;
    grid-template-areas:
      'name   act'
      'type   act'
      'method act'
      'ptable act'
      'pcol   act';
    row-gap: 6px;
  }
  .ni-attr-table--onto .ni-col-name { grid-area: name; }
  .ni-attr-table--onto .ni-col-type { grid-area: type; }
  .ni-attr-table--onto .ni-col-method { grid-area: method; }
  .ni-attr-table--onto .ni-col-ptable { grid-area: ptable; }
  .ni-attr-table--onto .ni-col-pcol { grid-area: pcol; }
  .ni-attr-table--onto .ni-col-act { grid-area: act; align-self: start; }
  /* 自定义属性表竖排 */
  .ni-attr-table--kv .ni-attr-row {
    grid-template-columns: 1fr 32px;
    grid-template-areas:
      'name act'
      'type act'
      'src  act';
    row-gap: 6px;
  }
  .ni-attr-table--kv .ni-col-name { grid-area: name; }
  .ni-attr-table--kv .ni-col-type { grid-area: type; }
  .ni-attr-table--kv .ni-col-source { grid-area: src; }
  .ni-attr-table--kv .ni-col-act { grid-area: act; align-self: start; }
}
</style>
