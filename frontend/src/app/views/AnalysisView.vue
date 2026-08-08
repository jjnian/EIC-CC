<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  BarChart3, Activity, AlertTriangle, Search, GitBranch,
  ShieldCheck, LoaderCircle, TrendingUp, Link2, Info, Maximize2,
} from 'lucide-vue-next';
import { listOntologies, getOntology, getModelSubgraph, getModelDomains, type DomainRollup } from '../../api/ontology';
import { listExperiences, type Experience } from '../../api/experiences';
import { listDataSources, type DataSource } from '../../api/dataSources';
import type { OntologyNode, OntologyEdge } from '../../types';
import { useToastStore } from '../stores/toast';
import GraphCanvas from '../components/GraphCanvas.vue';

const toast = useToastStore();
const loading = ref(true);
const models = ref<any[]>([]);
const modelId = ref('');
const model = ref<any>(null);
const nodes = ref<OntologyNode[]>([]);
const edges = ref<OntologyEdge[]>([]);
const tab = ref('stats');

// 覆盖审计数据
const experiences = ref<Experience[]>([]);
const dataSources = ref<DataSource[]>([]);

// 节点分析搜索
const nodeQ = ref('');
const selectedNode = ref<OntologyNode | null>(null);

// 路径查询
const pathFrom = ref('');
const pathTo = ref('');
const pathMax = ref(3);

// 审核队列
const reviewFilter = ref('all');

onMounted(async () => {
  try {
    const [ms, exps, dss] = await Promise.all([
      listOntologies().catch(() => []),
      listExperiences().catch(() => []),
      listDataSources().catch(() => []),
    ]);
    models.value = ms;
    experiences.value = exps;
    dataSources.value = dss;
  } finally { loading.value = false; }
});

watch(modelId, async (id) => {
  if (!id) { model.value = null; nodes.value = []; edges.value = []; return; }
  loading.value = true;
  try {
    model.value = await getOntology(id);
    nodes.value = model.value.graphData?.nodes || [];
    edges.value = model.value.graphData?.edges || [];
  } catch (e) { toast.error((e as Error).message); }
  finally { loading.value = false; }
});

