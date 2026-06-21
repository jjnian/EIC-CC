<script setup lang="ts">
import { computed, ref, type PropType } from 'vue';
import type { PredictionMsg } from '../../composables/useConversations';
import RawPromptDialog from '../RawPromptDialog.vue';

const props = defineProps({
  prediction: { type: Object as PropType<PredictionMsg>, required: true },
});

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
  (e: 'abort'): void;
  (e: 'explain-node', nodeId: string): void;
}>();

const credFilter = ref(0); // 0..1
const filteredSteps = computed(() => {
  if (credFilter.value <= 0) return props.prediction.steps;
  return props.prediction.steps.filter(s => (s.confidence ?? 1) >= credFilter.value);
});

const statusLabel = computed(() => {
  switch (props.prediction.status) {
    case 'running': return '运行中…';
    case 'done':    return '已完成';
    case 'error':   return '出错';
    case 'aborted': return '已停止';
    default:        return '';
  }
});

const intentLabel = computed(() =>
  props.prediction.intent === 'backward' ? '溯因 (Backward)' : '正向推演 (Forward)'
);

const seedLabel = (s: { id: string; label: string }) => s.label || s.id;
const confidencePct = (c?: number) => c == null ? '' : Math.round(c * 100) + '%';

// P1-8：原始 prompt 查看
const rawPromptOpen = ref(false);
const canShowRawPrompt = computed(() =>
  !!props.prediction.branchId && props.prediction.status !== 'running'
);
</script>

<template>
  <div class="pmsg" :class="`pmsg-${prediction.status}`">
    <div class="pmsg-head">
      <span class="pmsg-icon">⚡</span>
      <span class="pmsg-title">{{ intentLabel }}</span>
      <span class="pmsg-status">{{ statusLabel }}</span>
      <button
        v-if="canShowRawPrompt"
        class="pmsg-rawprompt"
        type="button"
        @click="rawPromptOpen = true"
        title="查看本次推演的原始 prompt"
      >📜 prompt</button>
      <button
        v-if="prediction.status === 'running'"
        class="pmsg-stop"
        type="button"
        @click="emit('abort')"
        title="停止推演"
      >
        <svg viewBox="0 0 24 24" width="10" height="10" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
        停止
      </button>
    </div>

    <div v-if="prediction.seeds.length" class="pmsg-meta">
      <span class="pmsg-meta-k">{{ prediction.intent === 'backward' ? '目标' : '起点' }}</span>
      <span
        v-for="s in prediction.seeds"
        :key="s.id"
        class="pmsg-seed"
        @click="emit('focus-node', s.id)"
        :title="'点击聚焦节点 ' + seedLabel(s)"
      >{{ seedLabel(s) }}</span>
    </div>
    <div v-if="prediction.prompt" class="pmsg-prompt" :title="prediction.prompt">
      <span class="pmsg-prompt-k">提示</span>
      <span class="pmsg-prompt-v">{{ prediction.prompt }}</span>
    </div>

    <div v-if="prediction.status === 'error' && prediction.error" class="pmsg-error">
      {{ prediction.error }}
    </div>

    <div v-if="prediction.steps.length" class="pmsg-steps">
      <div
        v-for="(s, i) in filteredSteps"
        :key="s.nodeId + ':' + s.step"
        class="pmsg-step"
        @click="emit('focus-node', s.nodeId)"
        :title="'点击聚焦节点 ' + s.label"
      >
        <div class="pmsg-step-num">{{ s.step }}</div>
        <div class="pmsg-step-body">
          <div class="pmsg-step-head">
            <span class="pmsg-step-label">{{ s.label }}</span>
            <span class="pmsg-step-type">{{ s.type }}</span>
            <span v-if="s.confidence != null" class="pmsg-step-conf">{{ confidencePct(s.confidence) }}</span>
          </div>
          <div v-if="s.explanation" class="pmsg-step-exp">{{ s.explanation }}</div>
          <div v-if="s.triggeredBy?.length" class="pmsg-step-meta">
            <span class="pmsg-step-arrow">{{ prediction.intent === 'backward' ? '→' : '↑' }}</span>
            {{ prediction.intent === 'backward' ? '导致' : '由' }}
            <span v-for="(id, j) in s.triggeredBy" :key="id" class="pmsg-step-ref">
              <a class="pmsg-step-reflink" @click.stop="emit('focus-node', id)">{{ id }}</a><span v-if="j < (s.triggeredBy?.length || 0) - 1">,</span>
            </span>
            {{ prediction.intent === 'backward' ? '' : '触发' }}
            <span v-if="s.ruleId" class="pmsg-step-rule">· 规则 {{ s.ruleId }}</span>
          </div>
        </div>
        <div v-if="i < filteredSteps.length - 1" class="pmsg-step-line" />
      </div>
    </div>

    <div v-else-if="prediction.status === 'running'" class="pmsg-loading">
      <span class="pmsg-spin"/>
      正在生成推演链…
    </div>

    <div v-if="prediction.steps.length > 1" class="pmsg-filter">
      <span class="pmsg-filter-k">置信度 ≥ {{ Math.round(credFilter * 100) }}%</span>
      <input type="range" min="0" max="0.9" step="0.05" v-model.number="credFilter" class="pmsg-filter-slider"/>
    </div>

    <div v-if="prediction.pruneDetails?.length" class="pmsg-prune">
      <div class="pmsg-prune-title">🔗 剪枝 · {{ prediction.pruneDetails.length }} 个节点</div>
      <div v-for="d in prediction.pruneDetails" :key="d.nodeId" class="pmsg-prune-item">
        <span class="pmsg-prune-label" @click="emit('focus-node', d.nodeId)">{{ d.label || d.nodeId }}</span>
        <span class="pmsg-prune-reason">{{ d.reason }}</span>
      </div>
    </div>

    <!-- P1-8：原始 prompt 弹窗 -->
    <RawPromptDialog
      v-if="prediction.branchId"
      :open="rawPromptOpen"
      :scenario-id="prediction.branchId"
      @close="rawPromptOpen = false"
    />
  </div>
