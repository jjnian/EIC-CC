<script setup lang="ts">
import { ref, computed } from 'vue';

const props = defineProps<{
  open: boolean;
  nodes: any[];
  initialSeedIds: string[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'submit', payload: { seeds: string[]; steps: number; prompt: string; name: string }): void;
}>();

const steps = ref(4);
const prompt = ref('');
const name = ref('');
const seedIds = ref<string[]>([]);
const search = ref('');

const nodeMap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));

const filteredCandidates = computed(() => {
  const q = search.value.trim().toLowerCase();
  return props.nodes
    .filter(n => !seedIds.value.includes(n.id))
    .filter(n => !q || (n.label || '').toLowerCase().includes(q) || (n.type || '').toLowerCase().includes(q))
    .slice(0, 30);
});

const sync = () => {
  if (props.open) {
    seedIds.value = [...props.initialSeedIds];
    steps.value = 4;
    prompt.value = '';
    name.value = '';
    search.value = '';
  }
};

const addSeed = (id: string) => {
  if (!seedIds.value.includes(id)) seedIds.value.push(id);
  search.value = '';
};
const removeSeed = (id: string) => {
  seedIds.value = seedIds.value.filter(s => s !== id);
};

const submit = () => {
  if (!seedIds.value.length) return;
  emit('submit', { seeds: seedIds.value, steps: steps.value, prompt: prompt.value.trim(), name: name.value.trim() });
};

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('predict-backdrop')) emit('close');
};

import { watch } from 'vue';
watch(() => props.open, sync, { immediate: true });
</script>

<template>
  <div v-if="open" class="predict-backdrop" @mousedown="onBackdrop">
    <div class="predict-dialog">
      <div class="pd-head">
        <div class="pd-title">
          <span class="pd-icon">⚡</span>
          <span>场景推演</span>
        </div>
        <button class="pd-close" @click="emit('close')">×</button>
      </div>

      <div class="pd-body">
        <div class="pd-section">
          <label class="pd-label">起点节点 (seeds)</label>
          <div class="pd-seeds">
            <span v-for="id in seedIds" :key="id" class="pd-seed-chip">
              {{ nodeMap[id]?.label || id }}
              <button @click="removeSeed(id)">×</button>
            </span>
            <span v-if="!seedIds.length" class="pd-empty">至少需要 1 个起点</span>
          </div>
          <input class="pd-search" v-model="search" placeholder="搜索节点添加更多起点…" />
          <div v-if="search.trim()" class="pd-candidates">
            <div v-for="n in filteredCandidates" :key="n.id" class="pd-cand" @click="addSeed(n.id)">
              <span class="pd-cand-label">{{ n.label }}</span>
              <span class="pd-cand-type">{{ n.type }}</span>
            </div>
            <div v-if="!filteredCandidates.length" class="pd-empty pd-empty-inline">无匹配</div>
          </div>
        </div>

        <div class="pd-section">
          <label class="pd-label">推演步数 <span class="pd-step-val">{{ steps }}</span></label>
          <input type="range" min="1" max="8" step="1" v-model.number="steps" class="pd-slider" />
          <div class="pd-slider-marks">
            <span>1</span><span>2</span><span>3</span><span>4</span><span>5</span><span>6</span><span>7</span><span>8</span>
          </div>
        </div>

        <div class="pd-section">
          <label class="pd-label">场景描述（可选）</label>
          <textarea class="pd-prompt" v-model="prompt" placeholder="例：假设供应商A遭遇罢工，影响范围扩大到原料供应…" rows="2" />
        </div>

        <div class="pd-section">
          <label class="pd-label">分支命名（留空自动生成）</label>
          <input class="pd-input" v-model="name" placeholder="如：供应商A断货推演" />
        </div>
      </div>

      <div class="pd-foot">
        <button class="pd-btn pd-btn-cancel" @click="emit('close')">取消</button>
        <button class="pd-btn pd-btn-go" :disabled="!seedIds.length" @click="submit">
          <span>⚡</span> 开始推演
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.predict-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  animation: pdFade 0.18s ease-out;
}
@keyframes pdFade { from { opacity: 0; } to { opacity: 1; } }
.predict-dialog {
  width: 520px;
  max-width: 92vw;
  max-height: 90vh;
  background: rgba(15, 23, 42, 0.96);
  border: 1px solid rgba(251, 191, 36, 0.25);
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
  overflow: hidden;
}
.pd-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.pd-title { display: flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 600; color: var(--text-main); }
.pd-icon { color: #fbbf24; font-size: 18px; }
.pd-close {
  background: rgba(255,255,255,0.06); border: none; color: var(--text-dim);
  width: 28px; height: 28px; border-radius: 50%; cursor: pointer; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
}
.pd-close:hover { background: rgba(255, 99, 99, 0.2); color: #ff8a8a; }
.pd-body { padding: 16px 20px; overflow-y: auto; flex: 1; }
.pd-section { margin-bottom: 16px; }
.pd-label { display: block; font-size: 12px; color: var(--text-dim); margin-bottom: 8px; letter-spacing: 0.5px; }
.pd-step-val { color: #fbbf24; font-weight: 700; margin-left: 6px; font-family: 'JetBrains Mono', monospace; }
.pd-seeds { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; min-height: 28px; }
.pd-seed-chip {
  display: flex; align-items: center; gap: 6px;
  background: rgba(251, 191, 36, 0.15);
  border: 1px solid rgba(251, 191, 36, 0.35);
  color: #fbbf24; padding: 4px 10px; border-radius: 100px; font-size: 12px;
}
.pd-seed-chip button {
  background: none; border: none; color: inherit; cursor: pointer; padding: 0 2px;
  font-size: 14px; line-height: 1;
}
.pd-empty { color: rgba(255,255,255,0.3); font-size: 12px; }
.pd-empty-inline { padding: 8px 12px; }
.pd-search, .pd-input {
  width: 100%;
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-main);
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.pd-search:focus, .pd-input:focus { border-color: rgba(251, 191, 36, 0.5); }
.pd-candidates {
  margin-top: 6px;
  max-height: 180px;
  overflow-y: auto;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
}
.pd-cand {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 12px; cursor: pointer; font-size: 13px;
  transition: background 0.12s;
}
.pd-cand:hover { background: rgba(251, 191, 36, 0.1); color: #fbbf24; }
.pd-cand-label { color: var(--text-main); }
.pd-cand-type { font-size: 10px; color: var(--text-dim); text-transform: uppercase; }
.pd-slider { width: 100%; accent-color: #fbbf24; }
.pd-slider-marks {
  display: flex; justify-content: space-between;
  font-size: 10px; color: rgba(255,255,255,0.3);
  font-family: 'JetBrains Mono', monospace;
  margin-top: 2px;
}
.pd-prompt {
  width: 100%;
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-main);
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  resize: vertical;
  outline: none;
}
.pd-prompt:focus { border-color: rgba(251, 191, 36, 0.5); }
.pd-foot {
  display: flex; gap: 10px; justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.pd-btn {
  padding: 9px 18px;
  border-radius: 10px;
  border: none;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  display: flex; align-items: center; gap: 6px;
}
.pd-btn-cancel { background: rgba(255,255,255,0.06); color: var(--text-dim); }
.pd-btn-cancel:hover { background: rgba(255,255,255,0.12); color: var(--text-main); }
.pd-btn-go { background: #fbbf24; color: #1a1a1a; }
.pd-btn-go:hover:not(:disabled) { background: #fde68a; transform: translateY(-1px); }
.pd-btn-go:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