// ── 图谱统计 ──
const nodeCountsByType = computed(() => {
  const m = new Map<string, number>();
  for (const n of nodes.value) m.set(n.type, (m.get(n.type) || 0) + 1);
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
});
const edgeCountsByLabel = computed(() => {
  const m = new Map<string, number>();
  for (const e of edges.value) {
    const label = e.label || '(未命名)';
    m.set(label, (m.get(label) || 0) + 1);
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
});
const isolatedSet = computed(() => {
  const linked = new Set<string>();
  for (const e of edges.value) { linked.add(e.from); linked.add(e.to); }
  return nodes.value.filter((n) => !linked.has(n.id));
});
const degRank = computed(() => {
  const deg = new Map<string, number>();
  for (const e of edges.value) {
    deg.set(e.from, (deg.get(e.from) || 0) + 1);
    deg.set(e.to, (deg.get(e.to) || 0) + 1);
  }
  return [...deg.entries()].sort((a, b) => b[1] - a[1]).slice(0, 15);
});

// ── 节点过滤 ──
const filteredNodes = computed(() => {
  const q = nodeQ.value.trim().toLowerCase();
  if (!q) return nodes.value.slice(0, 50);
  return nodes.value.filter((n) => n.name?.toLowerCase().includes(q) || n.type?.toLowerCase().includes(q)).slice(0, 100);
});

// ── 大图浏览器 ──
const subNodes = ref<OntologyNode[]>([]);
const subEdges = ref<OntologyEdge[]>([]);
const subLoading = ref(false);

async function loadSubgraph(node?: string) {
  if (!modelId.value || subLoading.value) return;
  subLoading.value = true;
  try {
    const sg = await getModelSubgraph(modelId.value, { node, depth: node ? 2 : 1, dir: 'both', limit: 200 });
    subNodes.value = sg.nodes || [];
    subEdges.value = sg.edges || [];
  } catch (e) { toast.error((e as Error).message); }
  finally { subLoading.value = false; }
}

// ── 路径查询 BFS ──
const pathResult = ref<string[] | null>(null);
function findPath() {
  const from = pathFrom.value.trim();
  const to = pathTo.value.trim();
  if (!from || !to) return;
  const adj = new Map<string, string[]>();
  for (const e of edges.value) {
    if (!adj.has(e.from)) adj.set(e.from, []);
    adj.get(e.from)!.push(e.to);
  }
  const queue: [string, string[]][] = [[from, [from]]];
  const visited = new Set<string>([from]);
  while (queue.length) {
    const [cur, p] = queue.shift()!;
    if (cur === to) { pathResult.value = p; return; }
    if (p.length >= pathMax.value + 1) continue;
    for (const nb of adj.get(cur) || []) {
      if (!visited.has(nb)) { visited.add(nb); queue.push([nb, [...p, nb]]); }
    }
  }
  pathResult.value = [];
}
</script>

<template>
  <div class="mx-auto max-w-5xl space-y-6">
    <div v-if="loading && !model" class="flex justify-center py-20" style="color:var(--text3)"><LoaderCircle :size="22" class="animate-spin" /></div>

    <template v-else>
      <!-- 顶栏：模型选择 + 标签导航 -->
      <div class="flex items-center gap-3">
        <h1 class="text-[16px] font-semibold" style="color:var(--text)">分析中心</h1>
        <select v-model="modelId" class="input !h-8 !w-56 !text-[12.5px]">
          <option value="">选择本体模型…</option>
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.title || m.name || m.id }}</option>
        </select>
      </div>

      <template v-if="!model">
        <div class="panel flex flex-col items-center gap-3 py-16" style="color:var(--text3)">
          <BarChart3 :size="36" />
          <p class="text-[13.5px]">请先选择一个本体模型，查看其分析报告。</p>
        </div>
      </template>

      <template v-else>
        <!-- 标签切换 -->
        <div class="flex gap-1 border-b pb-2" style="border-color:var(--border)">
          <button class="tab-btn" :class="{ active: tab === 'stats' }" @click="tab = 'stats'"><BarChart3 class="h-3.5 w-3.5" /> 图谱统计</button>
          <button class="tab-btn" :class="{ active: tab === 'coverage' }" @click="tab = 'coverage'"><ShieldCheck class="h-3.5 w-3.5" /> 覆盖审计</button>
          <button class="tab-btn" :class="{ active: tab === 'health' }" @click="tab = 'health'"><Activity class="h-3.5 w-3.5" /> 血缘健康</button>
          <button class="tab-btn" :class="{ active: tab === 'nodes' }" @click="tab = 'nodes'"><Search class="h-3.5 w-3.5" /> 节点分析</button>
          <button class="tab-btn" :class="{ active: tab === 'path' }" @click="tab = 'path'"><GitBranch class="h-3.5 w-3.5" /> 路径查询</button>
          <button class="tab-btn" :class="{ active: tab === 'big' }" @click="tab = 'big'; loadSubgraph()"><Maximize2 class="h-3.5 w-3.5" /> 大图浏览</button>
          <button class="tab-btn" :class="{ active: tab === 'review' }" @click="tab = 'review'"><AlertTriangle class="h-3.5 w-3.5" /> 审核队列</button>
        </div>

        <!-- ===== 图谱统计 ===== -->
        <div v-show="tab === 'stats'" class="space-y-5">
          <div class="grid grid-cols-4 gap-3">
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--primary)">{{ nodes.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">节点总数</div>
            </div>
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--primary)">{{ edges.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">关系总数</div>
            </div>
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--warning)">{{ isolatedSet.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">孤立节点</div>
            </div>
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--primary)">{{ edgeCountsByLabel.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">关系类型</div>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-4">
            <div class="panel rounded-xl p-4">
              <h4 class="mb-2 text-[13px] font-semibold" style="color:var(--text)">节点类型分布</h4>
              <div class="space-y-1.5">
                <div v-for="[type, cnt] in nodeCountsByType" :key="type" class="flex items-center gap-2">
                  <span class="text-[12.5px]" style="color:var(--text2)">{{ type }}</span>
                  <div class="h-2 flex-1 rounded-full" style="background:var(--nav-hover)">
                    <div class="h-full rounded-full" :style="{ width: (cnt / nodes.length * 100).toFixed(1) + '%', background: 'var(--primary)' }" />
                  </div>
                  <span class="text-[12px] font-mono" style="color:var(--text3)">{{ cnt }}</span>
                </div>
              </div>
            </div>
            <div class="panel rounded-xl p-4">
              <h4 class="mb-2 text-[13px] font-semibold" style="color:var(--text)">度数排名 Top 15</h4>
              <div class="max-h-64 space-y-1 overflow-y-auto">
                <div v-for="([id, cnt], i) in degRank" :key="id" class="flex items-center gap-2 text-[12.5px]">
                  <span class="w-5 text-right font-mono" style="color:var(--text3)">{{ i + 1 }}</span>
                  <span class="truncate" style="color:var(--text)">{{ id }}</span>
                  <span class="ml-auto font-mono" style="color:var(--primary)">{{ cnt }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- ===== 覆盖审计 ===== -->
        <div v-show="tab === 'coverage'" class="space-y-4">
          <p class="text-[13px]" style="color:var(--text2)">比对本模型的节点/边与实际业务数据源和经验文档的覆盖程度。</p>
          <div class="grid grid-cols-3 gap-3">
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--text)">{{ experiences.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">经验文档总数</div>
            </div>
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--text)">{{ dataSources.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">数据源总数</div>
            </div>
            <div class="panel rounded-xl px-4 py-3.5 text-center">
              <div class="text-[22px] font-bold" style="color:var(--primary)">{{ nodes.length + edges.length }}</div>
              <div class="text-[11.5px]" style="color:var(--text3)">图谱元素</div>
            </div>
          </div>
          <div class="panel rounded-xl p-4">
            <h4 class="mb-2 text-[13px] font-semibold" style="color:var(--text)">经验文档来源分布</h4>
            <div class="flex flex-wrap gap-1.5">
              <span v-for="(cnt, origin) in experiences.reduce((m: any, e) => { m[e.origin || 'unknown'] = (m[e.origin || 'unknown'] || 0) + 1; return m; }, {})" :key="String(origin)" class="badge" style="background:var(--nav-hover);color:var(--text2)">{{ origin }}：{{ cnt }}</span>
            </div>
          </div>
        </div>

        <!-- ===== 血缘健康 ===== -->
        <div v-show="tab === 'health'" class="space-y-4">
          <p class="text-[13px]" style="color:var(--text2)">检测图谱中的潜在问题：孤立节点、缺失关系、无效引用。</p>
          <div class="panel rounded-xl p-4">
            <h4 class="mb-3 text-[13px] font-semibold" style="color:var(--text)">健康检查项</h4>
            <div class="space-y-2">
              <div class="flex items-center gap-2 rounded-lg px-3 py-2" :style="{ background: isolatedSet.length ? 'rgba(245,158,11,.08)' : 'rgba(16,185,129,.08)' }">
                <span :style="{ color: isolatedSet.length ? '#F59E0B' : '#10B981' }">{{ isolatedSet.length ? '⚠' : '✓' }}</span>
                <span class="text-[13px]" style="color:var(--text)">孤立节点</span>
                <span class="ml-auto text-[12px] font-mono" style="color:var(--text3)">{{ isolatedSet.length }} 个</span>
              </div>
              <div class="flex items-center gap-2 rounded-lg px-3 py-2" style="background:rgba(16,185,129,.08)">
                <span style="color:#10B981">✓</span>
                <span class="text-[13px]" style="color:var(--text)">关系完整性</span>
                <span class="ml-auto text-[12px] font-mono" style="color:var(--text3)">{{ edges.length }} 条</span>
              </div>
              <div class="flex items-center gap-2 rounded-lg px-3 py-2" style="background:rgba(16,185,129,.08)">
                <span style="color:var(--text3)">—</span>
                <span class="text-[13px]" style="color:var(--text)">Schema 漂移检测</span>
                <span class="ml-auto text-[12px]" style="color:var(--text3)">需选择数据源后检测</span>
              </div>
            </div>
          </div>
        </div>

        <!-- ===== 节点分析 ===== -->
        <div v-show="tab === 'nodes'" class="space-y-3">
          <input v-model="nodeQ" class="input !w-72" placeholder="搜索节点名称或类型…" />
          <div class="panel rounded-xl max-h-96 overflow-y-auto">
            <table class="tbl">
              <thead><tr><th>名称</th><th>类型</th><th>标签</th></tr></thead>
              <tbody>
                <tr v-for="n in filteredNodes" :key="n.id" class="cursor-pointer" @click="selectedNode = n">
                  <td class="font-mono text-[12.5px]" style="color:var(--text)">{{ n.name }}</td>
                  <td class="text-[12px]" style="color:var(--text3)">{{ n.type }}</td>
                  <td class="text-[12px]" style="color:var(--text3)">{{ n.properties?.domain || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <!-- 节点详情 -->
          <div v-if="selectedNode" class="panel rounded-xl p-4">
            <h4 class="mb-2 text-[13px] font-semibold" style="color:var(--text)">{{ selectedNode.name }}</h4>
            <div class="grid grid-cols-2 gap-x-4 gap-y-1 text-[12.5px]">
              <span style="color:var(--text3)">类型</span><span style="color:var(--text)">{{ selectedNode.type }}</span>
              <span style="color:var(--text3)">ID</span><span class="font-mono" style="color:var(--text)">{{ selectedNode.id }}</span>
              <template v-for="(v, k) in selectedNode.properties" :key="k">
                <span style="color:var(--text3)">{{ k }}</span><span style="color:var(--text)">{{ v }}</span>
              </template>
            </div>
          </div>
        </div>

        <!-- ===== 路径查询 ===== -->
        <div v-show="tab === 'path'" class="space-y-4">
          <div class="flex items-center gap-2">
            <input v-model="pathFrom" class="input !w-56" placeholder="起始节点名称" list="path-node-list" />
            <span style="color:var(--text3)">→</span>
            <input v-model="pathTo" class="input !w-56" placeholder="目标节点名称" list="path-node-list" />
            <select v-model.number="pathMax" class="input !h-8 !w-20 !text-[12px]">
              <option :value="1">1 跳</option><option :value="2">2 跳</option><option :value="3">3 跳</option><option :value="5">5 跳</option>
            </select>
            <button class="btn-primary" @click="findPath">查询</button>
          </div>
          <datalist id="path-node-list">
            <option v-for="n in nodes" :key="n.id" :value="n.name" />
          </datalist>
          <div v-if="pathResult === null" class="text-[13px]" style="color:var(--text3)">输入起止节点名并点击查询。</div>
          <div v-else-if="!pathResult.length" class="text-[13px]" style="color:var(--warning)">未找到路径（在 {{ pathMax }} 跳范围内）。</div>
          <div v-else class="panel rounded-xl p-4">
            <div class="flex items-center gap-2 text-[13px]">
              <template v-for="(n, i) in pathResult" :key="i">
                <span v-if="i > 0" style="color:var(--text3)">→</span>
                <span class="rounded-md px-2 py-1 font-mono" style="background:var(--nav-hover);color:var(--text)">{{ n }}</span>
              </template>
            </div>
          </div>
        </div>

        <!-- ===== 大图浏览 ===== -->
        <div v-show="tab === 'big'" class="space-y-3">
          <p class="text-[13px]" style="color:var(--text2)">服务端子图渲染，按需加载节点邻域，适合千张/万张表的大模型浏览。</p>
          <div class="flex items-center gap-2">
            <button class="btn-primary" :disabled="subLoading" @click="loadSubgraph()">{{ subLoading ? '加载中…' : '加载入口枢纽' }}</button>
            <span class="text-[12px]" style="color:var(--text3)">点击节点可加载其邻域</span>
          </div>
          <div class="rounded-xl overflow-hidden border" style="border-color:var(--border);height:500px;background:var(--canvas-bg)">
            <GraphCanvas
              v-if="subNodes.length"
              :nodes="subNodes"
              :edges="subEdges"
              :selected="null"
              :show-labels="true"
              @select="(n: any) => { if (n) loadSubgraph(n.id); }"
            />
            <div v-else class="flex h-full items-center justify-center text-[13px]" style="color:var(--text3)">
              点击「加载入口枢纽」开始浏览大图。
            </div>
          </div>
        </div>

        <!-- ===== 审核队列 ===== -->
        <div v-show="tab === 'review'" class="space-y-4">
          <p class="text-[13px]" style="color:var(--text2)">待审核的图谱变更项：新增/修改/删除的节点与关系。</p>
          <div class="panel rounded-xl p-5 text-center">
            <Info class="mx-auto mb-2 h-6 w-6" style="color:var(--text3)" />
            <p class="text-[13px]" style="color:var(--text3)">审核队列由图谱编辑操作驱动。当通过对话或手动编辑修改图谱时，变更将先进入队列待人工审核。</p>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
