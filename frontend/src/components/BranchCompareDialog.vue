<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { Scenario, ChainStep } from '../types';

const props = defineProps<{
  open: boolean;
  branches: Scenario[];
  initialA?: string;
  initialB?: string;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const aId = ref<string>('');
const bId = ref<string>('');

watch(() => props.open, (v) => {
  if (v) {
    aId.value = props.initialA || (props.branches[0]?.id ?? '');
    bId.value = props.initialB || (props.branches[1]?.id ?? props.branches[0]?.id ?? '');
  }
});

// 当分支列表变化（外部删除）时：选中的 id 若已失效则 fallback；若列表空则自动 close
watch(() => props.branches.length, (n) => {
  if (!props.open) return;
  if (!n) { emit('close'); return; }
  const ids = new Set(props.branches.map(b => b.id));
  if (!ids.has(aId.value)) aId.value = props.branches[0]?.id ?? '';
  if (!ids.has(bId.value)) bId.value = props.branches[1]?.id ?? props.branches[0]?.id ?? '';
});

const branchA = computed(() => props.branches.find(b => b.id === aId.value));
const branchB = computed(() => props.branches.find(b => b.id === bId.value));

const stepsOf = (b: Scenario | undefined): ChainStep[] => b?.dag?.chain || b?.chain || [];
const norm = (s: string) => (s || '').trim().toLowerCase();

const diff = computed(() => {
  // Short-circuit: 同分支对比无意义。
  if (aId.value && aId.value === bId.value) {
    const steps = stepsOf(branchA.value);
    return { shared: [], uniqueA: [], uniqueB: [], aSteps: steps, bSteps: steps };
  }
  const aSteps = stepsOf(branchA.value);
  const bSteps = stepsOf(branchB.value);
  const aLabels = new Set(aSteps.map(s => norm(s.label)));
  const bLabels = new Set(bSteps.map(s => norm(s.label)));
  const matched = new Map<string, { a: ChainStep; b: ChainStep }>();
  for (const sa of aSteps) {
    const sb = bSteps.find(x => norm(x.label) === norm(sa.label));
    if (sb) matched.set(norm(sa.label), { a: sa, b: sb });
  }
  const uniqueA = aSteps.filter(s => !bLabels.has(norm(s.label)));
  const uniqueB = bSteps.filter(s => !aLabels.has(norm(s.label)));
  const shared = Array.from(matched.values());
  return { shared, uniqueA, uniqueB, aSteps, bSteps };
});

const fmt = (n?: number) =>
  (n == null || !Number.isFinite(n)) ? '—' : `${Math.round(n * 100)}%`;
const intentLabel = (b: Scenario | undefined) =>
  (b?.intent || b?.dag?.intent) === 'backward' ? '溯因' : '前向';
const constraintsCount = (b: Scenario | undefined) => b?.dag?.constraints?.length || 0;

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('bc-backdrop')) emit('close');
};
</script>

