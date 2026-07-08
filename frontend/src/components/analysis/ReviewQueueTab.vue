<script setup lang="ts">
import { ref, computed } from 'vue';
import type { OntologyNode, OntologyEdge } from '../../types';
import { toast } from '../../composables/useToast';
import { Button } from '@/components/ui/button';

/**
 * 审核队列：把「贴合实际」的最后一公里交给懂业务的人。
 * 队列 = 低置信 / 孤证的推断边（LLM 猜的、没被数据或多来源佐证的关系），
 * 专家逐条【确认】（review_status=confirmed、置信度提升）或【否决】（删边）。
 * 变更走现有 update-edge-schema / delete-edge 事件 → 编辑器快照 + 自动持久化。
 */

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
  (e: 'delete-edge', edgeId: string): void;
}>();

/** 确认后的置信度下限：专家点头 ≥ 多源佐证，但仍低于外键/SQL 定义体的确定性 1.0。 */
const CONFIRM_CONF = 0.9;
/** 进入队列的置信度阈值。 */
const REVIEW_CONF_THRESHOLD = 0.7;
const RENDER_CAP = 100;

const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));
const nodeName = (id: string) => nmap.value[id]?.label || id;

const evidenceCount = (e: OntologyEdge) =>
  e.evidences?.length ?? ((e.evidence || '').trim() ? 1 : 0);

/** 是否需要人工把关：未确认，且（推断 / 低置信 / 无任何证据）。 */
const needsReview = (e: OntologyEdge) => {
  if (e.review_status === 'confirmed') return false;
  if (e.source === 'inferred') return true;
  if ((e.confidence ?? 1) < REVIEW_CONF_THRESHOLD) return true;
  return e.source !== 'derived' && e.source !== 'manual' && evidenceCount(e) === 0;
};

// 待审队列：置信度升序（最可疑的排最前）、同置信度孤证优先
const queue = computed(() =>
  props.edges
    .filter(needsReview)
    .sort((a, b) =>
      (a.confidence ?? 0.5) - (b.confidence ?? 0.5) || evidenceCount(a) - evidenceCount(b)));

const confirmedCount = computed(() =>
  props.edges.filter(e => e.review_status === 'confirmed').length);

// 每行的批注输入（展开态）
const noteOpen = ref<Record<string, boolean>>({});
const notes = ref<Record<string, string>>({});
const toggleNote = (id: string) => { noteOpen.value = { ...noteOpen.value, [id]: !noteOpen.value[id] }; };

const confirmEdge = (e: OntologyEdge) => {
  const patch: Record<string, any> = {
    review_status: 'confirmed',
    reviewed_at: Date.now(),
    confidence: Math.max(e.confidence ?? 0, CONFIRM_CONF),
  };
  const note = (notes.value[e.id] || '').trim();
  if (note) patch.review_note = note;
  emit('update-edge-schema', e.id, patch);
  toast.success(`已确认「${nodeName(e.from)} → ${nodeName(e.to)}」`);
};

const rejectEdge = (e: OntologyEdge) => {
  emit('delete-edge', e.id);
  toast.info(`已否决并删除「${nodeName(e.from)} → ${nodeName(e.to)}」`);
};

const confirmVisible = () => {
  const list = queue.value.slice(0, RENDER_CAP);
  for (const e of list) confirmEdge(e);
};

const srcTag = (e: OntologyEdge) =>
  e.source === 'inferred' ? { text: '推断', color: '#ffcc44' }
  : e.source === 'derived' ? { text: '派生', color: '#22dd88' }
  : e.source === 'manual' ? { text: '手工', color: '#4a8df0' }
  : { text: '未标来源', color: '#999' };
</script>

