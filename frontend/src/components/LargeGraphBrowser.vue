<script setup lang="ts">
import { ref, onMounted } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { getModelSubgraph, getModelDomains, type DomainRollup } from '../api/ontology';
import { toast } from '../composables/useToast';
import GraphCanvas from './GraphCanvas.vue';

/**
 * 大图只读浏览器（面向千张/万张表的模型）：整图渲染会卡死浏览器，这里改为服务端取子图按需渲染——
 * 领域总览 + 入口枢纽 + 点击节点递归展开其邻域。全程<b>本地只读状态</b>，绝不写回模型（避免持久化截断）。
 */
const props = defineProps<{ modelId: string; title: string; nodeCount: number; edgeCount: number }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const subNodes = ref<OntologyNode[]>([]);
const subEdges = ref<OntologyEdge[]>([]);
const subSel = ref<string | null>(null);
const domains = ref<DomainRollup['domains']>([]);
const domainCount = ref(0);
const loading = ref(false);
const truncated = ref(false);
const centerLabel = ref('入口枢纽');

/** 子图节点重排到一个本地网格：原结构图的全局网格坐标在子集里会很散，本地重排更可读（只读，不落库）。 */
const relayout = (nodes: OntologyNode[]) => {
  const cols = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  return nodes.map((n, i) => ({ ...n, x: (i % cols) * 200, y: Math.floor(i / cols) * 120 }));
};

const load = async (node?: string, label?: string) => {
  if (loading.value) return;
  loading.value = true;
  try {
    const sg = await getModelSubgraph(props.modelId, { node, depth: node ? 2 : 1, dir: 'both', limit: 300 });
    subNodes.value = relayout(sg.nodes || []);
    subEdges.value = sg.edges || [];
    subSel.value = node || null;
    truncated.value = !!sg.truncated;
    centerLabel.value = node ? (label || node) : '入口枢纽';
  } catch (e) {
    toast.error(`子图加载失败：${(e as Error).message}`);
  } finally {
    loading.value = false;
  }
};

// 点击节点 → 以它为中心展开邻域（递归浏览）
const onSelect = (id: string | null) => {
  if (!id) { subSel.value = null; return; }
  const n = subNodes.value.find(x => x.id === id);
  load(id, n?.label);
};

onMounted(async () => {
  load();
  try {
    const d = await getModelDomains(props.modelId);
    domains.value = d.domains || [];
    domainCount.value = domains.value.length;
  } catch { /* 领域总览失败不阻塞子图浏览 */ }
});
</script>

<template>
  <div class="lgb-overlay">
    <header class="lgb-head">
      <div class="lgb-title">
        <b>{{ title }}</b>
        <span class="lgb-badge">大图模式 · 只读浏览</span>
      </div>
      <div class="lgb-meta">共 {{ nodeCount }} 节点 · {{ edgeCount }} 边 · {{ domainCount }} 领域</div>
      <button class="lgb-close" @click="emit('close')">✕ 关闭</button>
    </header>

    <div class="lgb-note">
      整图过大，无法直接渲染。下方为<b>按需子图</b>：点击任一节点即以它为中心展开邻域（2 跳）；「入口枢纽」为度数最高的节点。
    </div>

    <div v-if="domains.length" class="lgb-domains">
      <span class="lgb-dl">领域分布：</span>
      <span v-for="d in domains.slice(0, 24)" :key="d.domain" class="lgb-domain">{{ d.domain }} · {{ d.nodeCount }}</span>
      <span v-if="domains.length > 24" class="lgb-more">…共 {{ domains.length }} 个</span>
    </div>

    <div class="lgb-toolbar">
      <button class="lgb-btn" :disabled="loading" @click="load()">↺ 回到入口枢纽</button>
      <span class="lgb-center">当前中心：<b>{{ centerLabel }}</b></span>
      <span v-if="truncated" class="lgb-trunc">⚠ 邻域较大，已截断到 300 节点</span>
      <span v-if="loading" class="lgb-loading">加载中…</span>
    </div>

    <div class="lgb-canvas">
      <GraphCanvas
        :nodes="subNodes"
        :edges="subEdges"
        :selId="subSel"
        readonly
        @select="onSelect"
      />
    </div>
  </div>
</template>

<style scoped>
.lgb-overlay { position: absolute; inset: 0; display: flex; flex-direction: column;
  background: #0b0e14; z-index: 40; }
.lgb-head { display: flex; align-items: center; gap: 14px; padding: 10px 16px;
  border-bottom: 1px solid rgba(255,255,255,.08); }
.lgb-title { display: flex; align-items: center; gap: 10px; color: #e8eaed; font-size: 14px; }
.lgb-badge { font-size: 11px; color: #ffcc66; background: rgba(255,180,60,.14);
  padding: 2px 8px; border-radius: 10px; }
.lgb-meta { color: #8a93a5; font-size: 12px; margin-left: auto; }
.lgb-close { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12);
  color: #cbd0d6; border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px; }
.lgb-close:hover { color: #fff; border-color: rgba(255,255,255,.25); }
.lgb-note { padding: 8px 16px; font-size: 11.5px; color: #8a93a5; line-height: 1.5;
  border-bottom: 1px solid rgba(255,255,255,.05); }
.lgb-domains { display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
  padding: 8px 16px; border-bottom: 1px solid rgba(255,255,255,.05); }
.lgb-dl { font-size: 11.5px; color: #77808f; }
.lgb-domain { font-size: 11px; color: #cbd0d6; background: rgba(255,255,255,.05);
  padding: 2px 8px; border-radius: 10px; }
.lgb-more { font-size: 11px; color: #77808f; }
.lgb-toolbar { display: flex; align-items: center; gap: 14px; padding: 8px 16px; font-size: 12px; color: #cbd0d6; }
.lgb-btn { background: rgba(47,134,214,.14); border: 1px solid rgba(47,134,214,.35);
  color: #cfe0f5; border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px; }
.lgb-btn:hover:not(:disabled) { border-color: rgba(47,134,214,.7); }
.lgb-btn:disabled { opacity: .5; cursor: default; }
.lgb-center { color: #8a93a5; }
.lgb-center b { color: #9ecbff; }
.lgb-trunc { color: #ffcc66; font-size: 11.5px; }
.lgb-loading { color: #8a93a5; font-size: 11.5px; }
.lgb-canvas { flex: 1; position: relative; overflow: hidden; }
</style>
