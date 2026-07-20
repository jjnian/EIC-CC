<script setup lang="ts">
import { ref, onMounted } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { getModelSubgraph, getModelDomains, chatEditModel, type DomainRollup } from '../api/ontology';
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

// ── 对话驱动精准改图：自然语言 → 服务端检索相关子图 → LLM 产 patch → 局部应用 ──
const editMsg = ref('');
const editing = ref(false);
// 全图语义检索(向量)：关时用当前可见子图作上下文(快、免读整图)；开时对全图做向量语义检索，
// 适合请求引用了视图外的实体(如「把订单连到库存」而库存不在当前视图)。
const wholeGraphSearch = ref(false);
const nodeCountLive = ref(props.nodeCount);
const edgeCountLive = ref(props.edgeCount);
const submitEdit = async () => {
  const msg = editMsg.value.trim();
  if (!msg || editing.value) return;
  editing.value = true;
  try {
    // 关：办法 B(可见子图作上下文，免读整图)；开：办法 A(全图向量语义检索，不发 scope)
    const scopeNodeIds = wholeGraphSearch.value ? undefined : subNodes.value.map(n => n.id);
    const r = await chatEditModel(props.modelId, msg, { scopeNodeIds });
    nodeCountLive.value = r.nodeCount;
    edgeCountLive.value = r.edgeCount;
    if (r.applied > 0) {
      toast.success(`${r.reply || '已修改'}（应用 ${r.applied} 处${r.skipped ? `，跳过 ${r.skipped}` : ''}）`);
      editMsg.value = '';
      // 局部改动已落库：重载当前中心子图以反映变化
      await load(subSel.value || undefined, centerLabel.value === '入口枢纽' ? undefined : centerLabel.value);
    } else {
      toast.warn(r.reply || '未做修改（可换个说法，或先点到相关节点附近再改）');
    }
  } catch (e) {
    toast.error(`改图失败：${(e as Error).message}`);
  } finally {
    editing.value = false;
  }
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
      <div class="lgb-meta">共 {{ nodeCountLive }} 节点 · {{ edgeCountLive }} 边 · {{ domainCount }} 领域</div>
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

    <!-- 对话驱动精准改图：只发相关子图给 LLM，局部落库，大图也能改 -->
    <div class="lgb-edit">
      <span class="lgb-edit-ico">✏️</span>
      <input
        v-model="editMsg"
        class="lgb-edit-input"
        :disabled="editing"
        placeholder="用一句话修改这张图：如「把订单连到发票，关系 produces」「删除孤立的备注节点」「客户和订单的血缘方向标反了，改过来」"
        @keydown.enter="submitEdit"
      />
      <label class="lgb-edit-toggle" title="开启后对全图做向量语义检索，适合请求引用了当前视图外的实体；关闭则只改当前可见部分（更快）">
        <input type="checkbox" v-model="wholeGraphSearch" :disabled="editing" /> 全图语义检索
      </label>
      <button class="lgb-edit-btn" :disabled="editing || !editMsg.trim()" @click="submitEdit">
        {{ editing ? '修改中…' : '发送' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.lgb-overlay { position: absolute; inset: 0; display: flex; flex-direction: column;
  background: var(--bg-base, #fff); z-index: 40; }
.lgb-head { display: flex; align-items: center; gap: 14px; padding: 10px 16px;
  border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); }
.lgb-title { display: flex; align-items: center; gap: 10px; color: var(--text-main, #18181b); font-size: 14px; }
.lgb-badge { font-size: 11px; color: #d97706; background: rgba(217,119,6,0.10);
  padding: 2px 8px; border-radius: 10px; }
.lgb-meta { color: var(--text-dim, #52525b); font-size: 12px; margin-left: auto; }
.lgb-close { background: var(--bg-elev, rgba(0,0,0,0.045)); border: 1px solid var(--glass-border, rgba(0,0,0,0.09));
  color: var(--text-dim, #52525b); border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px; }
.lgb-close:hover { color: var(--text-main, #18181b); border-color: rgba(0,0,0,0.2); }
.lgb-note { padding: 8px 16px; font-size: 11.5px; color: var(--text-dim, #52525b); line-height: 1.5;
  border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); }
.lgb-domains { display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
  padding: 8px 16px; border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); }
.lgb-dl { font-size: 11.5px; color: var(--text-muted, #a1a1aa); }
.lgb-domain { font-size: 11px; color: var(--text-dim, #52525b); background: var(--bg-elev, rgba(0,0,0,0.045));
  padding: 2px 8px; border-radius: 10px; }
.lgb-more { font-size: 11px; color: var(--text-muted, #a1a1aa); }
.lgb-toolbar { display: flex; align-items: center; gap: 14px; padding: 8px 16px; font-size: 12px; color: var(--text-dim, #52525b); }
.lgb-btn { background: rgba(37,99,235,0.06); border: 1px solid rgba(37,99,235,0.35);
  color: #2563eb; border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px; }
.lgb-btn:hover:not(:disabled) { border-color: rgba(37,99,235,0.6); }
.lgb-btn:disabled { opacity: .5; cursor: default; }
.lgb-center { color: var(--text-dim, #52525b); }
.lgb-center b { color: #2563eb; }
.lgb-trunc { color: #d97706; font-size: 11.5px; }
.lgb-loading { color: var(--text-dim, #52525b); font-size: 11.5px; }
.lgb-canvas { flex: 1; position: relative; overflow: hidden; }
.lgb-edit { display: flex; align-items: center; gap: 8px; padding: 10px 16px;
  border-top: 1px solid var(--hairline, rgba(0,0,0,0.07)); background: var(--bg-subtle, #f7f8fa); }
.lgb-edit-ico { font-size: 14px; }
.lgb-edit-input { flex: 1; background: #fff; border: 1px solid var(--glass-border, rgba(0,0,0,0.09));
  border-radius: 8px; padding: 8px 12px; color: var(--text-main, #18181b); font-size: 12.5px; }
.lgb-edit-input:focus { outline: none; border-color: rgba(0,0,0,0.35); }
.lgb-edit-input:disabled { opacity: .6; }
.lgb-edit-btn { background: var(--accent, #18181b); border: 1px solid var(--accent, #18181b);
  color: #fff; border-radius: 8px; padding: 8px 16px; cursor: pointer; font-size: 12.5px; }
.lgb-edit-btn:hover:not(:disabled) { background: var(--accent-soft, #3f3f46); }
.lgb-edit-btn:disabled { opacity: .5; cursor: default; }
.lgb-edit-toggle { display: flex; align-items: center; gap: 4px; font-size: 11.5px; color: var(--text-dim, #52525b);
  white-space: nowrap; cursor: pointer; }
</style>