<template>
  <div v-if="open" class="bc-backdrop" @mousedown="onBackdrop">
    <div class="bc-dialog">
      <div class="bc-head">
        <div class="bc-title"><span class="bc-icon">⚖</span><span>分支对比</span></div>
        <button class="bc-close" type="button" @click="emit('close')">×</button>
      </div>

      <div class="bc-selectors">
        <div class="bc-sel-col">
          <label class="bc-sel-label">分支 A</label>
          <select v-model="aId" class="bc-sel">
            <option v-for="b in branches" :key="b.id" :value="b.id">{{ b.name }}</option>
          </select>
          <div v-if="branchA" class="bc-meta">
            <span>{{ intentLabel(branchA) }} · {{ diff.aSteps.length }} 步</span>
            <span v-if="constraintsCount(branchA)" class="bc-meta-tag">含 {{ constraintsCount(branchA) }} 约束</span>
          </div>
        </div>
        <div class="bc-vs">vs</div>
        <div class="bc-sel-col">
          <label class="bc-sel-label">分支 B</label>
          <select v-model="bId" class="bc-sel">
            <option v-for="b in branches" :key="b.id" :value="b.id">{{ b.name }}</option>
          </select>
          <div v-if="branchB" class="bc-meta">
            <span>{{ intentLabel(branchB) }} · {{ diff.bSteps.length }} 步</span>
            <span v-if="constraintsCount(branchB)" class="bc-meta-tag">含 {{ constraintsCount(branchB) }} 约束</span>
          </div>
        </div>
      </div>

      <div class="bc-body">
        <div v-if="aId === bId" class="bc-empty">请选择两个不同的分支</div>
        <template v-else>
          <div class="bc-section">
            <div class="bc-sec-head">
              <span class="bc-sec-title bc-shared-title">共同结论</span>
              <span class="bc-sec-count">{{ diff.shared.length }}</span>
            </div>
            <div v-if="!diff.shared.length" class="bc-sec-empty">无共同节点</div>
            <div v-for="pair in diff.shared" :key="'s:' + pair.a.nodeId + ':' + pair.b.nodeId" class="bc-row bc-row-shared">
              <span class="bc-row-label">{{ pair.a.label }}</span>
              <div class="bc-row-probs">
                <span class="bc-prob-a" :title="'A 中有效概率'">A·{{ fmt(pair.a.effectiveProbability ?? pair.a.confidence) }}</span>
                <span class="bc-prob-b" :title="'B 中有效概率'">B·{{ fmt(pair.b.effectiveProbability ?? pair.b.confidence) }}</span>
              </div>
            </div>
          </div>

          <div class="bc-columns">
            <div class="bc-section bc-col">
              <div class="bc-sec-head">
                <span class="bc-sec-title bc-a-title">仅 A 出现</span>
                <span class="bc-sec-count">{{ diff.uniqueA.length }}</span>
              </div>
              <div v-if="!diff.uniqueA.length" class="bc-sec-empty">无</div>
              <div v-for="s in diff.uniqueA" :key="'a:' + s.step + ':' + s.nodeId" class="bc-row bc-row-a">
                <span class="bc-row-step">#{{ s.step }}</span>
                <span class="bc-row-label">{{ s.label }}</span>
                <span class="bc-row-prob">{{ fmt(s.effectiveProbability ?? s.confidence) }}</span>
              </div>
            </div>
            <div class="bc-section bc-col">
              <div class="bc-sec-head">
                <span class="bc-sec-title bc-b-title">仅 B 出现</span>
                <span class="bc-sec-count">{{ diff.uniqueB.length }}</span>
              </div>
              <div v-if="!diff.uniqueB.length" class="bc-sec-empty">无</div>
              <div v-for="s in diff.uniqueB" :key="'b:' + s.step + ':' + s.nodeId" class="bc-row bc-row-b">
                <span class="bc-row-step">#{{ s.step }}</span>
                <span class="bc-row-label">{{ s.label }}</span>
                <span class="bc-row-prob">{{ fmt(s.effectiveProbability ?? s.confidence) }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bc-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.5);
  backdrop-filter: blur(4px); z-index: 2000;
  display: flex; align-items: center; justify-content: center;
  animation: bcFade 0.18s ease-out;
}
@keyframes bcFade { from { opacity: 0; } to { opacity: 1; } }
.bc-dialog {
  width: 760px; max-width: 94vw; max-height: 90vh;
  background: rgba(15, 23, 42, 0.96);
  border: 1px solid rgba(99, 179, 237, 0.25);
  border-radius: 16px;
  display: flex; flex-direction: column;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
  overflow: hidden;
}
.bc-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.bc-title { display: flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 600; color: var(--text-main); }
.bc-icon { color: #63b3ed; font-size: 18px; }
.bc-close {
  background: rgba(255,255,255,0.06); border: none; color: var(--text-dim);
  width: 28px; height: 28px; border-radius: 50%; cursor: pointer; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
}
.bc-close:hover { background: rgba(255,99,99,0.2); color: #ff8a8a; }
.bc-selectors {
  display: flex; align-items: stretch; gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.bc-sel-col { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.bc-sel-label { font-size: 11px; color: var(--text-dim); letter-spacing: 0.5px; }
.bc-sel {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main);
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.bc-sel:focus { border-color: rgba(99, 179, 237, 0.5); }
.bc-meta { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }
.bc-meta-tag { background: rgba(251, 191, 36, 0.12); color: #fbbf24; padding: 1px 6px; border-radius: 100px; }
.bc-vs {
  align-self: center;
  font-size: 14px; color: rgba(255,255,255,0.3);
  font-family: 'JetBrains Mono', monospace;
  padding-top: 18px;
}
.bc-body { padding: 14px 20px; overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 16px; }
.bc-empty { text-align: center; padding: 40px 20px; color: var(--text-dim); font-size: 13px; }
.bc-section { display: flex; flex-direction: column; gap: 6px; }
.bc-sec-head { display: flex; align-items: center; gap: 8px; }
.bc-sec-title { font-size: 12px; font-weight: 600; letter-spacing: 0.5px; }
.bc-shared-title { color: #42b883; }
.bc-a-title { color: #fbbf24; }
.bc-b-title { color: #63b3ed; }
.bc-sec-count {
  font-size: 10px;
  font-family: 'JetBrains Mono', monospace;
  background: rgba(255,255,255,0.08);
  color: var(--text-dim);
  padding: 1px 7px;
  border-radius: 100px;
}
.bc-sec-empty { font-size: 12px; color: rgba(255,255,255,0.3); padding: 6px 4px; font-style: italic; }
.bc-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px;
  background: rgba(10, 16, 27, 0.5);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px;
  font-size: 12px;
}
.bc-row-shared { border-left: 2px solid #42b883; }
.bc-row-a { border-left: 2px solid #fbbf24; }
.bc-row-b { border-left: 2px solid #63b3ed; }
.bc-row-label { flex: 1; color: var(--text-main); }
.bc-row-step {
  font-size: 10px; color: rgba(255,255,255,0.3);
  font-family: 'JetBrains Mono', monospace;
}
.bc-row-probs { display: flex; gap: 4px; font-family: 'JetBrains Mono', monospace; }
.bc-row-prob {
  font-size: 10px; color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
  background: rgba(255,255,255,0.06);
  padding: 1px 6px; border-radius: 100px;
}
.bc-prob-a {
  font-size: 10px; color: #fbbf24;
  background: rgba(251, 191, 36, 0.12);
  padding: 1px 6px; border-radius: 100px;
}
.bc-prob-b {
  font-size: 10px; color: #63b3ed;
  background: rgba(99, 179, 237, 0.12);
  padding: 1px 6px; border-radius: 100px;
}
.bc-columns { display: flex; gap: 12px; }
.bc-col { flex: 1; min-width: 0; }
</style>
