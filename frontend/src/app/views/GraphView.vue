<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  Search, ZoomIn, ZoomOut, Maximize, Save, RefreshCw, X, Waypoints, LoaderCircle,
} from 'lucide-vue-next';
import { listOntologies, getOntology, updateOntology, getModelSubgraph } from '../../api/ontology';
import { getPrefs, savePrefs } from '../../api/prefs';
import type { OntologyModel, OntologyNode } from '../../types';
import { useToastStore } from '../stores/toast';
import GraphCanvas from '../components/GraphCanvas.vue';
import NodeDrawer from '../components/NodeDrawer.vue';
import UiDrawer from '../components/UiDrawer.vue';
import UiEmpty from '../components/UiEmpty.vue';

const route = useRoute();
const toast = useToastStore();

const models = ref<OntologyModel[]>([]);
const currentId = ref('');
const model = ref<OntologyModel | null>(null);
const loading = ref(true);
const saving = ref(false);
const dirty = ref(false);
const query = ref('');
const selected = ref<OntologyNode | null>(null);
const highlight = ref<Set<string> | null>(null);
const traceInfo = ref('');
const showLabels = ref(true);
const canvas = ref<InstanceType<typeof GraphCanvas> | null>(null);

const nodes = computed(() => model.value?.graphData?.nodes || []);
const edges = computed(() => model.value?.graphData?.edges || []);

onMounted(async () => {
  try {
    const prefs = await getPrefs().catch(() => null);
    if (prefs) showLabels.value = prefs.showEdgeLabels !== false;
    models.value = await listOntologies();
    const fromQuery = route.query.model as string;
    const first = models.value.find((m) => m.id === fromQuery) || models.value[0];
    if (first) await openModel(first.id);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    loading.value = false;
  }
});

watch(currentId, (id) => { if (id && id !== model.value?.id) openModel(id); });

async function openModel(id: string) {
  loading.value = true;
  selected.value = null;
  highlight.value = null;
  traceInfo.value = '';
  try {
    currentId.value = id;
    model.value = await getOntology(id);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

function onSelect(n: OntologyNode | null) {
  selected.value = n;
}

function onMoved() { dirty.value = true; }

async function saveLayout() {
  if (!model.value || !canvas.value) return;
  saving.value = true;
  try {
    const posMap = canvas.value.collectPositions();
    for (const n of nodes.value) {
      const p = posMap[n.id];
      if (p) { n.x = p.x; n.y = p.y; }
    }
    await updateOntology(model.value.id, model.value);
    dirty.value = false;
    toast.success('布局已保存');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function trace(n: OntologyNode, dir: 'up' | 'down') {
  if (!model.value) return;
  try {
    const sub = await getModelSubgraph(model.value.id, { node: n.id, depth: 3, dir, limit: 400 });
    highlight.value = new Set(sub.nodes.map((x) => x.id));
    traceInfo.value = `「${n.label}」${dir === 'up' ? '上游' : '下游'}追溯：${sub.nodes.length} 个节点`;
    selected.value = null;
  } catch (e) {
    toast.error((e as Error).message);
  }
}

function clearTrace() {
  highlight.value = null;
  traceInfo.value = '';
}

function jump(nodeId: string) {
  const n = nodes.value.find((x) => x.id === nodeId);
  if (n) selected.value = n;
}

async function toggleLabels() {
  showLabels.value = !showLabels.value;
  savePrefs({ showEdgeLabels: showLabels.value }).catch(() => {});
}
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 工具栏 -->
    <div class="flex shrink-0 flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-4 py-2.5">
      <select v-model="currentId" class="input h-8 w-56 text-[13px]">
        <option v-for="m in models" :key="m.id" :value="m.id">{{ m.title || m.name || '未命名模型' }}</option>
      </select>

      <div class="relative">
        <Search :size="14" class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
        <input v-model="query" class="input h-8 w-48 pl-8 text-[13px]" placeholder="搜索节点…" />
      </div>

      <div class="ml-auto flex items-center gap-1">
        <button class="btn-ghost btn-sm" title="缩小" @click="canvas?.zoomOut()"><ZoomOut :size="15" /></button>
        <button class="btn-ghost btn-sm" title="放大" @click="canvas?.zoomIn()"><ZoomIn :size="15" /></button>
        <button class="btn-ghost btn-sm" title="适应视图" @click="canvas?.fit()"><Maximize :size="15" /></button>
        <button class="btn-ghost btn-sm" :class="showLabels ? 'text-indigo-600' : ''" title="边标签" @click="toggleLabels">
          标签
        </button>
        <button class="btn-ghost btn-sm" title="重新加载" @click="openModel(currentId)"><RefreshCw :size="15" /></button>
        <button v-if="dirty" class="btn-primary btn-sm" :disabled="saving" @click="saveLayout">
          <LoaderCircle v-if="saving" :size="14" class="animate-spin" /><Save v-else :size="14" /> 保存布局
        </button>
      </div>
    </div>

    <!-- 追溯提示条 -->
    <div v-if="traceInfo" class="flex shrink-0 items-center gap-2 border-b border-indigo-100 bg-indigo-50 px-4 py-2 text-[13px] text-indigo-700">
      <span class="min-w-0 flex-1 truncate">{{ traceInfo }}</span>
      <button class="rounded p-0.5 hover:bg-indigo-100" @click="clearTrace"><X :size="14" /></button>
    </div>

    <!-- 画布 -->
    <div class="relative min-h-0 flex-1">
      <div v-if="loading" class="flex h-full items-center justify-center text-slate-400">
        <LoaderCircle :size="22" class="animate-spin" />
      </div>
      <UiEmpty
        v-else-if="!models.length"
        :icon="Waypoints"
        title="当前工作空间还没有血缘模型"
        description="前往经验库导入材料并发起建图，构建完成后的模型会出现在这里。"
      >
        <router-link :to="{ name: 'experiences', query: { build: '1' } }" class="btn-primary">去建图</router-link>
      </UiEmpty>
      <GraphCanvas
        v-else
        ref="canvas"
        :nodes="nodes"
        :edges="edges"
        :selected-id="selected?.id"
        :highlight="highlight"
        :query="query"
        :show-edge-labels="showLabels"
        @select="onSelect"
        @moved="onMoved"
      />
    </div>

    <UiDrawer :open="!!selected" title="节点详情" @close="selected = null">
      <NodeDrawer
        v-if="selected"
        :node="selected"
        :nodes="nodes"
        :edges="edges"
        @jump="jump"
        @trace="trace"
      />
    </UiDrawer>
  </div>
</template>