<template>
  <div class="rq-wrap">
    <div class="gap-section">
      <div class="gap-sec-title">人工审核队列</div>
      <div class="rq-note">
        低置信 / 孤证的推断关系需要业务专家把关：<b>确认</b>后标记为已审核并把置信度提升到
        {{ (CONFIRM_CONF * 100).toFixed(0) }}%；<b>否决</b>即从图中删除该边（可撤销 / 有版本快照）。
      </div>
      <div class="rq-stats">
        <span class="rq-stat warn" v-if="queue.length">待审 {{ queue.length }} 条</span>
        <span class="rq-stat ok" v-else>✅ 没有待审核的边</span>
        <span class="rq-stat" v-if="confirmedCount">已确认 {{ confirmedCount }} 条</span>
        <Button v-if="queue.length > 1" size="sm" variant="outline" class="rq-all"
                @click="confirmVisible" title="把当前列出的边全部标记为已确认">全部确认</Button>
      </div>
    </div>

    <div v-if="queue.length" class="gap-section">
      <div class="rq-rows">
        <div v-for="e in queue.slice(0, RENDER_CAP)" :key="e.id" class="rq-row">
          <div class="rq-row-main">
            <button class="rq-row-label" :title="e.rel_type || ''" @click="emit('focus-node', e.from)">
              {{ nodeName(e.from) }} → {{ nodeName(e.to) }}
            </button>
            <span v-if="e.label" class="rq-rel">{{ e.label }}</span>
            <span class="rq-tag" :style="{ color: srcTag(e).color }">{{ srcTag(e).text }}</span>
            <span class="rq-conf">{{ e.confidence != null ? (e.confidence * 100).toFixed(0) + '%' : '—' }}</span>
            <span v-if="evidenceCount(e) > 1" class="rq-tag" style="color:#34d399">🔗×{{ evidenceCount(e) }}</span>
            <span v-else-if="evidenceCount(e) === 0" class="rq-tag" style="color:#ff7755">无证据</span>
            <span v-else class="rq-tag" style="color:#999">孤证</span>
          </div>
          <div v-if="e.evidence" class="rq-evidence" :title="(e.evidences || []).join('\n')">❝ {{ e.evidence }}</div>
          <div class="rq-actions">
            <Button size="sm" variant="outline" @click="confirmEdge(e)">✅ 确认</Button>
            <Button size="sm" variant="ghost" class="text-destructive" @click="rejectEdge(e)">✗ 否决删边</Button>
            <Button size="sm" variant="ghost" @click="toggleNote(e.id)">{{ noteOpen[e.id] ? '收起批注' : '批注' }}</Button>
          </div>
          <input v-if="noteOpen[e.id]" v-model="notes[e.id]" class="rq-input"
                 placeholder="可选：确认理由 / 业务口径说明（随确认一并保存）" />
        </div>
        <div v-if="queue.length > RENDER_CAP" class="rq-more">…共 {{ queue.length }} 条，处理后自动补进</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rq-wrap { display: flex; flex-direction: column; gap: 14px; }
.gap-section { display: flex; flex-direction: column; gap: 8px; }
.gap-sec-title { font-size: 12px; color: #9aa3b2; font-weight: 600; }
.rq-note { font-size: 12px; color: #8b93a3; line-height: 1.6; }
.rq-note b { color: #c6cede; }
.rq-stats { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.rq-stat { font-size: 12px; color: #9aa3b2; }
.rq-stat.warn { color: #ffcc44; font-weight: 600; }
.rq-stat.ok { color: #22dd88; }
.rq-all { margin-left: auto; }
.rq-rows { display: flex; flex-direction: column; gap: 10px; }
.rq-row {
  background: rgba(255,255,255,.03);
  border: 1px solid rgba(255,255,255,.07);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex; flex-direction: column; gap: 6px;
}
.rq-row-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rq-row-label {
  background: none; border: none; padding: 0;
  color: #dde3ee; font-size: 13px; cursor: pointer; text-align: left;
}
.rq-row-label:hover { color: #4a8df0; text-decoration: underline; }
.rq-rel { font-size: 12px; color: #8b93a3; }
.rq-tag { font-size: 11px; }
.rq-conf { font-size: 12px; color: #ffcc44; font-variant-numeric: tabular-nums; }
.rq-evidence { font-size: 12px; color: #8b93a3; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rq-actions { display: flex; gap: 6px; }
.rq-input {
  background: rgba(0,0,0,.25); border: 1px solid rgba(255,255,255,.1); border-radius: 6px;
  color: #dde3ee; font-size: 12px; padding: 6px 8px; outline: none;
}
.rq-input:focus { border-color: #4a8df0; }
.rq-more { font-size: 12px; color: #777; }
</style>