</template>

<style scoped>
.pmsg {
  background: rgba(251, 191, 36, 0.05);
  border: 1px solid rgba(251, 191, 36, 0.28);
  border-radius: 14px;
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-main);
  max-width: 540px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pmsg-running { box-shadow: 0 0 0 1px rgba(251, 191, 36, 0.25), 0 4px 16px rgba(251, 191, 36, 0.08); }
.pmsg-error { border-color: rgba(255, 99, 99, 0.4); background: rgba(255, 99, 99, 0.05); }
.pmsg-aborted { border-color: rgba(255, 255, 255, 0.15); background: rgba(255, 255, 255, 0.03); opacity: 0.85; }

.pmsg-head { display: flex; align-items: center; gap: 8px; }
.pmsg-icon { font-size: 14px; }
.pmsg-title { font-weight: 600; color: #fbbf24; }
.pmsg-status {
  margin-left: auto; font-size: 11px;
  padding: 2px 8px; border-radius: 100px;
  background: rgba(255,255,255,0.05); color: var(--text-dim);
}
.pmsg-running .pmsg-status { background: rgba(251, 191, 36, 0.15); color: #fbbf24; }
.pmsg-done .pmsg-status { background: rgba(34, 221, 136, 0.15); color: #22dd88; }
.pmsg-error .pmsg-status { background: rgba(255, 99, 99, 0.15); color: #ff8a8a; }
.pmsg-aborted .pmsg-status { background: rgba(255, 255, 255, 0.08); }
.pmsg-stop {
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main); font-family: inherit; font-size: 11px;
  padding: 3px 8px; border-radius: 6px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 4px;
}
.pmsg-stop:hover { background: rgba(255,255,255,0.16); }
/* P1-8：原始 prompt 按钮 */
.pmsg-rawprompt {
  background: rgba(99, 179, 237, 0.1); border: 1px solid rgba(99, 179, 237, 0.25);
  color: #93c5fd; font-family: inherit; font-size: 10.5px;
  padding: 2px 8px; border-radius: 6px; cursor: pointer;
  margin-left: 6px;
}
.pmsg-rawprompt:hover { background: rgba(99, 179, 237, 0.2); }

.pmsg-meta, .pmsg-prompt {
  display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px;
  font-size: 12px;
}
.pmsg-meta-k, .pmsg-prompt-k {
  color: var(--text-dim); font-size: 10.5px; text-transform: uppercase; letter-spacing: 1px;
  flex-shrink: 0;
}
.pmsg-seed {
  background: rgba(99, 179, 237, 0.15); border: 1px solid rgba(99, 179, 237, 0.3);
  color: #93c5fd; padding: 1px 8px; border-radius: 100px; cursor: pointer;
  transition: background .12s;
}
.pmsg-seed:hover { background: rgba(99, 179, 237, 0.25); }
.pmsg-prompt-v {
  color: var(--text-main); font-size: 12.5px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  flex: 1; min-width: 0;
}

.pmsg-error { color: #ff8a8a; font-size: 12px; }

.pmsg-steps { display: flex; flex-direction: column; gap: 8px; margin-top: 2px; }
.pmsg-step {
  position: relative;
  display: flex; gap: 10px;
  padding: 8px 10px;
  background: rgba(0,0,0,0.25);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 10px;
  cursor: pointer; transition: background .12s, border-color .12s;
}
.pmsg-step:hover {
  background: rgba(251, 191, 36, 0.06);
  border-color: rgba(251, 191, 36, 0.3);
}
.pmsg-step-num {
  flex-shrink: 0;
  width: 22px; height: 22px; border-radius: 50%;
  background: rgba(251, 191, 36, 0.18); color: #fbbf24;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; font-family: 'JetBrains Mono', monospace;
}
.pmsg-step-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.pmsg-step-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.pmsg-step-label { font-weight: 500; color: var(--text-main); font-size: 13px; }
.pmsg-step-type {
  font-size: 10px; padding: 1px 6px; border-radius: 3px;
  background: rgba(255,255,255,0.06); color: var(--text-dim);
  text-transform: lowercase;
}
.pmsg-step-conf {
  margin-left: auto; font-size: 10.5px; font-family: 'JetBrains Mono', monospace;
  color: #fbbf24; opacity: 0.85;
}
.pmsg-step-exp { font-size: 12px; color: rgba(255,255,255,0.7); }
.pmsg-step-meta {
  font-size: 11px; color: rgba(255,255,255,0.45);
  display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
}
.pmsg-step-arrow { color: rgba(251, 191, 36, 0.6); }
.pmsg-step-reflink {
  color: rgba(99, 179, 237, 0.85);
  font-family: 'JetBrains Mono', monospace; font-size: 10.5px;
  cursor: pointer; text-decoration: none;
}
.pmsg-step-reflink:hover { color: #93c5fd; text-decoration: underline; }
.pmsg-step-rule { color: #ff80bf; }
.pmsg-step-line {
  position: absolute; left: 21px; bottom: -8px; width: 2px; height: 8px;
  background: rgba(251, 191, 36, 0.2);
}

.pmsg-loading {
  display: flex; align-items: center; gap: 8px;
  color: var(--text-dim); font-size: 12px;
  padding: 8px 4px;
}
.pmsg-spin {
  width: 12px; height: 12px;
  border: 2px solid rgba(251, 191, 36, 0.3);
  border-top-color: #fbbf24;
  border-radius: 50%;
  animation: pmsg-spin 0.9s linear infinite;
}
@keyframes pmsg-spin { to { transform: rotate(360deg); } }

.pmsg-filter {
  display: flex; align-items: center; gap: 8px;
  font-size: 11px; color: var(--text-dim);
}
.pmsg-filter-k { flex-shrink: 0; font-family: 'JetBrains Mono', monospace; }
.pmsg-filter-slider { flex: 1; accent-color: #fbbf24; }

.pmsg-prune {
  border-top: 1px dashed rgba(255,255,255,0.08);
  padding-top: 8px;
  font-size: 12px;
  display: flex; flex-direction: column; gap: 4px;
}
.pmsg-prune-title { font-size: 11px; color: #ff80bf; font-weight: 500; }
.pmsg-prune-item {
  display: flex; gap: 6px; align-items: baseline;
  color: rgba(255,255,255,0.6); font-size: 11.5px;
}
.pmsg-prune-label {
  color: #93c5fd; cursor: pointer;
  font-family: 'JetBrains Mono', monospace; font-size: 11px;
}
.pmsg-prune-label:hover { text-decoration: underline; }
.pmsg-prune-reason { flex: 1; }
</style>
