<script setup lang="ts">
import { computed } from 'vue';
import { ArrowUpRight, ArrowDownRight, Link2 } from 'lucide-vue-next';
import type { OntologyNode, OntologyEdge } from '../../types';
import { typeStyle, typeLabel, sourceMeta } from '../lib/graphStyle';

const props = defineProps<{
  node: OntologyNode;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'jump', nodeId: string): void;
  (e: 'trace', node: OntologyNode, dir: 'up' | 'down'): void;
}>();

const style = computed(() => typeStyle(props.node.type));
const source = computed(() => sourceMeta(props.node.source));

const nameOf = computed(() => {
  const map = new Map(props.nodes.map((n) => [n.id, n.label]));
  return (id: string) => map.get(id) || id;
});

const incoming = computed(() => props.edges.filter((e) => e.to === props.node.id));
const outgoing = computed(() => props.edges.filter((e) => e.from === props.node.id));

const confidencePct = computed(() =>
  props.node.confidence != null ? Math.round(props.node.confidence * 100) : null,
);
</script>

<template>
  <div class="space-y-5">
    <div>
      <div class="mb-2 flex items-center gap-2">
        <span class="badge" :style="{ background: style.bg, color: style.color }">{{ typeLabel(node.type) }}</span>
        <span v-if="node.source" class="badge" :class="source.cls">{{ source.label }}</span>
        <span v-if="node.domain" class="badge bg-slate-100 text-slate-600">{{ node.domain }}</span>
      </div>
      <h4 class="text-[16px] font-semibold leading-snug text-slate-900">{{ node.label }}</h4>
      <div v-if="confidencePct != null" class="mt-2.5">
        <div class="mb-1 flex items-center justify-between text-xs text-slate-400">
          <span>置信度</span><span>{{ confidencePct }}%</span>
        </div>
        <div class="h-1.5 overflow-hidden rounded-full bg-slate-100">
          <div class="h-full rounded-full bg-indigo-500" :style="{ width: confidencePct + '%' }" />
        </div>
      </div>
    </div>

    <div v-if="node.evidence" class="rounded-lg bg-amber-50 px-3 py-2.5 text-[12.5px] leading-relaxed text-amber-800">
      <span class="font-medium">证据：</span>{{ node.evidence }}
    </div>

    <div v-if="node.attributes?.length">
      <div class="label">属性（{{ node.attributes.length }}）</div>
      <div class="space-y-1">
        <div
          v-for="a in node.attributes" :key="a.name"
          class="flex items-center justify-between rounded-lg border border-slate-100 bg-slate-50 px-3 py-1.5 text-[12.5px]"
        >
          <span class="text-slate-700">{{ a.name }}</span>
          <span class="text-slate-400">{{ a.valueSpace || a.column || '' }}</span>
        </div>
      </div>
    </div>

    <div v-if="node.constraints?.length">
      <div class="label">约束（{{ node.constraints.length }}）</div>
      <div class="space-y-1">
        <div
          v-for="(c, i) in node.constraints" :key="i"
          class="rounded-lg border border-rose-100 bg-rose-50 px-3 py-1.5 text-[12.5px] text-rose-700"
        >{{ c.note }}</div>
      </div>
    </div>

    <div class="grid grid-cols-2 gap-2">
      <button class="btn-secondary btn-sm" @click="emit('trace', node, 'up')">
        <ArrowUpRight :size="14" /> 上游追溯
      </button>
      <button class="btn-secondary btn-sm" @click="emit('trace', node, 'down')">
        <ArrowDownRight :size="14" /> 下游追溯
      </button>
    </div>

    <div v-if="incoming.length">
      <div class="label">流入（{{ incoming.length }}）</div>
      <div class="space-y-1">
        <button
          v-for="e in incoming" :key="e.id"
          class="flex w-full items-center gap-2 rounded-lg border border-slate-100 px-3 py-1.5 text-left text-[12.5px] hover:border-indigo-200 hover:bg-indigo-50"
          @click="emit('jump', e.from)"
        >
          <Link2 :size="12" class="shrink-0 text-slate-300" />
          <span class="truncate text-slate-700">{{ nameOf(e.from) }}</span>
          <span class="ml-auto shrink-0 text-slate-400">{{ e.label || e.rel_type }}</span>
        </button>
      </div>
    </div>

    <div v-if="outgoing.length">
      <div class="label">流出（{{ outgoing.length }}）</div>
      <div class="space-y-1">
        <button
          v-for="e in outgoing" :key="e.id"
          class="flex w-full items-center gap-2 rounded-lg border border-slate-100 px-3 py-1.5 text-left text-[12.5px] hover:border-indigo-200 hover:bg-indigo-50"
          @click="emit('jump', e.to)"
        >
          <Link2 :size="12" class="shrink-0 text-slate-300" />
          <span class="truncate text-slate-700">{{ nameOf(e.to) }}</span>
          <span class="ml-auto shrink-0 text-slate-400">{{ e.label || e.rel_type }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
