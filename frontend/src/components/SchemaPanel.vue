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
import { Button } from '@/components/ui/button';
import BaseSelect from './form/BaseSelect.vue';

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

const kindOptions = computed(() => CONSTRAINT_KINDS.map((k) => ({ value: k, label: kindLabel(k) })));

const sourceBadge = (s?: string) => {
  if (s === 'inferred') return { text: 'AI推理', color: '#7c3aed', bg: 'rgba(124,58,237,0.10)' };
  if (s === 'derived')  return { text: '文本提取', color: '#059669', bg: 'rgba(5,150,105,0.10)' };
  if (s === 'manual')   return { text: '手动', color: '#2563eb', bg: 'rgba(37,99,235,0.10)' };
  return { text: '预置', color: '#a1a1aa', bg: 'rgba(0,0,0,0.05)' };
};
</script>

<template>
  <div v-if="open" class="sp-wrap">
    <div class="sp-header">
      <div class="sp-title">
        <span class="sp-title-mark">⎔</span>
        <span>Schema · 静态本体</span>
      </div>
      <Button variant="ghost" size="icon-sm" @click="emit('close')" title="关闭">×</Button>
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
            <Button variant="ghost" size="icon-sm" class="text-destructive" @click="removeAttribute(n, i)" title="删除">×</Button>
          </div>
          <Button variant="outline" size="sm" @click="addAttribute(n)">+ 添加属性</Button>

          <div class="sp-sub">约束 ({{ (n.constraints || []).length }})</div>
          <div v-for="(c, i) in (n.constraints || [])" :key="'c'+i" class="sp-row">
            <div class="sp-in-kind"><BaseSelect size="sm" :model-value="c.kind || 'custom'" :options="kindOptions" @update:model-value="updateNodeConstraint(n, i, { kind: $event as any })" /></div>
            <input class="sp-in sp-in-note" :value="c.note" @input="updateNodeConstraint(n, i, { note: ($event.target as HTMLInputElement).value })" placeholder="约束说明"/>
            <span class="sp-src" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
            <Button variant="ghost" size="icon-sm" class="text-destructive" @click="removeNodeConstraint(n, i)" title="删除">×</Button>
          </div>
          <Button variant="outline" size="sm" @click="addNodeConstraint(n)">+ 添加约束</Button>
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
            <div class="sp-in-kind"><BaseSelect size="sm" :model-value="c.kind || 'custom'" :options="kindOptions" @update:model-value="updateEdgeConstraint(e, i, { kind: $event as any })" /></div>
            <input class="sp-in sp-in-note" :value="c.note" @input="updateEdgeConstraint(e, i, { note: ($event.target as HTMLInputElement).value })" placeholder="约束说明"/>
            <span class="sp-src" :style="{color: sourceBadge(c.source).color, background: sourceBadge(c.source).bg}">{{ sourceBadge(c.source).text }}</span>
            <Button variant="ghost" size="icon-sm" class="text-destructive" @click="removeEdgeConstraint(e, i)" title="删除">×</Button>
          </div>
          <Button variant="outline" size="sm" @click="addEdgeConstraint(e)">+ 添加约束</Button>
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
  background: var(--bg-base, #fff);
  border-left: 1px solid var(--hairline, rgba(0,0,0,0.07));
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.sp-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07));
}
.sp-title { display: flex; align-items: center; gap: 8px; color: var(--text-main, #18181b); font-size: 14px; font-weight: 600; }
.sp-title-mark { color: #2563eb; font-size: 16px; }
.sp-close { background: transparent; border: none; color: var(--text-muted, #a1a1aa); font-size: 18px; cursor: pointer; }
.sp-close:hover { color: var(--text-main, #18181b); }

.sp-tabs {
  display: flex;
  padding: 6px 8px;
  gap: 4px;
  border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07));
}
.sp-tab {
  flex: 1;
  background: transparent;
  border: none;
  color: var(--text-dim, #52525b);
  padding: 6px 4px;
  font-size: 12px;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.sp-tab:hover { background: var(--bg-elev, rgba(0,0,0,0.045)); color: var(--text-main, #18181b); }
.sp-tab.active { background: rgba(37,99,235,0.10); color: #2563eb; }
.sp-tab-count { font-size: 10px; opacity: 0.7; font-family: 'JetBrains Mono', monospace; }

.sp-body { flex: 1; overflow-y: auto; padding: 12px; }
.sp-empty { padding: 24px 8px; text-align: center; color: var(--text-muted, #a1a1aa); font-size: 12px; line-height: 1.6; }

.sp-card {
  background: var(--bg-subtle, #f7f8fa);
  border: 1px solid var(--hairline, rgba(0,0,0,0.07));
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 10px;
}
.sp-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.sp-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.sp-dot-class { background: #2563eb; }
.sp-dot-rel { background: #059669; }
.sp-card-name { color: var(--text-main, #18181b); font-size: 13px; font-weight: 600; cursor: pointer; flex: 1; }
.sp-card-name:hover { color: #2563eb; }
.sp-lock {
  font-size: 11px;
  background: rgba(219,39,119,0.10);
  color: #db2777;
  padding: 2px 6px;
  border-radius: 100px;
  cursor: help;
}

.sp-rel-end { color: var(--text-dim, #52525b); cursor: pointer; }
.sp-rel-end:hover { color: #2563eb; text-decoration: underline; }
.sp-rel-arrow { color: var(--text-muted, #a1a1aa); margin: 0 4px; font-family: 'JetBrains Mono', monospace; font-size: 12px; }

.sp-sub {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--text-muted, #a1a1aa);
  margin: 8px 0 4px;
}
.sp-row { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.sp-in {
  background: #fff;
  border: 1px solid var(--glass-border, rgba(0,0,0,0.09));
  color: var(--text-main, #18181b);
  border-radius: 5px;
  padding: 4px 6px;
  font-size: 12px;
  outline: none;
  font-family: inherit;
}
.sp-in:focus { border-color: rgba(0,0,0,0.35); }
.sp-in-name { width: 80px; }
.sp-in-val { flex: 1; min-width: 0; }
.sp-in-kind { width: 96px; flex-shrink: 0; }
.sp-in-note { flex: 1; min-width: 0; }
.sp-colon { color: var(--text-muted, #a1a1aa); }
.sp-del {
  background: transparent;
  border: none;
  color: var(--text-muted, #a1a1aa);
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
}
.sp-del:hover { color: #dc2626; }
.sp-add {
  background: rgba(37,99,235,0.05);
  border: 1px dashed rgba(37,99,235,0.35);
  color: #2563eb;
  border-radius: 6px;
  padding: 4px 8px;
  margin-top: 4px;
  font-size: 11px;
  cursor: pointer;
  width: 100%;
  font-family: inherit;
}
.sp-add:hover { background: rgba(37,99,235,0.10); }

.sp-flat {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-dim, #52525b);
}
.sp-flat:hover { background: var(--bg-elev, rgba(0,0,0,0.045)); }
.sp-flat-owner { color: var(--text-main, #18181b); font-weight: 500; cursor: pointer; }
.sp-flat-owner:hover { color: #2563eb; }
.sp-flat-dot { color: var(--text-muted, #a1a1aa); }
.sp-flat-name { color: #d97706; font-weight: 500; }
.sp-flat-val { color: var(--text-dim, #52525b); font-family: 'JetBrains Mono', monospace; font-size: 11px; }
.sp-flat-kind {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 100px;
  letter-spacing: 0.5px;
}
.sp-flat-kind-class    { background: #eff6ff; color: #2563eb; }
.sp-flat-kind-relation { background: #ecfdf5; color: #059669; }

.sp-src {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  white-space: nowrap;
  flex-shrink: 0;
}
.sp-src-flat { margin-left: auto; }
</style>
