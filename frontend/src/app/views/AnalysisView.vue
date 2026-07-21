<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { LoaderCircle, Waypoints, Search, AlertTriangle, CircleAlert, Copy, Unplug, RefreshCw } from 'lucide-vue-next';
import { listOntologies, getOntology, getModelSubgraph } from '../../api/ontology';
import type { OntologyModel, OntologyNode, OntologyEdge } from '../../types';
import { useToastStore } from '../stores/toast';
import { typeLabel, sourceMeta } from '../lib/graphStyle';
import GraphCanvas from '../components/GraphCanvas.vue';
import UiEmpty from '../components/UiEmpty.vue';

const router = useRouter();
const toast = useToastStore();

type Tab = 'overview' | 'trace' | 'health';
const tab = ref<Tab>('overview');

const models = ref<OntologyModel[]>([]);
const currentId = ref('');
const model = ref<OntologyModel | null>(null);
const loading = ref(true);

// 追溯
const traceQuery = ref('');
const traceNode = ref<OntologyNode | null>(null);
const traceDir = ref<'up' | 'down' | 'both'>('both');
const traceDepth = ref(2);
const traceResult = ref<{ nodes: OntologyNode[]; edges: OntologyEdge[] } | null>(null);
const tracing = ref(false);

const nodes = computed(() => model.value?.graphData?.nodes || []);
const edges = computed(() => model.value?.graphData?.edges || []);

onMounted(async () => {
  try {
    models.value = await listOntologies();
    if (models.value[0]) await open(models.value[0].id);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    loading.value = false;
  }
});

async function open(id: string) {
  currentId.value = id;
  traceResult.value = null;
  traceNode.value = null;
  traceQuery.value = '';
  model.value = await getOntology(id);
}

// ── 概览统计 ──
const stats = computed(() => {
  const ns = nodes.value;
  const es = edges.value;
  const domains = new Set(ns.map((n) => n.domain).filter(Boolean));
  const confs = es.map((e) => e.confidence).filter((c): c is number => c != null);
  const avgConf = confs.length ? Math.round((confs.reduce((s, c) => s + c, 0) / confs.length) * 100) : null;
  return { nodes: ns.length, edges: es.length, domains: domains.size, avgConf };
});

const typeDist = computed(() => {
  const m = new Map<string, number>();
  for (const n of nodes.value) m.set(n.type || 'unknown', (m.get(n.type || 'unknown') || 0) + 1);
  const total = nodes.value.length || 1;
  return [...m.entries()]
    .map(([type, count]) => ({ type, label: typeLabel(type), count, pct: Math.round((count / total) * 100) }))
    .sort((a, b) => b.count - a.count);
});

const sourceDist = computed(() => {
  const m = new Map<string, number>();
  for (const e of edges.value) m.set(e.source || 'unknown', (m.get(e.source || 'unknown') || 0) + 1);
  return [...m.entries()]
    .map(([s, count]) => ({ source: s, meta: sourceMeta(s), count }))
    .sort((a, b) => b.count - a.count);
});

// ── 追溯 ──
const traceCandidates = computed(() => {
  const q = traceQuery.value.trim().toLowerCase();
  if (!q) return [];
  return nodes.value.filter((n) => n.label.toLowerCase().includes(q)).slice(0, 8);
});

function pickTraceNode(n: OntologyNode) {
  traceNode.value = n;
  traceQuery.value = n.label;
}

async function runTrace() {
  if (!traceNode.value || !model.value) return;
  tracing.value = true;
  try {
    const sub = await getModelSubgraph(model.value.id, {
      node: traceNode.value.id,
      depth: traceDepth.value,
      dir: traceDir.value,
      limit: 300,
    });
    traceResult.value = { nodes: sub.nodes, edges: sub.edges };
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    tracing.value = false;
  }
}

