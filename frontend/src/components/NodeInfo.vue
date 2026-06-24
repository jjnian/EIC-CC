<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { NT } from '../constants';
import type { AttrSourceMethod } from '../types';
import NodeDataBindingPanel from './NodeDataBindingPanel.vue';
import { useNodeEditDraft } from '../composables/useNodeEditDraft';
import { Button } from '@/components/ui/button';
import BaseSelect from './form/BaseSelect.vue';

const typeOptions = Object.entries(NT).map(([key, meta]) => ({ value: key, label: (meta as any).label }));
const sourceOptions = [
  { value: 'manual', label: '手动' },
  { value: 'derived', label: '文本提取' },
  { value: 'inferred', label: 'AI推理' },
  { value: 'preset', label: '预置' },
];
const methodOptions = [
  { value: 'db', label: '数据库提取' },
  { value: 'file', label: '文件提取' },
  { value: 'custom', label: '自定义' },
];
const constraintKindOptions = [
  { value: 'cardinality', label: '基数' },
  { value: 'exclusive', label: '互斥' },
  { value: 'symmetric', label: '对称' },
  { value: 'transitive', label: '传递' },
  { value: 'custom', label: '自定义' },
];

const props = defineProps<{
  node: any | null;
  nodes: any[];
  edges: any[];
  isOpen: boolean;
  /** 当前本体模型 id，用于节点数据供血绑定（为空表示模型未保存） */
  modelId?: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update-node-props', id: string, props: { key: string; value: any; source?: string }[]): void;
  (e: 'delete-edge', edgeId: string): void;
  (e: 'update-node-schema', id: string, patch: any): void;
  (e: 'edit-edge-relation', edgeId: string): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
  (e: 'select-node', id: string): void;
}>();

const tab = ref(0);
const height = ref(250);
const dragging = ref(false);

const t = computed(() => props.node ? (NT as any)[props.node.type] || NT.class : null);
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

const outgoing = computed(() => props.node ? props.edges.filter(e => e.from === props.node.id) : []);
const incoming = computed(() => props.node ? props.edges.filter(e => e.to === props.node.id) : []);

// 左栏「关联节点」：出边 + 入边汇成一张连接列表，带方向 / 关系名 / 类型，点击可跳转选中
const connectedNodes = computed(() => {
  if (!props.node) return [] as { id: string; label: string; type: string; rel: string; dir: 'out' | 'in' }[];
  const nm = nmap.value;
  const out = outgoing.value
    .map(e => { const tn = nm[e.to]; return tn ? { id: tn.id, label: tn.label, type: tn.type, rel: e.label || '', dir: 'out' as const } : null; })
    .filter(Boolean) as { id: string; label: string; type: string; rel: string; dir: 'out' | 'in' }[];
  const inc = incoming.value
    .map(e => { const fn = nm[e.from]; return fn ? { id: fn.id, label: fn.label, type: fn.type, rel: e.label || '', dir: 'in' as const } : null; })
    .filter(Boolean) as { id: string; label: string; type: string; rel: string; dir: 'out' | 'in' }[];
  return [...out, ...inc];
});

const typeMeta = (type: string) => (NT as any)[type] || NT.class;

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

