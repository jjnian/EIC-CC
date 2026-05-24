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
const relatedEdgeConstraints = computed(() => {
  const out: { c: any; relLabel: string }[] = [];
  if (!props.node) return out;
  const nm = nmap.value;
  for (const e of props.edges) {
    if (e.from !== props.node.id && e.to !== props.node.id) continue;
    for (const c of (e.constraints || [])) {
      const fromL = nm[e.from]?.label || e.from;
      const toL = nm[e.to]?.label || e.to;
      out.push({ c, relLabel: `${fromL} —${e.label || ''}→ ${toL}` });
    }
  }
  return out;
});

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

// 属性编辑状态
const editableProps = ref<{ key: string; value: any; source?: string }[]>([]);

watch(() => props.node, (n) => {
  editableProps.value = n?.props ? n.props.map((p: any) => ({ ...p })) : [];
}, { immediate: true });

const propsChanged = computed(() => {
  return JSON.stringify(editableProps.value) !== JSON.stringify(props.node?.props || []);
});

const addEditableProp = () => {
  editableProps.value.push({ key: '', value: '' });
};

const removeEditableProp = (i: number) => {
  editableProps.value.splice(i, 1);
};

const saveProps = () => {
  if (!props.node) return;
  const cleaned = editableProps.value.filter(p => p.key.trim());
  emit('update-node-props', props.node.id, cleaned);
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
        <!-- Removed ni-list completely as requested -->
        <div class="ni-right" style="flex: 1; display: flex; flex-direction: column;">
          <div class="ni-header" style="display: flex; align-items: center; justify-content: space-between; padding: 0 16px; border-bottom: 1px solid var(--glass-border); flex-shrink: 0; background: rgba(255,255,255,0.02)">
            <div class="ni-tabs" style="border: none; padding: 8px 0;">
              <button v-for="(lb, i) in ['概览', '属性', '关系', '约束']" :key="i" :class="['ni-tab', { on: tab === i }]" @click="tab = i">{{ lb }}</button>
            </div>
            <div style="display: flex; align-items: center; gap: 12px;">
              <span v-if="node" class="ni-list-title" style="font-size: 13px; color: var(--accent);">当前选中: {{ node.label }}</span>
              <span v-else class="ni-list-title" style="font-size: 13px; color: var(--accent);">当前视图: 全局模型</span>
              <button class="ni-list-close" @click="emit('close')" style="background:transparent; border:none; color:var(--text-main); cursor:pointer; font-size:18px;">×</button>
            </div>
          </div>

          <div class="ni-body" style="padding: 16px; flex: 1; overflow-y: auto;">
            <!-- Node Context -->
            <template v-if="node">
              <template v-if="tab === 0">
                <div class="ni-section">
                  <div class="ni-section-title">节点详情</div>
                  <div class="ni-row"><div class="ni-key">ID</div><div class="ni-val">{{ node.id }}</div></div>
                  <div class="ni-row"><div class="ni-key">名称</div><div class="ni-val">{{ node.label }}</div></div>
                  <div class="ni-row"><div class="ni-key">类型</div><div class="ni-val">{{ t?.label || '—' }}</div></div>
                  <div class="ni-row"><div class="ni-key">来源</div>
                    <div class="ni-val">
                      <span v-if="node.source === 'inferred'" style="color:#bb77ff; background:rgba(187,119,255,0.1); padding:2px 6px; border-radius:4px; font-size:11px;">AI 推理展开</span>
                      <span v-else-if="node.source === 'derived'" style="color:#22dd88; background:rgba(34,221,136,0.1); padding:2px 6px; border-radius:4px; font-size:11px;">文本提取</span>
                      <span v-else>系统预置</span>
                    </div>
                  </div>
                  <div class="ni-row"><div class="ni-key">状态</div><div class="ni-val green">● 已激活</div></div>
                </div>

                <!-- TBox 属性: 这个类节点上挂的属性定义 -->
                <div class="ni-section" v-if="(node.attributes?.length || 0) > 0">
                  <div class="ni-section-title">属性 ({{ node.attributes.length }})</div>
                  <div v-for="(a, i) in node.attributes" :key="'a'+i" class="ni-row">
                    <div class="ni-key" style="color:#ffaa22">{{ a.name }}</div>
                    <div class="ni-val" style="font-family:'JetBrains Mono',monospace; font-size:12px;">{{ a.valueSpace || '—' }}</div>
                  </div>
                </div>

                <!-- 概览中简要显示约束数量，详细内容在约束 Tab -->
                <div class="ni-section" v-if="(node.constraints?.length || 0) > 0 || relatedEdgeConstraints.length > 0">
                  <div class="ni-section-title">约束</div>
                  <div class="ni-row">
                    <div class="ni-key">约束总计</div>
                    <div class="ni-val" style="color:#ff7fbe">
                      🔒 {{ (node.constraints?.length || 0) + relatedEdgeConstraints.length }} 条
                      <span style="color:rgba(255,255,255,0.4); font-size:11px; margin-left:8px">详见「约束」标签页</span>
                    </div>
                  </div>
                </div>
              </template>
              <template v-if="tab === 1">
                <!-- 属性 Tab 可编辑 -->
                <div class="ni-prop-list">
                  <div class="ni-section-title">自定义属性 ({{ editableProps.length }})</div>
                  <div v-for="(p, i) in editableProps" :key="i" class="ni-prop-row">
                    <input v-model="p.key" class="ni-prop-key-input" placeholder="键" />
                    <input v-model="p.value" class="ni-prop-val-input" placeholder="值" />
                    <button class="ni-prop-del" @click="removeEditableProp(i)" title="删除">✕</button>
                  </div>
                  <div v-if="editableProps.length === 0" style="color: rgba(255,255,255,0.4); font-size: 12px; padding: 8px 0;">
                    暂无属性
                  </div>
                  <button class="ni-prop-add" @click="addEditableProp">+ 新增属性</button>
                  <button v-if="propsChanged" class="ni-prop-save" @click="saveProps">保存属性</button>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-section" style="flex:1">
                  <div class="ni-section-title">节点关系 ({{ outgoing.length + incoming.length }})</div>
                  <table v-if="outgoing.length || incoming.length" class="ni-rel-table">
                    <thead>
                      <tr>
                        <th class="ni-th">方向</th>
                        <th class="ni-th">关系名称</th>
                        <th class="ni-th">来源</th>
                        <th class="ni-th">目标节点</th>
                        <th class="ni-th" style="width:32px"></th>
                      </tr>
                    </thead>
                    <tbody>
                      <template v-for="e in outgoing" :key="e.id">
                        <tr v-if="nmap[e.to]" class="ni-tr">
                          <td class="ni-td">
                            <span style="color:#3d9bff">→ 输出</span>
                            <span v-if="e.rule_driven" style="font-size:9px; color:#ff3399; margin-left:4px">⚡</span>
                            <span v-if="(e.constraints?.length || 0) > 0" class="ni-lock-inline" :title="(e.constraints || []).map((c: any) => kindLabel(c.kind) + ': ' + c.note).join('\n')">🔒</span>
                          </td>
                          <td class="ni-td" style="color:#ffaa22; font-weight:500">{{ e.label || '(未命名)' }}</td>
                          <td class="ni-td">
                            <span v-if="e.source === 'inferred'" style="color:#bb77ff; font-size:11px">AI推理</span>
                            <span v-else-if="e.source === 'derived'" style="color:#22dd88; font-size:11px">文本提取</span>
                            <span v-else style="color:rgba(255,255,255,0.4); font-size:11px">预置</span>
                          </td>
                          <td class="ni-td">{{ nmap[e.to].label }}</td>
                          <td class="ni-td"><button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button></td>
                        </tr>
                      </template>
                      <template v-for="e in incoming" :key="e.id">
                        <tr v-if="nmap[e.from]" class="ni-tr">
                          <td class="ni-td">
                            <span style="color:#22dd88">← 输入</span>
                            <span v-if="e.rule_driven" style="font-size:9px; color:#ff3399; margin-left:4px">⚡</span>
                            <span v-if="(e.constraints?.length || 0) > 0" class="ni-lock-inline" :title="(e.constraints || []).map((c: any) => kindLabel(c.kind) + ': ' + c.note).join('\n')">🔒</span>
                          </td>
                          <td class="ni-td" style="color:#ffaa22; font-weight:500">{{ e.label || '(未命名)' }}</td>
                          <td class="ni-td">
                            <span v-if="e.source === 'inferred'" style="color:#bb77ff; font-size:11px">AI推理</span>
                            <span v-else-if="e.source === 'derived'" style="color:#22dd88; font-size:11px">文本提取</span>
                            <span v-else style="color:rgba(255,255,255,0.4); font-size:11px">预置</span>
                          </td>
                          <td class="ni-td">{{ nmap[e.from].label }}</td>
                          <td class="ni-td"><button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button></td>
                        </tr>
                      </template>
                    </tbody>
                  </table>
                  <div v-if="!outgoing.length && !incoming.length" style="color:rgba(255,255,255,0.35);font-size:12px;padding-top:8px">暂无关系</div>
                </div>
              </template>
              <template v-if="tab === 3">
                <div class="ni-section" style="flex:1">
                  <div class="ni-section-title">节点约束 ({{ (node.constraints || []).length }})</div>
                  <div v-for="(c, i) in (node.constraints || [])" :key="'c'+i" class="ni-row">
                    <div class="ni-key" style="color:#ff7fbe">{{ kindLabel(c.kind) }}</div>
                    <div class="ni-val">{{ c.note }}</div>
                  </div>
                  <div v-if="!(node.constraints?.length)" style="color:rgba(255,255,255,0.35); font-size:12px; padding:8px 0;">暂无约束</div>

                  <div v-if="relatedEdgeConstraints.length > 0" style="margin-top:16px">
                    <div class="ni-section-title">所在关系的约束 ({{ relatedEdgeConstraints.length }})</div>
                    <div v-for="(row, i) in relatedEdgeConstraints" :key="'rec'+i" class="ni-row">
                      <div class="ni-key" style="color:#ff7fbe">{{ kindLabel(row.c.kind) }}</div>
                      <div class="ni-val">
                        <span style="color:rgba(255,255,255,0.55); font-size:11px">{{ row.relLabel }}</span>
                        <span style="display:block">{{ row.c.note }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </template>
            </template>

            <!-- Global Context -->
            <template v-else>
              <template v-if="tab === 0">
                <div class="ni-section">
                  <div class="ni-section-title">全局模型概览</div>
                  <div class="ni-row"><div class="ni-key">总节点数</div><div class="ni-val">{{ nodes.length }} 实体</div></div>
                  <div class="ni-row"><div class="ni-key">总关系数</div><div class="ni-val">{{ edges.length }} 流向</div></div>
                  <div class="ni-row"><div class="ni-key">推演引擎</div><div class="ni-val green">● 实时就绪</div></div>
                </div>
              </template>
              <template v-if="tab === 1">
                <div class="ni-section">
                  <div class="ni-section-title">模型属性概要</div>
                  <div class="ni-row"><div class="ni-key">节点总数</div><div class="ni-val">{{ nodes.length }}</div></div>
                  <div class="ni-row"><div class="ni-key">关系总数</div><div class="ni-val">{{ edges.length }}</div></div>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-section" style="flex:1">
                  <div class="ni-section-title">全局拓扑关系表 ({{ edges.length }})</div>
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
                          <td class="ni-td" style="color:#ffaa22; font-weight:500">{{ e.label || '(未命名)' }}</td>
                          <td class="ni-td">{{ nmap[e.from].label }}</td>
                          <td class="ni-td">{{ nmap[e.to].label }}</td>
                        </tr>
                      </template>
                    </tbody>
                  </table>
                  <div v-if="!edges.length" style="color:rgba(255,255,255,0.35);font-size:12px;padding-top:8px">暂无全局关系</div>
                </div>
              </template>
              <template v-if="tab === 3">
                <div class="ni-section">
                  <div class="ni-section-title">全局约束概要</div>
                  <div class="ni-row"><div class="ni-key">带约束的节点</div><div class="ni-val">{{ nodes.filter(n => (n.constraints?.length || 0) > 0).length }} 个</div></div>
                  <div class="ni-row"><div class="ni-key">带约束的关系</div><div class="ni-val">{{ edges.filter(e => (e.constraints?.length || 0) > 0).length }} 条</div></div>
                </div>
              </template>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