// ── 体检 ──
const health = computed(() => {
  const ns = nodes.value;
  const es = edges.value;
  const ids = new Set(ns.map((n) => n.id));

  const degree = new Map<string, number>();
  for (const e of es) {
    degree.set(e.from, (degree.get(e.from) || 0) + 1);
    degree.set(e.to, (degree.get(e.to) || 0) + 1);
  }
  const orphans = ns.filter((n) => !degree.get(n.id));

  const byLabel = new Map<string, OntologyNode[]>();
  for (const n of ns) {
    const k = n.label.trim();
    if (!byLabel.has(k)) byLabel.set(k, []);
    byLabel.get(k)!.push(n);
  }
  const duplicates = [...byLabel.values()].filter((g) => g.length > 1);

  const pairSet = new Set(es.map((e) => `${e.from}→${e.to}`));
  const conflicts = es.filter((e) => pairSet.has(`${e.to}→${e.from}`) && e.from < e.to);

  // 环检测（DFS 三色标记，最多报 5 条）
  const adj = new Map<string, string[]>();
  for (const e of es) {
    if (!adj.has(e.from)) adj.set(e.from, []);
    adj.get(e.from)!.push(e.to);
  }
  const color = new Map<string, number>();
  const cycles: string[][] = [];
  const stack: string[] = [];
  const dfs = (u: string) => {
    if (cycles.length >= 5) return;
    color.set(u, 1);
    stack.push(u);
    for (const v of adj.get(u) || []) {
      if (cycles.length >= 5) return;
      if (color.get(v) === 1) {
        cycles.push([...stack.slice(stack.indexOf(v)), v]);
      } else if (!color.get(v)) dfs(v);
    }
    stack.pop();
    color.set(u, 2);
  };
  for (const id of ids) if (!color.get(id)) dfs(id);

  const noEvidence = es.filter((e) => e.source === 'inferred' && !e.evidence && !(e.evidences || []).length);

  return { orphans, duplicates, conflicts, cycles, noEvidence };
});

const nameOf = (id: string) => nodes.value.find((n) => n.id === id)?.label || id;