const removeRelatedEdgeConstraintLive = (edgeId: string, index: number) => {
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

// 置信度配色 / 提示：高=绿、中=黄、低=红，帮助快速判断血缘可信度
const confColor = (c: number) => (c >= 0.85 ? '#22dd88' : c >= 0.55 ? '#ffcc44' : '#ff7755');
const confHint = (c: number) => (c >= 0.85 ? '高（事实依据充分）' : c >= 0.55 ? '中（部分推断）' : '低（多为推断，需复核）');

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

// ===== 编辑模式 / 草稿（抽到 useNodeEditDraft）=====
const {
  editMode, draft, isEditing,
  startEdit, cancelEdit, saveEdit,
  dAddAttribute, dRemoveAttribute, dUpdateAttribute,
  dAddConstraint, dRemoveConstraint, dUpdateConstraint,
  dAddTable, dRemoveTable, dUpdateTable,
} = useNodeEditDraft({
  node: () => props.node,
  edges: () => props.edges,
  tab,
  updateNode: (id, patch) => emit('update-node-schema', id, patch),
  updateEdge: (id, patch) => emit('update-edge-schema', id, patch),
});

// 切换选中节点时,折叠掉之前展开的关系约束（编辑态由 useNodeEditDraft 自行复位）
watch(() => props.node?.id, () => {
  expandedEdges.value = new Set();
});

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
        <!-- 左栏：关联节点列表（仅选中具体节点时显示） -->
        <div v-if="node" class="ni-list">
          <div class="ni-list-head">
            <span class="ni-list-dot" :style="{ background: t?.color || 'var(--accent)', color: t?.color || 'var(--accent)' }" />
            <span class="ni-list-title">{{ node.label }}</span>
            <span class="ni-list-count">{{ connectedNodes.length }}</span>
          </div>
          <div class="ni-list-body">
            <div v-if="connectedNodes.length === 0" class="ni-list-empty">暂无关联节点</div>
            <button
              v-for="(cn, i) in connectedNodes"
              :key="cn.dir + cn.id + i"
              class="ni-list-item"
              :title="(cn.dir === 'out' ? '→ ' : '← ') + (cn.rel || '关联') + ' · ' + cn.label"
              @click="emit('select-node', cn.id)"
            >
              <span class="ni-list-item-dot" :style="{ background: typeMeta(cn.type).color }" />
              <span class="ni-list-item-info">
                <span class="ni-list-item-label">{{ cn.label }}</span>
                <span class="ni-list-item-sub">{{ cn.dir === 'out' ? '→ ' + (cn.rel || '关联') : '← ' + (cn.rel || '关联') }} · {{ typeMeta(cn.type).label }}</span>
              </span>
              <span class="ni-list-item-arrow">{{ cn.dir === 'out' ? '▶' : '◀' }}</span>
            </button>
          </div>
        </div>
        <div class="ni-right">
          <div class="ni-header">
            <div class="ni-header-l">
              <div class="ni-tabs">
                <button v-for="(lb, i) in ['概览', '属性', '关系', '约束', '供血']" :key="i" :class="['ni-tab', { on: tab === i }]" @click="tab = i">{{ lb }}</button>
              </div>
            </div>
            <div class="ni-header-r">
              <span v-if="node" class="ni-current">
                <span class="ni-current-dot" :style="t ? { background: t.color || '#2f86d6' } : {}" />
                <span class="ni-current-lb">{{ node.label }}</span>
              </span>
              <span v-else class="ni-current ni-current-global">
                <span class="ni-current-dot" />
                <span class="ni-current-lb">全局模型</span>
              </span>
              <!-- 编辑模式按钮组：仅在选中节点时显示 -->
              <template v-if="node">
                <template v-if="isEditing">
                  <Button variant="ghost" size="sm" @click="cancelEdit" title="放弃修改">取消</Button>
                  <Button size="sm" @click="saveEdit" title="保存修改">保存</Button>
                </template>
                <Button v-else variant="outline" size="sm" @click="startEdit" title="进入编辑模式">✎ 编辑</Button>
              </template>
              <Button variant="ghost" size="icon-sm" @click="emit('close')" title="关闭">×</Button>
            </div>
          </div>

          <div class="ni-body">
            <!-- Node Context -->
            <template v-if="node">
              <!-- ===== 概览 ===== -->
              <template v-if="tab === 0">
                <div class="ni-card">
                  <div class="ni-card-title">节点详情</div>
                  <table class="ni-kv">
                    <tbody>
                      <tr><td class="ni-kv-k">ID</td><td class="ni-kv-v mono">{{ node.id }}</td></tr>
                      <tr>
                        <td class="ni-kv-k">名称</td>
                        <td class="ni-kv-v">
                          <input v-if="isEditing" class="ni-inline-input" v-model="draft.label" placeholder="节点名称" />
                          <span v-else class="ni-kv-v strong">{{ node.label }}</span>
                        </td>
                      </tr>
                      <tr>
                        <td class="ni-kv-k">类型</td>
                        <td class="ni-kv-v">
                          <BaseSelect v-if="isEditing" size="sm" v-model="draft.type" :options="typeOptions" />
                          <span v-else class="ni-chip">{{ t?.label || '—' }}</span>
                        </td>
                      </tr>
                      <tr>
                        <td class="ni-kv-k">来源</td>
                        <td class="ni-kv-v">
                          <BaseSelect v-if="isEditing" size="sm" v-model="draft.source" :options="sourceOptions" />
                          <span v-else class="ni-badge" :style="{color: sourceBadge(node.source).color, background: sourceBadge(node.source).bg}">{{ sourceBadge(node.source).text }}</span>
                        </td>
                      </tr>
                      <tr v-if="!isEditing && node.confidence != null">
                        <td class="ni-kv-k">置信度</td>
                        <td class="ni-kv-v">
                          <span class="ni-conf" :style="{ color: confColor(node.confidence) }">{{ (node.confidence * 100).toFixed(0) }}%</span>
                          <span class="ni-conf-hint">{{ confHint(node.confidence) }}</span>
                        </td>
                      </tr>
                      <tr><td class="ni-kv-k">状态</td><td class="ni-kv-v"><span class="ni-chip ok">● 已激活</span></td></tr>
                    </tbody>
                  </table>
                  <!-- 血缘证据：抽取该节点所依据的原文引文，可审计 -->
                  <div v-if="!isEditing && node.evidence" class="ni-evidence">
                    <span class="ni-evidence-icon" title="血缘证据">❝</span>
                    <span class="ni-evidence-text">{{ node.evidence }}</span>
                  </div>
                </div>
                <div v-if="isEditing || node.derived_source || node.derived_database || (node.derived_tables || []).length" class="ni-card ni-card-full">
                  <div class="ni-card-title">数据来源血缘</div>
                  <table class="ni-kv">
                    <tbody>
                      <tr :class="{ missing: !isEditing && !node.derived_source }">
                        <td class="ni-kv-k">数据源</td>
                        <td class="ni-kv-v">
                          <input v-if="isEditing" class="ni-inline-input" v-model="draft.derived_source" placeholder="数据源名称（如 pgsql）" />
                          <template v-else>
                            <span v-if="node.derived_source" class="ni-src-val">{{ node.derived_source }}</span>
                            <span v-else class="ni-kv-empty">未关联</span>
                          </template>
                        </td>
                      </tr>
                      <tr :class="{ missing: !isEditing && !node.derived_database }">
                        <td class="ni-kv-k">数据库</td>
                        <td class="ni-kv-v">
                          <input v-if="isEditing" class="ni-inline-input mono" v-model="draft.derived_database" placeholder="数据库名" />
                          <template v-else>
                            <span v-if="node.derived_database" class="ni-src-val mono">{{ node.derived_database }}</span>
                            <span v-else class="ni-kv-empty">未关联</span>
                          </template>
                        </td>
                      </tr>
                      <tr :class="{ missing: !isEditing && !(node.derived_tables || []).length }">
                        <td class="ni-kv-k">来源表 <span v-if="!isEditing && (node.derived_tables || []).length" class="ni-kv-count">{{ node.derived_tables.length }}</span></td>
                        <td class="ni-kv-v">
                          <template v-if="isEditing">
                            <div class="ni-table-list">
                              <div v-for="(tb, i) in draft.derived_tables" :key="'dt-edit'+i" class="ni-table-row">
                                <input class="ni-inline-input mono" :value="tb" placeholder="表名" @input="(ev: any) => dUpdateTable(i, ev.target.value)" />
                                <Button variant="ghost" size="icon-sm" class="text-destructive" @click="dRemoveTable(i)" title="删除">✕</Button>
                              </div>
                              <Button variant="outline" size="sm" @click="dAddTable">+ 新增表名</Button>
                            </div>
                          </template>
                          <template v-else>
                            <span v-for="(tb, i) in (node.derived_tables || [])" :key="'dt'+i" class="ni-src-chip">{{ tb }}</span>
                            <span v-if="!(node.derived_tables || []).length" class="ni-kv-empty">未关联</span>
                          </template>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>

              <!-- ===== 属性 ===== -->
              <template v-if="tab === 1">
                <!-- TBox 属性: 类节点上的属性定义 -->
                <div class="ni-card ni-card-full">
                  <div class="ni-card-head">
                    <div class="ni-card-title">本体属性 <span class="ni-card-count">{{ isEditing ? (draft.attributes || []).length : (node.attributes || []).length }}</span></div>
                    <Button v-if="isEditing" variant="outline" size="sm" @click="dAddAttribute">+ 新增属性</Button>
                  </div>
                  <!-- 编辑模式 -->
                  <template v-if="isEditing">
                    <div v-if="(draft.attributes || []).length" class="ni-attr-table ni-attr-table--onto">
                      <div class="ni-attr-thead">
                        <div class="ni-attr-th ni-col-name">名称</div>
                        <div class="ni-attr-th ni-col-type">类型</div>
                        <div class="ni-attr-th ni-col-method">来源方式</div>
                        <div class="ni-attr-th ni-col-ptable">物理表</div>
                        <div class="ni-attr-th ni-col-pcol">物理字段</div>
                        <div class="ni-attr-th ni-col-act"></div>
                      </div>
                      <div v-for="(a, i) in draft.attributes" :key="'tba-edit'+i" class="ni-attr-row">
                        <div class="ni-attr-cell ni-col-name">
                          <input class="ni-inline-input" :value="a.name" placeholder="属性名" @input="(e: any) => dUpdateAttribute(i, 'name', e.target.value)" />
                        </div>
                        <div class="ni-attr-cell ni-col-type">
                          <input class="ni-inline-input mono" :value="a.valueSpace" placeholder="类型" @input="(e: any) => dUpdateAttribute(i, 'valueSpace', e.target.value)" />
                        </div>
                        <div class="ni-attr-cell ni-col-method">
                          <BaseSelect size="sm" :model-value="sourceMethodOf(a)" :options="methodOptions" @update:model-value="dUpdateAttribute(i, 'sourceMethod', $event)" />
                        </div>
                        <div class="ni-attr-cell ni-col-ptable">
                          <input class="ni-inline-input mono" :value="displayTable(a, node)" placeholder="物理表" @input="(e: any) => dUpdateAttribute(i, 'table', e.target.value)" />
                        </div>
                        <div class="ni-attr-cell ni-col-pcol">
                          <input class="ni-inline-input mono" :value="a.column" placeholder="物理字段" @input="(e: any) => dUpdateAttribute(i, 'column', e.target.value)" />
                        </div>
                        <div class="ni-attr-cell ni-col-act">
                          <Button variant="ghost" size="icon-sm" class="text-destructive" @click="dRemoveAttribute(i)" title="删除">✕</Button>
                        </div>
                      </div>
                    </div>
                    <div v-else class="ni-empty">暂无本体属性，点击「+ 新增属性」开始添加</div>
                  </template>
                  <!-- 只读模式 -->
                  <template v-else>
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
                        <div class="ni-attr-cell ni-col-name"><span class="ni-attr-name">{{ a.name || '—' }}</span></div>
                        <div class="ni-attr-cell ni-col-type mono">{{ a.valueSpace || '—' }}</div>
                        <div class="ni-attr-cell ni-col-method">
                          <span class="ni-chip">{{ ({ db: '数据库提取', file: '文件提取', custom: '自定义' } as any)[sourceMethodOf(a)] }}</span>
                        </div>
                        <div class="ni-attr-cell ni-col-ptable mono">{{ displayTable(a, node) || '—' }}</div>
                        <div class="ni-attr-cell ni-col-pcol mono">{{ a.column || '—' }}</div>
                        <div class="ni-attr-cell ni-col-act"></div>
                      </div>
                    </div>
                    <div v-else class="ni-empty">暂无本体属性</div>
                  </template>
                </div>
              </template>

              <!-- ===== 关系 ===== -->
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
                          <td class="ni-td amber">
                            <input v-if="isEditing" class="ni-inline-input" :value="draft.labels[e.id]" placeholder="(未命名)" @input="(ev: any) => draft.labels[e.id] = ev.target.value" />
                            <span v-else>{{ e.label || '(未命名)' }}</span>
                          </td>
                          <td class="ni-td">
                            <span v-if="e.source === 'inferred'" class="ni-src purple">AI推理</span>
                            <span v-else-if="e.source === 'derived'" class="ni-src green">文本提取</span>
                            <span v-else class="ni-src dim">预置</span>
                          </td>
                          <td class="ni-td">{{ nmap[e.to].label }}</td>
                          <td class="ni-td ni-td-actions">
                            <Button variant="ghost" size="icon-sm" @click="emit('edit-edge-relation', e.id)" title="编辑关系">✎</Button>
                            <Button variant="ghost" size="icon-sm" class="text-destructive" @click="emit('delete-edge', e.id)" title="删除关系">✕</Button>
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
                          <td class="ni-td amber">
                            <input v-if="isEditing" class="ni-inline-input" :value="draft.labels[e.id]" placeholder="(未命名)" @input="(ev: any) => draft.labels[e.id] = ev.target.value" />
                            <span v-else>{{ e.label || '(未命名)' }}</span>
                          </td>
                          <td class="ni-td">
                            <span v-if="e.source === 'inferred'" class="ni-src purple">AI推理</span>
                            <span v-else-if="e.source === 'derived'" class="ni-src green">文本提取</span>
                            <span v-else class="ni-src dim">预置</span>
                          </td>
                          <td class="ni-td">{{ nmap[e.from].label }}</td>
                          <td class="ni-td ni-td-actions">
                            <Button variant="ghost" size="icon-sm" @click="emit('edit-edge-relation', e.id)" title="编辑关系">✎</Button>
                            <Button variant="ghost" size="icon-sm" class="text-destructive" @click="emit('delete-edge', e.id)" title="删除关系">✕</Button>
                          </td>
                        </tr>
                      </template>
                    </tbody>
                  </table>
                  <div v-if="!outgoing.length && !incoming.length" class="ni-empty">暂无关系</div>
                  <div v-if="isEditing" class="ni-edit-hint">编辑模式下可修改关系名称，「✎/✕」可即时跳转或删除。保存只提交名称变更。</div>
                </div>
              </template>

              <!-- ===== 约束 ===== -->
              <template v-if="tab === 3">
                <div class="ni-card ni-card-full">
                  <div class="ni-card-head">
                    <div class="ni-card-title">节点约束 <span class="ni-card-count">{{ isEditing ? (draft.constraints || []).length : (node.constraints || []).length }}</span></div>
                    <Button v-if="isEditing" variant="outline" size="sm" @click="dAddConstraint">+ 新增约束</Button>
                  </div>
                  <!-- 编辑模式 -->
                  <template v-if="isEditing">
                    <div v-if="(draft.constraints || []).length" class="ni-cons-list">
                      <div v-for="(c, i) in draft.constraints" :key="'c-edit'+i" class="ni-cons-item">
                        <div class="ni-cons-head">
                          <div class="ni-kind-sel"><BaseSelect size="sm" :model-value="c.kind || 'custom'" :options="constraintKindOptions" @update:model-value="dUpdateConstraint(i, 'kind', $event)" /></div>
                          <span class="ni-badge" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
                          <Button variant="ghost" size="icon-sm" class="text-destructive" @click="dRemoveConstraint(i)" title="删除">✕</Button>
                        </div>
                        <input class="ni-inline-input" :value="c.note" placeholder="约束说明" @input="(e: any) => dUpdateConstraint(i, 'note', e.target.value)" />
                      </div>
                    </div>
                    <div v-else class="ni-empty">暂无节点约束，点击「+ 新增约束」开始添加</div>
                  </template>
                  <!-- 只读模式 -->
                  <template v-else>
                    <div v-if="(node.constraints?.length || 0) > 0" class="ni-cons-list">
                      <div v-for="(c, i) in (node.constraints || [])" :key="'c'+i" class="ni-cons-item">
                        <div class="ni-cons-head">
                          <span class="ni-cons-kind">{{ kindLabel(c.kind) }}</span>
                          <span class="ni-badge" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
                        </div>
                        <div class="ni-cons-note">{{ c.note || '—' }}</div>
                      </div>
                    </div>
                    <div v-else class="ni-empty">暂无节点约束</div>
                  </template>
                </div>

                <div v-if="!isEditing && relatedEdgeConstraints.length > 0" class="ni-card ni-card-full">
                  <div class="ni-card-title">所在关系的约束 <span class="ni-card-count">{{ relatedEdgeConstraints.length }}</span></div>
                  <div class="ni-cons-list">
                    <div v-for="(row, i) in relatedEdgeConstraints" :key="'rec'+i" class="ni-cons-item">
                      <div class="ni-cons-head">
                        <span class="ni-cons-kind">{{ kindLabel(row.c.kind) }}</span>
                        <span class="ni-badge" :style="{color: sourceBadge(row.c.source).color, background: sourceBadge(row.c.source).bg}">{{ sourceBadge(row.c.source).text }}</span>
                        <span class="ni-cons-rel">{{ row.relLabel }}</span>
                        <Button variant="ghost" size="icon-sm" class="text-destructive" style="margin-left:auto" @click="removeRelatedEdgeConstraintLive(row.edgeId, row.index)" title="删除该约束">✕</Button>
                      </div>
                      <div class="ni-cons-note">{{ row.c.note }}</div>
                    </div>
                  </div>
                </div>
              </template>

              <!-- ===== 供血（数据源绑定 + 取数） ===== -->
              <template v-if="tab === 4">
                <div class="ni-card ni-card-full">
                  <NodeDataBindingPanel :model-id="modelId" :node-id="node.id" :node-label="node.label" />
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
/* ===== 置信度 / 血缘证据 ===== */
.ni-conf { font-weight: 700; font-family: 'JetBrains Mono', monospace; }
.ni-conf-hint { margin-left: 8px; font-size: 11px; color: rgba(255,255,255,0.45); }
.ni-evidence {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: rgba(255,255,255,0.78);
  background: rgba(34,221,136,0.06);
  border-left: 2px solid rgba(34,221,136,0.5);
  border-radius: 4px;
}
.ni-evidence-icon { color: #22dd88; font-weight: 700; flex-shrink: 0; }
.ni-evidence-text { font-style: italic; }

.ni-src-chip {
  display: inline-block;
  padding: 2px 8px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  color: #22dd88;
  background: rgba(34, 221, 136, 0.12);
  border-radius: 6px;
}

/* ===== 编辑模式按钮 ===== */
.ni-edit-btn {
  padding: 4px 12px;
  font-size: 12px;
  border-radius: 6px;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.12);
  color: var(--text-main);
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  transition: background-color .15s, border-color .15s, color .15s;
}
.ni-edit-btn:hover {
  background: rgba(255,255,255,0.1);
  border-color: rgba(255,255,255,0.22);
}
.ni-edit-btn--save {
  background: var(--accent, #2f86d6);
  border-color: var(--accent, #2f86d6);
  color: #fff;
  font-weight: 600;
}
.ni-edit-btn--save:hover {
  opacity: 0.9;
  background: var(--accent, #2f86d6);
}
.ni-edit-btn--cancel {
  color: rgba(255,255,255,0.7);
}

/* ===== 编辑模式提示 ===== */
.ni-edit-hint {
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 11px;
  color: rgba(255,255,255,0.5);
  background: rgba(255,255,255,0.03);
  border: 1px dashed rgba(255,255,255,0.1);
  border-radius: 6px;
}

/* ===== 来源表草稿列表 ===== */
.ni-table-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ni-table-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ni-table-row .ni-inline-input { flex: 1; }

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
  font-size: 12px;
  color: var(--text-main);
}
.ni-attr-cell.mono { font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 11px; color: var(--text-dim); }
.ni-attr-cell .ni-attr-name { color: #ffaa22; font-weight: 500; }
.ni-col-act { justify-content: flex-end; }
/* 单元格内的输入/下拉占满列宽 */
.ni-col-method .ni-inline-select { width: 100%; }
.ni-kind-sel { width: 104px; flex-shrink: 0; }
.ni-cons-head { display: flex; align-items: center; gap: 8px; }

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
