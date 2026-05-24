<script setup lang="ts">
/**
 * Schema 面板 —— 静态本体层 (TBox) 的"账本"视图。
 * 图谱画布只画 class 节点 + 关系边;Schema 面板补齐属性/约束等不上图的元素。
 *
 *   类     — 列出每个 class 节点 + 它的属性 (name : valueSpace) + 约束列表
 *   关系   — 列出每条边 (label, 域 → 值域) + 约束列表
 *   属性   — 全局聚合视图,按"哪个类挂哪个属性"汇总
 *   约束   — 全局聚合视图,按 class/relation 汇总,点击可定位
 */
import { ref, computed } from 'vue';
import type { OntologyNode, OntologyEdge, OntologyAttribute, OntologyConstraint } from '../types';

const props = defineProps<{
  open: boolean;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'focus-node', id: string): void;
  (e: 'update-node', id: string, patch: Partial<OntologyNode>): void;
  (e: 'update-edge', id: string, patch: Partial<OntologyEdge>): void;
}>();

const tab = ref<'class' | 'relation' | 'attribute' | 'constraint'>('class');

const classes = computed(() => props.nodes.filter(n => n.type === 'class'));
const relations = computed(() => props.edges);

const nodeLabel = (id: string) => props.nodes.find(n => n.id === id)?.label || id;

// ---- 属性: 全局聚合 ----
const allAttributes = computed(() => {
  const out: { ownerId: string; ownerLabel: string; attr: OntologyAttribute }[] = [];
  for (const n of classes.value) {
    for (const a of (n.attributes || [])) {
      out.push({ ownerId: n.id, ownerLabel: n.label, attr: a });
    }
  }
  return out;
});

// ---- 约束: 全局聚合 (来自类 + 关系) ----
const allConstraints = computed(() => {
  const out: { ownerKind: 'class' | 'relation'; ownerId: string; ownerLabel: string; c: OntologyConstraint }[] = [];
  for (const n of classes.value) {
    for (const c of (n.constraints || [])) {
      out.push({ ownerKind: 'class', ownerId: n.id, ownerLabel: n.label, c });
    }
  }
  for (const e of relations.value) {
    for (const c of (e.constraints || [])) {
      out.push({ ownerKind: 'relation', ownerId: e.id, ownerLabel: `${nodeLabel(e.from)} —${e.label || ''}→ ${nodeLabel(e.to)}`, c });
    }
  }
  return out;
});

// ---- 类:增删属性 / 约束 ----
const addAttribute = (n: OntologyNode) => {
  const next = [...(n.attributes || []), { name: '新属性', valueSpace: 'string', source: 'manual' as const }];
  emit('update-node', n.id, { attributes: next });
};
const removeAttribute = (n: OntologyNode, idx: number) => {
  const next = (n.attributes || []).filter((_, i) => i !== idx);
  emit('update-node', n.id, { attributes: next });
};
const updateAttribute = (n: OntologyNode, idx: number, patch: Partial<OntologyAttribute>) => {
  const next = (n.attributes || []).map((a, i) => i === idx ? { ...a, ...patch } : a);
  emit('update-node', n.id, { attributes: next });
};

const addNodeConstraint = (n: OntologyNode) => {
  const next = [...(n.constraints || []), { kind: 'custom' as const, note: '新约束', source: 'manual' as const }];
  emit('update-node', n.id, { constraints: next });
};
const removeNodeConstraint = (n: OntologyNode, idx: number) => {
  const next = (n.constraints || []).filter((_, i) => i !== idx);
  emit('update-node', n.id, { constraints: next });
};
const updateNodeConstraint = (n: OntologyNode, idx: number, patch: Partial<OntologyConstraint>) => {
  const next = (n.constraints || []).map((c, i) => i === idx ? { ...c, ...patch } : c);
  emit('update-node', n.id, { constraints: next });
};

// ---- 关系:增删约束 ----
const addEdgeConstraint = (e: OntologyEdge) => {
  const next = [...(e.constraints || []), { kind: 'custom' as const, note: '新约束', source: 'manual' as const }];
  emit('update-edge', e.id, { constraints: next });
};
const removeEdgeConstraint = (e: OntologyEdge, idx: number) => {
  const next = (e.constraints || []).filter((_, i) => i !== idx);
  emit('update-edge', e.id, { constraints: next });
};
const updateEdgeConstraint = (e: OntologyEdge, idx: number, patch: Partial<OntologyConstraint>) => {
  const next = (e.constraints || []).map((c, i) => i === idx ? { ...c, ...patch } : c);
  emit('update-edge', e.id, { constraints: next });
};

