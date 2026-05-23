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
              <button v-for="(lb, i) in ['概览', '关系', '属性', 'Schema']" :key="i" :class="['ni-tab', { on: tab === i }]" @click="tab = i">{{ lb }}</button>
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
              </template>
              <template v-if="tab === 1">
                <div class="ni-section" style="flex:1">
                  <div class="ni-section-title">节点关系 ({{ outgoing.length + incoming.length }})</div>
                  <div v-for="e in outgoing" :key="e.id">
                    <div v-if="nmap[e.to]" class="ni-row">
                      <div class="ni-key" style="color:#3d9bff; display:flex; flex-direction:column; gap:2px;">
                        <span>→ 输出</span>
                        <span v-if="e.rule_driven" style="font-size:9px; color:#ff3399">⚡规则驱动</span>
                      </div>
                      <div class="ni-val">
                        <span style="color:#ffaa22">{{ e.label }}</span>
                        <span v-if="e.source === 'inferred'" style="font-size:10px; color:#bb77ff; margin-left:4px;">(AI推理)</span>
                        <span style="color:#253a52"> → </span>{{ nmap[e.to].label }}
                      </div>
                      <button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button>
                    </div>
                  </div>
                  <div v-for="e in incoming" :key="e.id">
                    <div v-if="nmap[e.from]" class="ni-row">
                      <div class="ni-key" style="color:#22dd88; display:flex; flex-direction:column; gap:2px;">
                        <span>← 输入</span>
                        <span v-if="e.rule_driven" style="font-size:9px; color:#ff3399">⚡规则驱动</span>
                      </div>
                      <div class="ni-val">
                        {{ nmap[e.from].label }}<span style="color:#253a52"> → </span>
                        <span style="color:#ffaa22">{{ e.label }}</span>
                        <span v-if="e.source === 'inferred'" style="font-size:10px; color:#bb77ff; margin-left:4px;">(AI推理)</span>
                      </div>
                      <button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button>
                    </div>
                  </div>
                  <div v-if="!outgoing.length && !incoming.length" style="color:#1e3348;font-size:11px;padding-top:4px">暂无关系</div>
                </div>
              </template>
              <template v-if="tab === 2">
                <!-- 属性 Tab 可编辑 -->
                <div class="ni-prop-list">
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
              <template v-if="tab === 3">
                <div class="ni-section">
                  <div class="ni-section-title">Schema 定义</div>
                  <div v-for="[k, v] in [['label','String (required)'],['type',t.label+' (enum)'],['id','String (PK)'],['x','Float'],['y','Float']]" :key="k" class="ni-row">
                    <div class="ni-key">{{ k }}</div><div class="ni-val">{{ v }}</div>
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
                <div class="ni-section" style="flex:1">
                  <div class="ni-section-title">全局拓扑关系表 ({{ edges.length }})</div>
                  <div v-for="e in edges" :key="e.id">
                    <div v-if="nmap[e.from] && nmap[e.to]" class="ni-row">
                      <div class="ni-key" style="color:#ffaa22; width: 100px;">{{ e.label }}</div>
                      <div class="ni-val">{{ nmap[e.from].label }} <span style="color:#253a52; margin: 0 8px;">→</span> {{ nmap[e.to].label }}</div>
                    </div>
                  </div>
                  <div v-if="!edges.length" style="color:#1e3348;font-size:11px;padding-top:4px">暂无全局关系</div>
                </div>
              </template>
              <template v-if="tab === 2">
                <div class="ni-section">
                  <div class="ni-section-title">模型属性表</div>
                  <!-- TODO: 接入后端的模型 metadata（type / level / version 等） -->
                  <div class="ni-row"><div class="ni-key">节点总数</div><div class="ni-val">{{ nodes.length }}</div></div>
                  <div class="ni-row"><div class="ni-key">关系总数</div><div class="ni-val">{{ edges.length }}</div></div>
                </div>
              </template>
              <template v-if="tab === 3">
                <div class="ni-section">
                  <div class="ni-section-title">全局本体 Schema</div>
                  <div class="ni-row"><div class="ni-key">Entity [实体]</div><div class="ni-val">id(PK), label(String), type(Enum)</div></div>
                  <div class="ni-row"><div class="ni-key">Edge [关系]</div><div class="ni-val">id(PK), from(EntityID), to(EntityID), label(String)</div></div>
                  <div class="ni-row"><div class="ni-key">Properties [属性列]</div><div class="ni-val">动态键值对 (支持 String, Number, Boolean)</div></div>
                </div>
              </template>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