function goGraph(nodeId?: string) {
  router.push({ name: 'graph', query: { model: currentId.value, node: nodeId } });
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex shrink-0 flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-4 py-2.5">
      <select v-model="currentId" class="input h-8 w-56 text-[13px]" @change="open(currentId)">
        <option v-for="m in models" :key="m.id" :value="m.id">{{ m.title || m.name || '未命名模型' }}</option>
      </select>
      <div class="ml-2 flex rounded-lg bg-slate-100 p-0.5">
        <button
          v-for="t in ([['overview', '概览'], ['trace', '血缘追溯'], ['health', '结构体检']] as [Tab, string][])"
          :key="t[0]"
          class="rounded-md px-3.5 py-1.5 text-[13px] font-medium transition"
          :class="tab === t[0] ? 'bg-white text-indigo-700 shadow-sm' : 'text-slate-500 hover:text-slate-700'"
          @click="tab = t[0]"
        >{{ t[1] }}</button>
      </div>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto">
      <div v-if="loading" class="flex justify-center py-20 text-slate-400"><LoaderCircle :size="22" class="animate-spin" /></div>
      <UiEmpty v-else-if="!model" :icon="Waypoints" title="暂无可分析的模型" description="请先到经验库构建血缘图。" />

      <!-- 概览 -->
      <div v-else-if="tab === 'overview'" class="mx-auto max-w-4xl px-6 py-6">
        <div class="grid gap-4 sm:grid-cols-4">
          <div class="card p-4"><div class="text-2xl font-bold text-slate-900">{{ stats.nodes }}</div><div class="text-xs text-slate-400">节点</div></div>
          <div class="card p-4"><div class="text-2xl font-bold text-slate-900">{{ stats.edges }}</div><div class="text-xs text-slate-400">血缘边</div></div>
          <div class="card p-4"><div class="text-2xl font-bold text-slate-900">{{ stats.domains }}</div><div class="text-xs text-slate-400">业务领域</div></div>
          <div class="card p-4"><div class="text-2xl font-bold text-slate-900">{{ stats.avgConf != null ? stats.avgConf + '%' : '—' }}</div><div class="text-xs text-slate-400">平均边置信度</div></div>
        </div>

        <div class="mt-6 grid gap-4 lg:grid-cols-2">
          <div class="card p-5">
            <h3 class="mb-4 text-[14px] font-semibold text-slate-700">节点类型分布</h3>
            <div class="space-y-2.5">
              <div v-for="d in typeDist" :key="d.type">
                <div class="mb-1 flex justify-between text-[12.5px]"><span class="text-slate-600">{{ d.label }}</span><span class="text-slate-400">{{ d.count }}</span></div>
                <div class="h-2 overflow-hidden rounded-full bg-slate-100">
                  <div class="h-full rounded-full bg-indigo-500" :style="{ width: d.pct + '%' }" />
                </div>
              </div>
            </div>
          </div>
          <div class="card p-5">
            <h3 class="mb-4 text-[14px] font-semibold text-slate-700">血缘边来源分布</h3>
            <div class="space-y-2">
              <div v-for="d in sourceDist" :key="d.source" class="flex items-center gap-2.5 rounded-lg bg-slate-50 px-3 py-2">
                <span class="badge" :class="d.meta.cls">{{ d.meta.label }}</span>
                <span class="ml-auto text-[13px] font-medium text-slate-700">{{ d.count }}</span>
              </div>
            </div>
            <p class="mt-4 text-xs leading-relaxed text-slate-400">「推断」来源的边建议通过值包含检验做数据佐证，或进入人工审核。</p>
          </div>
        </div>
      </div>

      <!-- 追溯 -->
      <div v-else-if="tab === 'trace'" class="flex h-full flex-col">
        <div class="flex shrink-0 flex-wrap items-center gap-2 px-4 py-3">
          <div class="relative">
            <Search :size="14" class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input v-model="traceQuery" class="input h-8 w-52 pl-8 text-[13px]" placeholder="输入节点名…" @input="traceNode = null" />
            <div v-if="traceCandidates.length && !traceNode" class="absolute z-20 mt-1 w-52 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg">
              <button
                v-for="n in traceCandidates" :key="n.id"
                class="block w-full px-3 py-2 text-left text-[13px] hover:bg-indigo-50"
                @click="pickTraceNode(n)"
              >{{ n.label }}</button>
            </div>
          </div>
          <div class="flex rounded-lg bg-slate-100 p-0.5">
            <button
              v-for="d in ([['up', '上游'], ['down', '下游'], ['both', '双向']] as const)" :key="d[0]"
              class="rounded-md px-3 py-1 text-[12.5px] font-medium transition"
              :class="traceDir === d[0] ? 'bg-white text-indigo-700 shadow-sm' : 'text-slate-500'"
              @click="traceDir = d[0]"
            >{{ d[1] }}</button>
          </div>
          <select v-model.number="traceDepth" class="input h-8 w-24 text-[13px]">
            <option :value="1">1 跳</option><option :value="2">2 跳</option><option :value="3">3 跳</option><option :value="5">5 跳</option>
          </select>
          <button class="btn-primary btn-sm" :disabled="!traceNode || tracing" @click="runTrace">
            <LoaderCircle v-if="tracing" :size="13" class="animate-spin" /> 开始追溯
          </button>
        </div>
        <div class="min-h-0 flex-1 border-t border-slate-100">
          <UiEmpty v-if="!traceResult" :icon="Search" title="选择一个节点开始追溯" description="上游 = 它从哪来，下游 = 它影响谁。" />
          <GraphCanvas v-else :nodes="traceResult.nodes" :edges="traceResult.edges" :show-edge-labels="true" />
        </div>
      </div>

      <!-- 体检 -->
      <div v-else class="mx-auto max-w-4xl space-y-4 px-6 py-6">
        <div class="card p-5">
          <div class="mb-2 flex items-center gap-2">
            <Unplug :size="16" class="text-slate-400" />
            <h3 class="text-[14px] font-semibold text-slate-700">孤立节点</h3>
            <span class="badge ml-auto" :class="health.orphans.length ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'">{{ health.orphans.length }}</span>
          </div>
          <p class="mb-2 text-xs text-slate-400">没有任何血缘边连接的节点，可能是抽取遗漏或冗余。</p>
          <div v-if="health.orphans.length" class="flex flex-wrap gap-1.5">
            <button v-for="n in health.orphans.slice(0, 30)" :key="n.id" class="badge bg-slate-100 text-slate-600 hover:bg-indigo-50 hover:text-indigo-700" @click="goGraph(n.id)">{{ n.label }}</button>
          </div>
        </div>

        <div class="card p-5">
          <div class="mb-2 flex items-center gap-2">
            <Copy :size="16" class="text-slate-400" />
            <h3 class="text-[14px] font-semibold text-slate-700">疑似重复节点</h3>
            <span class="badge ml-auto" :class="health.duplicates.length ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'">{{ health.duplicates.length }}</span>
          </div>
          <div v-if="health.duplicates.length" class="space-y-1">
            <div v-for="(g, i) in health.duplicates.slice(0, 20)" :key="i" class="rounded-lg bg-slate-50 px-3 py-1.5 text-[12.5px] text-slate-600">
              「{{ g[0].label }}」× {{ g.length }}
            </div>
          </div>
        </div>

        <div class="card p-5">
          <div class="mb-2 flex items-center gap-2">
            <AlertTriangle :size="16" class="text-slate-400" />
            <h3 class="text-[14px] font-semibold text-slate-700">方向矛盾</h3>
            <span class="badge ml-auto" :class="health.conflicts.length ? 'bg-rose-50 text-rose-700' : 'bg-emerald-50 text-emerald-700'">{{ health.conflicts.length }}</span>
          </div>
          <p class="mb-2 text-xs text-slate-400">同一对节点之间存在双向边，需人工确认血缘方向。</p>
          <div v-if="health.conflicts.length" class="space-y-1">
            <div v-for="e in health.conflicts.slice(0, 20)" :key="e.id" class="rounded-lg bg-rose-50 px-3 py-1.5 text-[12.5px] text-rose-700">
              {{ nameOf(e.from) }} ⇄ {{ nameOf(e.to) }}
            </div>
          </div>
        </div>

        <div class="card p-5">
          <div class="mb-2 flex items-center gap-2">
            <RefreshCw :size="16" class="text-slate-400" />
            <h3 class="text-[14px] font-semibold text-slate-700">血缘环</h3>
            <span class="badge ml-auto" :class="health.cycles.length ? 'bg-rose-50 text-rose-700' : 'bg-emerald-50 text-emerald-700'">{{ health.cycles.length }}</span>
          </div>
          <div v-if="health.cycles.length" class="space-y-1">
            <div v-for="(c, i) in health.cycles" :key="i" class="rounded-lg bg-rose-50 px-3 py-1.5 text-[12.5px] text-rose-700">
              {{ c.map(nameOf).join(' → ') }}
            </div>
          </div>
        </div>

        <div class="card p-5">
          <div class="mb-2 flex items-center gap-2">
            <CircleAlert :size="16" class="text-slate-400" />
            <h3 class="text-[14px] font-semibold text-slate-700">缺证据的推断边</h3>
            <span class="badge ml-auto" :class="health.noEvidence.length ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'">{{ health.noEvidence.length }}</span>
          </div>
          <p class="mb-2 text-xs text-slate-400">推断来源且无证据的边，建议做数据佐证或人工审核。</p>
          <div v-if="health.noEvidence.length" class="space-y-1">
            <div v-for="e in health.noEvidence.slice(0, 20)" :key="e.id" class="rounded-lg bg-slate-50 px-3 py-1.5 text-[12.5px] text-slate-600">
              {{ nameOf(e.from) }} → {{ nameOf(e.to) }} <span class="text-slate-400">（{{ e.label || e.rel_type }}）</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