const CONSTRAINT_KINDS: OntologyConstraint['kind'][] = ['cardinality', 'exclusive', 'symmetric', 'transitive', 'custom'];
const kindLabel = (k?: string) => ({
  cardinality: '基数',
  exclusive:   '互斥',
  symmetric:   '对称',
  transitive:  '传递',
  custom:      '自定义',
} as Record<string, string>)[k || 'custom'] || k || '自定义';

const sourceBadge = (s?: string) => {
  if (s === 'inferred') return { text: 'AI推理', color: '#bb77ff', bg: 'rgba(187,119,255,0.12)' };
  if (s === 'derived')  return { text: '文本提取', color: '#22dd88', bg: 'rgba(34,221,136,0.12)' };
  if (s === 'manual')   return { text: '手动', color: '#3d9bff', bg: 'rgba(61,155,255,0.12)' };
  return { text: '预置', color: 'rgba(255,255,255,0.5)', bg: 'rgba(255,255,255,0.06)' };
};
</script>

<template>
  <div v-if="open" class="sp-wrap">
    <div class="sp-header">
      <div class="sp-title">
        <span class="sp-title-mark">⎔</span>
        <span>Schema · 静态本体</span>
      </div>
      <button class="sp-close" @click="emit('close')" title="关闭">×</button>
    </div>

    <div class="sp-tabs">
      <button :class="['sp-tab', { active: tab === 'class' }]" @click="tab = 'class'">
        类 <span class="sp-tab-count">{{ classes.length }}</span>
      </button>
      <button :class="['sp-tab', { active: tab === 'relation' }]" @click="tab = 'relation'">
        关系 <span class="sp-tab-count">{{ relations.length }}</span>
      </button>
      <button :class="['sp-tab', { active: tab === 'attribute' }]" @click="tab = 'attribute'">
        属性 <span class="sp-tab-count">{{ allAttributes.length }}</span>
      </button>
      <button :class="['sp-tab', { active: tab === 'constraint' }]" @click="tab = 'constraint'">
        约束 <span class="sp-tab-count">{{ allConstraints.length }}</span>
      </button>
    </div>

    <div class="sp-body">
      <!-- 类 -->
      <div v-if="tab === 'class'">
        <div v-if="classes.length === 0" class="sp-empty">还没有类节点 · 在画布上添加 class 节点即可</div>
        <div v-for="n in classes" :key="n.id" class="sp-card">
          <div class="sp-card-head">
            <span class="sp-dot sp-dot-class"/>
            <span class="sp-card-name" @click="emit('focus-node', n.id)">{{ n.label }}</span>
            <span v-if="(n.constraints?.length || 0) > 0" class="sp-lock" :title="(n.constraints || []).map(c => c.note).join('\n')">🔒</span>
          </div>

          <div class="sp-sub">属性 ({{ (n.attributes || []).length }})</div>
          <div v-for="(a, i) in (n.attributes || [])" :key="'a'+i" class="sp-row">
            <input class="sp-in sp-in-name" :value="a.name" @input="updateAttribute(n, i, { name: ($event.target as HTMLInputElement).value })" placeholder="属性名"/>
            <span class="sp-colon">:</span>
            <input class="sp-in sp-in-val" :value="a.valueSpace || ''" @input="updateAttribute(n, i, { valueSpace: ($event.target as HTMLInputElement).value })" placeholder="取值范围 (如 number / 0..1 / string)"/>
            <span class="sp-src" :style="{color: sourceBadge(a.source).color, background: sourceBadge(a.source).bg}">{{ sourceBadge(a.source).text }}</span>
            <button class="sp-del" @click="removeAttribute(n, i)" title="删除">×</button>
          </div>
          <button class="sp-add" @click="addAttribute(n)">+ 添加属性</button>

          <div class="sp-sub">约束 ({{ (n.constraints || []).length }})</div>
          <div v-for="(c, i) in (n.constraints || [])" :key="'c'+i" class="sp-row">
            <select class="sp-in sp-in-kind" :value="c.kind || 'custom'" @change="updateNodeConstraint(n, i, { kind: ($event.target as HTMLSelectElement).value as any })">
              <option v-for="k in CONSTRAINT_KINDS" :key="k" :value="k">{{ kindLabel(k) }}</option>
            </select>
            <input class="sp-in sp-in-note" :value="c.note" @input="updateNodeConstraint(n, i, { note: ($event.target as HTMLInputElement).value })" placeholder="约束说明"/>
            <span class="sp-src" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
            <button class="sp-del" @click="removeNodeConstraint(n, i)" title="删除">×</button>
          </div>
          <button class="sp-add" @click="addNodeConstraint(n)">+ 添加约束</button>
        </div>
      </div>

      <!-- 关系 -->
      <div v-if="tab === 'relation'">
        <div v-if="relations.length === 0" class="sp-empty">还没有关系 · 在画布上从一个类连到另一个类</div>
        <div v-for="e in relations" :key="e.id" class="sp-card">
          <div class="sp-card-head">
            <span class="sp-dot sp-dot-rel"/>
            <span class="sp-card-name">
              <span class="sp-rel-end" @click="emit('focus-node', e.from)">{{ nodeLabel(e.from) }}</span>
              <span class="sp-rel-arrow">— {{ e.label || '(未命名)' }} →</span>
              <span class="sp-rel-end" @click="emit('focus-node', e.to)">{{ nodeLabel(e.to) }}</span>
            </span>
            <span v-if="(e.constraints?.length || 0) > 0" class="sp-lock" :title="(e.constraints || []).map(c => c.note).join('\n')">🔒</span>
          </div>

          <div class="sp-sub">约束 ({{ (e.constraints || []).length }})</div>
          <div v-for="(c, i) in (e.constraints || [])" :key="'ec'+i" class="sp-row">
            <select class="sp-in sp-in-kind" :value="c.kind || 'custom'" @change="updateEdgeConstraint(e, i, { kind: ($event.target as HTMLSelectElement).value as any })">
              <option v-for="k in CONSTRAINT_KINDS" :key="k" :value="k">{{ kindLabel(k) }}</option>
            </select>
            <input class="sp-in sp-in-note" :value="c.note" @input="updateEdgeConstraint(e, i, { note: ($event.target as HTMLInputElement).value })" placeholder="约束说明"/>
            <span class="sp-src" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
            <button class="sp-del" @click="removeEdgeConstraint(e, i)" title="删除">×</button>
          </div>
          <button class="sp-add" @click="addEdgeConstraint(e)">+ 添加约束</button>
        </div>
      </div>

      <!-- 属性聚合视图 -->
      <div v-if="tab === 'attribute'">
        <div v-if="allAttributes.length === 0" class="sp-empty">还没有属性 · 在「类」标签页给类节点添加属性</div>
        <div v-for="(row, i) in allAttributes" :key="i" class="sp-flat">
          <span class="sp-flat-owner" @click="emit('focus-node', row.ownerId)">{{ row.ownerLabel }}</span>
          <span class="sp-flat-dot">·</span>
          <span class="sp-flat-name">{{ row.attr.name }}</span>
          <span class="sp-flat-val">{{ row.attr.valueSpace || '—' }}</span>
          <span class="sp-src sp-src-flat" :style="{color: sourceBadge(row.attr.source).color, background: sourceBadge(row.attr.source).bg}">{{ sourceBadge(row.attr.source).text }}</span>
        </div>
      </div>

      <!-- 约束聚合视图 -->
      <div v-if="tab === 'constraint'">
        <div v-if="allConstraints.length === 0" class="sp-empty">还没有约束 · 在「类」或「关系」标签页添加</div>
        <div v-for="(row, i) in allConstraints" :key="i" class="sp-flat">
          <span class="sp-flat-kind" :class="'sp-flat-kind-' + (row.ownerKind)">{{ row.ownerKind === 'class' ? '类' : '关系' }}</span>
          <span class="sp-flat-owner" @click="row.ownerKind === 'class' && emit('focus-node', row.ownerId)">{{ row.ownerLabel }}</span>
          <span class="sp-flat-dot">·</span>
          <span class="sp-flat-name">{{ kindLabel(row.c.kind) }}</span>
          <span class="sp-flat-val">{{ row.c.note }}</span>
          <span class="sp-src sp-src-flat" :style="{color: sourceBadge(row.c.source).color, background: sourceBadge(row.c.source).bg}">{{ sourceBadge(row.c.source).text }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sp-wrap {
  width: 360px;
  flex-shrink: 0;
  background: rgba(11, 19, 33, 0.95);
  border-left: 1px solid rgba(255,255,255,0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  backdrop-filter: blur(12px);
}
.sp-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.sp-title { display: flex; align-items: center; gap: 8px; color: #e2e8f0; font-size: 14px; font-weight: 600; }
.sp-title-mark { color: #3d9bff; font-size: 16px; }
.sp-close { background: transparent; border: none; color: rgba(255,255,255,0.4); font-size: 18px; cursor: pointer; }
.sp-close:hover { color: #fff; }

.sp-tabs {
  display: flex;
  padding: 6px 8px;
  gap: 4px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.sp-tab {
  flex: 1;
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.55);
  padding: 6px 4px;
  font-size: 12px;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.sp-tab:hover { background: rgba(255,255,255,0.05); color: #fff; }
.sp-tab.active { background: rgba(61, 155, 255, 0.18); color: #3d9bff; }
.sp-tab-count { font-size: 10px; opacity: 0.7; font-family: 'JetBrains Mono', monospace; }

.sp-body { flex: 1; overflow-y: auto; padding: 12px; }
.sp-empty { padding: 24px 8px; text-align: center; color: rgba(255,255,255,0.35); font-size: 12px; line-height: 1.6; }

.sp-card {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 10px;
}
.sp-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.sp-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.sp-dot-class { background: #3d9bff; box-shadow: 0 0 6px rgba(61,155,255,0.6); }
.sp-dot-rel { background: #22dd88; box-shadow: 0 0 6px rgba(34,221,136,0.6); }
.sp-card-name { color: #e2e8f0; font-size: 13px; font-weight: 600; cursor: pointer; flex: 1; }
.sp-card-name:hover { color: #3d9bff; }
.sp-lock {
  font-size: 11px;
  background: rgba(255, 51, 153, 0.18);
  color: #ff7fbe;
  padding: 2px 6px;
  border-radius: 100px;
  cursor: help;
}

.sp-rel-end { color: #cbd5e1; cursor: pointer; }
.sp-rel-end:hover { color: #3d9bff; text-decoration: underline; }
.sp-rel-arrow { color: rgba(255,255,255,0.4); margin: 0 4px; font-family: 'JetBrains Mono', monospace; font-size: 12px; }

.sp-sub {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: rgba(255,255,255,0.35);
  margin: 8px 0 4px;
}
.sp-row { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.sp-in {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.08);
  color: #e2e8f0;
  border-radius: 5px;
  padding: 4px 6px;
  font-size: 12px;
  outline: none;
  font-family: inherit;
}
.sp-in:focus { border-color: #3d9bff; }
.sp-in-name { width: 80px; }
.sp-in-val { flex: 1; min-width: 0; }
.sp-in-kind { width: 72px; }
.sp-in-note { flex: 1; min-width: 0; }
.sp-colon { color: rgba(255,255,255,0.4); }
.sp-del {
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.35);
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
}
.sp-del:hover { color: #ff8a8a; }
.sp-add {
  background: rgba(61,155,255,0.08);
  border: 1px dashed rgba(61,155,255,0.3);
  color: #3d9bff;
  border-radius: 6px;
  padding: 4px 8px;
  margin-top: 4px;
  font-size: 11px;
  cursor: pointer;
  width: 100%;
  font-family: inherit;
}
.sp-add:hover { background: rgba(61,155,255,0.15); }

.sp-flat {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 12px;
  color: #cbd5e1;
}
.sp-flat:hover { background: rgba(255,255,255,0.04); }
.sp-flat-owner { color: #e2e8f0; font-weight: 500; cursor: pointer; }
.sp-flat-owner:hover { color: #3d9bff; }
.sp-flat-dot { color: rgba(255,255,255,0.3); }
.sp-flat-name { color: #ffaa22; font-weight: 500; }
.sp-flat-val { color: rgba(255,255,255,0.55); font-family: 'JetBrains Mono', monospace; font-size: 11px; }
.sp-flat-kind {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 100px;
  letter-spacing: 0.5px;
}
.sp-flat-kind-class    { background: rgba(61,155,255,0.18); color: #3d9bff; }
.sp-flat-kind-relation { background: rgba(34,221,136,0.18); color: #22dd88; }

.sp-src {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  white-space: nowrap;
  flex-shrink: 0;
}
.sp-src-flat { margin-left: auto; }
</style>
