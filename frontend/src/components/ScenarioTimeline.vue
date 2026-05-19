<script setup lang="ts">
const props = defineProps<{
  steps: any[];
  loading: boolean;
  nodes: any[];
  intent?: 'forward' | 'backward';
}>();

const isBackward = () => props.intent === 'backward';

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'focus-node', id: string): void;
}>();

const nodeLabel = (id: string, nodes: any[]) => {
  const n = nodes.find(x => x.id === id);
  return n?.label || id;
};
</script>

<template>
  <div class="st-panel">
    <div class="st-head">
      <div class="st-title">
        <span class="st-pulse" v-if="loading" />
        <span class="st-icon">{{ isBackward() ? '←' : '⚡' }}</span>
        <span>{{ isBackward() ? '溯因推演' : '前向推演' }} {{ loading ? '进行中…' : '完成' }}</span>
      </div>
      <button class="st-close" @click="emit('close')">×</button>
    </div>

    <div class="st-body">
      <div v-if="!steps.length && loading" class="st-empty">
        <div class="st-spinner" />
        <span>正在推演中…</span>
      </div>
      <div v-else-if="!steps.length" class="st-empty">暂无推演步骤</div>

      <div v-for="(s, i) in steps" :key="s.nodeId || i" class="st-step" @click="emit('focus-node', s.nodeId)">
        <div class="st-step-rail">
          <div class="st-step-num">{{ s.step }}</div>
          <div class="st-step-line" v-if="i < steps.length - 1" />
        </div>
        <div class="st-step-card">
          <div class="st-step-head">
            <span class="st-step-label">{{ s.label }}</span>
            <div class="st-prob-group">
              <span
                v-if="s.effectiveProbability != null"
                class="st-prob"
                :title="'有效概率（noisy-OR 聚合）'"
              >P={{ Math.round(s.effectiveProbability * 100) }}%</span>
              <span
                v-if="s.confidence != null"
                class="st-conf"
                :title="'LLM 置信度（节点内在）'"
              >c={{ Math.round(s.confidence * 100) }}%</span>
            </div>
          </div>
          <div class="st-step-exp">{{ s.explanation }}</div>
          <div v-if="s.triggeredBy?.length" class="st-step-meta">
            <span class="st-meta-key">{{ isBackward() ? '导致' : '由' }}</span>
            <span v-for="t in s.triggeredBy" :key="t" class="st-meta-chip">{{ nodeLabel(t, nodes) }}</span>
            <span v-if="s.ruleId" class="st-meta-rule">⚡ {{ nodeLabel(s.ruleId, nodes) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.st-panel {
  display: flex; flex-direction: column; height: 100%;
  background: rgba(8, 14, 24, 0.6);
  border-left: 1px solid rgba(255, 255, 255, 0.06);
}
.st-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.st-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: var(--text-main); }
.st-icon { color: #fbbf24; }
.st-pulse {
  width: 8px; height: 8px; border-radius: 50%;
  background: #fbbf24; box-shadow: 0 0 8px #fbbf24;
  animation: stPulse 1.4s infinite;
}
@keyframes stPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(1.3); }
}
.st-close {
  background: rgba(255,255,255,0.06); border: none; color: var(--text-dim);
  width: 26px; height: 26px; border-radius: 50%; cursor: pointer; font-size: 16px;
  display: flex; align-items: center; justify-content: center;
}
.st-close:hover { background: rgba(255, 99, 99, 0.2); color: #ff8a8a; }
.st-body {
  flex: 1; overflow-y: auto;
  padding: 16px 20px;
}
.st-empty {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 40px 20px;
  color: var(--text-dim); font-size: 13px;
}
.st-spinner {
  width: 24px; height: 24px;
  border: 3px solid rgba(251, 191, 36, 0.3);
  border-top-color: #fbbf24;
  border-radius: 50%;
  animation: stSpin 0.8s linear infinite;
}
@keyframes stSpin { to { transform: rotate(360deg); } }

.st-step {
  display: flex; gap: 12px;
  cursor: pointer;
  animation: stIn 0.35s cubic-bezier(.34,1.56,.64,1);
}
@keyframes stIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
.st-step-rail {
  display: flex; flex-direction: column; align-items: center;
  flex-shrink: 0;
}
.st-step-num {
  width: 26px; height: 26px;
  border-radius: 50%;
  background: rgba(251, 191, 36, 0.15);
  border: 1px solid rgba(251, 191, 36, 0.4);
  color: #fbbf24;
  font-size: 11px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  font-family: 'JetBrains Mono', monospace;
}
.st-step-line {
  flex: 1;
  width: 2px;
  background: linear-gradient(to bottom, rgba(251, 191, 36, 0.4), rgba(251, 191, 36, 0.05));
  margin: 4px 0;
  min-height: 16px;
}
.st-step-card {
  flex: 1;
  background: rgba(15, 23, 42, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 8px;
  transition: all 0.15s;
}
.st-step:hover .st-step-card {
  border-color: rgba(251, 191, 36, 0.35);
  background: rgba(251, 191, 36, 0.05);
}
.st-step-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 4px;
}
.st-step-label { font-size: 13px; font-weight: 600; color: var(--text-main); }
.st-prob-group { display: flex; gap: 4px; align-items: center; flex-shrink: 0; }
.st-conf {
  font-size: 10px; color: rgba(251, 191, 36, 0.7);
  background: rgba(251, 191, 36, 0.08);
  padding: 1px 6px; border-radius: 100px;
  font-family: 'JetBrains Mono', monospace;
}
.st-prob {
  font-size: 10px; color: #42b883;
  background: rgba(66, 184, 131, 0.14);
  padding: 1px 6px; border-radius: 100px;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
}
.st-step-exp { font-size: 12px; color: var(--text-dim); line-height: 1.5; }
.st-step-meta {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px;
  margin-top: 6px; font-size: 10px;
}
.st-meta-key { color: rgba(255,255,255,0.3); }
.st-meta-chip {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.08);
  color: var(--text-dim);
  padding: 1px 6px;
  border-radius: 4px;
}
.st-meta-rule {
  background: rgba(255, 51, 153, 0.12);
  border: 1px solid rgba(255, 51, 153, 0.25);
  color: #ff3399;
  padding: 1px 6px;
  border-radius: 4px;
  margin-left: 4px;
}
</style>
