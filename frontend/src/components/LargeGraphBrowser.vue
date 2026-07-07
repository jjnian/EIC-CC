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
  background: #0b0e14; z-index: 40; }
.lgb-head { display: flex; align-items: center; gap: 14px; padding: 10px 16px;
  border-bottom: 1px solid rgba(255,255,255,.08); }
.lgb-title { display: flex; align-items: center; gap: 10px; color: #e8eaed; font-size: 14px; }
.lgb-badge { font-size: 11px; color: #ffcc66; background: rgba(255,180,60,.14);
  padding: 2px 8px; border-radius: 10px; }
.lgb-meta { color: #8a93a5; font-size: 12px; margin-left: auto; }
/* → 对齐 button-preview 的 secondary(玻璃)，紧凑 */
.lgb-close { background: var(--bg-elev); border: 1px solid var(--glass-border);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.06);
  color: #cbd0d6; border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px;
  transition: transform .14s var(--ease-out), box-shadow .2s var(--ease-out), background .2s var(--ease-out), border-color .2s var(--ease-out), color .2s var(--ease-out); }
.lgb-close:hover { color: #fff; background: var(--bg-elev-hi); border-color: var(--glass-border-strong); transform: translateY(-1px); box-shadow: inset 0 1px 0 rgba(255,255,255,.10), 0 5px 16px rgba(4,12,28,.35); }
.lgb-close:active { transform: translateY(.5px); box-shadow: inset 0 2px 5px rgba(2,10,26,.4); }
.lgb-note { padding: 8px 16px; font-size: 11.5px; color: #8a93a5; line-height: 1.5;
  border-bottom: 1px solid rgba(255,255,255,.05); }
.lgb-domains { display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
  padding: 8px 16px; border-bottom: 1px solid rgba(255,255,255,.05); }
.lgb-dl { font-size: 11.5px; color: #77808f; }
.lgb-domain { font-size: 11px; color: #cbd0d6; background: rgba(255,255,255,.05);
  padding: 2px 8px; border-radius: 10px; }
.lgb-more { font-size: 11px; color: #77808f; }
.lgb-toolbar { display: flex; align-items: center; gap: 14px; padding: 8px 16px; font-size: 12px; color: #cbd0d6; }
/* → secondary(玻璃)，紧凑 */
.lgb-btn { background: var(--bg-elev); border: 1px solid var(--glass-border);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.06);
  color: #cbd0d6; border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px;
  transition: transform .14s var(--ease-out), box-shadow .2s var(--ease-out), background .2s var(--ease-out), border-color .2s var(--ease-out), color .2s var(--ease-out); }
.lgb-btn:hover:not(:disabled) { color: #fff; background: var(--bg-elev-hi); border-color: var(--glass-border-strong); transform: translateY(-1px); box-shadow: inset 0 1px 0 rgba(255,255,255,.10), 0 5px 16px rgba(4,12,28,.35); }
.lgb-btn:active:not(:disabled) { transform: translateY(.5px); box-shadow: inset 0 2px 5px rgba(2,10,26,.4); }
.lgb-btn:disabled { opacity: .5; cursor: default; }
.lgb-center { color: #8a93a5; }
.lgb-center b { color: #9ecbff; }
.lgb-trunc { color: #ffcc66; font-size: 11.5px; }
.lgb-loading { color: #8a93a5; font-size: 11.5px; }
.lgb-canvas { flex: 1; position: relative; overflow: hidden; }
.lgb-edit { display: flex; align-items: center; gap: 8px; padding: 10px 16px;
  border-top: 1px solid rgba(255,255,255,.08); background: #0d1017; }
.lgb-edit-ico { font-size: 14px; }
.lgb-edit-input { flex: 1; background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.14);
  border-radius: 8px; padding: 8px 12px; color: #e8eaed; font-size: 12.5px; }
.lgb-edit-input:focus { outline: none; border-color: rgba(47,134,214,.6); }
.lgb-edit-input:disabled { opacity: .6; }
/* 改图提交 = 主操作 → 对齐 button-preview 的 primary(竖向蓝渐变) */
.lgb-edit-btn { background: linear-gradient(180deg, var(--accent-soft), var(--accent) 56%, var(--accent-deep));
  border: 1px solid var(--accent-deep); color: #fff; font-weight: 600;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.26), 0 1px 2px rgba(4,18,44,.3), 0 2px 8px rgba(47,134,214,.22);
  text-shadow: 0 1px 1px rgba(2,16,48,.35);
  border-radius: 8px; padding: 8px 16px; cursor: pointer; font-size: 12.5px;
  transition: transform .14s var(--ease-out), box-shadow .2s var(--ease-out), background .2s var(--ease-out); }
.lgb-edit-btn:hover:not(:disabled) { background: linear-gradient(180deg, #82c0ff, var(--accent-soft) 56%, var(--accent)); transform: translateY(-1px); box-shadow: inset 0 1px 0 rgba(255,255,255,.36), 0 2px 4px rgba(4,18,44,.3), 0 5px 15px rgba(47,134,214,.32); }
.lgb-edit-btn:active:not(:disabled) { transform: translateY(.5px); box-shadow: inset 0 2px 6px rgba(2,16,48,.45), 0 1px 3px var(--accent-glow); }
.lgb-edit-btn:disabled { opacity: .5; cursor: default; }
.lgb-edit-toggle { display: flex; align-items: center; gap: 4px; font-size: 11.5px; color: #9aa2b0;
  white-space: nowrap; cursor: pointer; }
</style>
