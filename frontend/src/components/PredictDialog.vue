<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { toast } from '../composables/useToast';
import { useHypothesisTemplates } from '../composables/useHypothesisTemplates';
import { useConstraintConflicts } from '../composables/useConstraintConflicts';

const props = defineProps<{
  open: boolean;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  initialSeedIds: string[];
  modelId?: string;
}>();

// P1-10：约束三态扩展。probability 模式下额外携带 probability 字段（0..1）。
type ConstraintMode = 'force' | 'block' | 'probability';
type Constraint = { nodeId: string; mode: ConstraintMode; probability?: number };

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'submit', payload: {
    seeds: string[]; steps: number; prompt: string; name: string;
    intent: 'forward' | 'backward'; constraints: Constraint[];
  }): void;
}>();

const intent = ref<'forward' | 'backward'>('forward');
const steps = ref(4);
const prompt = ref('');
const name = ref('');
const seedIds = ref<string[]>([]);
const search = ref('');
const constraints = ref<Constraint[]>([]);
const cSearch = ref('');
const showConstraints = ref(false);
const showTemplatePanel = ref(false);
const templateName = ref('');

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
    intent.value = 'forward';
    constraints.value = [];
    cSearch.value = '';
    showConstraints.value = false;
    showTemplatePanel.value = false;
    templateName.value = '';
    loadTemplates();
  }
};

// 模板增删改查抽到 useHypothesisTemplates；表单读写通过回调注入以保持响应式。
const tpl = useHypothesisTemplates({
  getModelId: () => props.modelId,
  getForm: () => ({ seeds: seedIds.value, steps: steps.value, intent: intent.value,
                    constraints: constraints.value, prompt: prompt.value }),
  applyForm: (t, existingIds) => {
    seedIds.value = t.seeds.filter(id => existingIds.has(id));
    steps.value = t.steps;
    intent.value = (t.intent as 'forward' | 'backward') || 'forward';
    constraints.value = (t.constraints || []).filter(c => existingIds.has(c.nodeId));
    prompt.value = t.prompt || '';
    showTemplatePanel.value = false;
  },
  getExistingNodeIds: () => new Set(props.nodes.map(n => n.id)),
});
// 顶层 const 别名：ref 在模板里才会自动解包（嵌套 tpl.templates 不会），与 App.vue 既有约定一致。
const templates = tpl.templates;
const loadTemplates = tpl.loadTemplates;
const loadFromTemplate = tpl.loadFromTemplate;
const removeTemplate = tpl.removeTemplate;
// 原 saveAsTemplate 无参且成功后清空 templateName；保持模板调用不变，清空副作用仅在成功时执行。
const saveAsTemplate = async () => {
  const ok = await tpl.saveAsTemplate(templateName.value);
  if (ok) templateName.value = '';
};

const constraintNodeIds = computed(() => new Set(constraints.value.map(c => c.nodeId)));

// 约束冲突检测抽到 useConstraintConflicts；标签解析复用组件内 nodeMap。
const { constraintConflicts } = useConstraintConflicts(
  () => constraints.value,
  () => props.edges,
  (id) => nodeMap.value[id]?.label || id,
);
const constraintCandidates = computed(() => {
  const q = cSearch.value.trim().toLowerCase();
  if (!q) return [];
  return props.nodes
    .filter(n => !constraintNodeIds.value.has(n.id))
    .filter(n => (n.label || '').toLowerCase().includes(q) || (n.type || '').toLowerCase().includes(q) || (n.id || '').toLowerCase().includes(q))
    .slice(0, 20);
});
const addConstraint = (id: string, mode: ConstraintMode = 'block') => {
  if (constraintNodeIds.value.has(id)) return;
  // P1-10：probability 模式默认 0.5，由用户调整
  const c: Constraint = { nodeId: id, mode };
  if (mode === 'probability') c.probability = 0.5;
  constraints.value.push(c);
  cSearch.value = '';
};
const removeConstraint = (id: string) => {
  constraints.value = constraints.value.filter(c => c.nodeId !== id);
};
// P1-10：循环切换 force → block → probability → force
const toggleConstraint = (id: string) => {
  const c = constraints.value.find(x => x.nodeId === id);
  if (!c) return;
  if (c.mode === 'force') {
    c.mode = 'block';
    delete c.probability;
  } else if (c.mode === 'block') {
    c.mode = 'probability';
    c.probability = 0.5;
  } else {
    c.mode = 'force';
    delete c.probability;
  }
};
// P1-10：约束节点是否为现有图谱节点（预测节点不能加概率约束）
const isExistingGraphNode = (id: string) => {
  if (!id) return false;
  // 推演节点 id 形如 'p_*' / 'pe_*'；现有节点通常是 'n_*' 或导入时的其他 id
  return !id.startsWith('p_') && !id.startsWith('pe_');
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
  emit('submit', {
    seeds: seedIds.value,
    steps: steps.value,
    prompt: prompt.value.trim(),
    name: name.value.trim(),
    intent: intent.value,
    constraints: constraints.value.slice()
  });
};

const seedLabelHint = computed(() => intent.value === 'backward' ? '目标节点 (结果)' : '起点节点 (seeds)');
const stepLabelHint = computed(() => intent.value === 'backward' ? '溯因层数' : '推演步数');
const promptHint = computed(() => intent.value === 'backward'
  ? '例：客户突然大量流失，希望排查可能的根因…'
  : '例：假设供应商A遭遇罢工，影响范围扩大到原料供应…');
const submitLabel = computed(() => intent.value === 'backward' ? '开始溯因' : '开始推演');

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('predict-backdrop')) emit('close');
};

watch(() => props.open, sync, { immediate: true });

// Dialog 打开期间若 initialSeedIds 变化（父端再次右键 / 改 seeds），同步过去。
watch(() => props.initialSeedIds, (v) => {
  if (!props.open) return;
  seedIds.value = [...(v || [])];
});

// 切换 intent 时清空 constraints（forward/backward 语义不同），通过 toast 告知。
watch(intent, (newVal, oldVal) => {
  if (!props.open) return;
  if (newVal !== oldVal && constraints.value.length > 0) {
    constraints.value = [];
    toast.info('已切换推演方向，约束已清除');
  }
});
</script>

<template>
  <Dialog :open="open" @update:open="(v: boolean) => { if (!v) emit('close'); }">
    <DialogContent class="flex max-h-[90vh] flex-col gap-0 overflow-hidden border-amber-400/25 p-0 sm:max-w-[520px]">
      <DialogHeader class="border-b border-border px-5 py-4">
        <DialogTitle class="flex items-center gap-2">
          <span class="pd-icon">⚡</span>
          <span>场景推演</span>
        </DialogTitle>
      </DialogHeader>

      <div class="pd-body">
        <div class="pd-section pd-template-section">
          <button class="pd-collapse" type="button" @click="showTemplatePanel = !showTemplatePanel">
            <span class="pd-collapse-arrow" :class="{ 'pd-collapse-open': showTemplatePanel }">▶</span>
            <span class="pd-collapse-label">推演模板</span>
            <span v-if="templates.length" class="pd-collapse-badge">{{ templates.length }}</span>
            <span class="pd-collapse-hint">保存/加载常用配置</span>
          </button>
          <div v-if="showTemplatePanel" class="pd-template-body">
            <div v-if="templates.length" class="pd-template-list">
              <div v-for="t in templates" :key="t.id" class="pd-template-row">
                <div class="pd-template-info" @click="loadFromTemplate(t)">
                  <span class="pd-template-name">{{ t.name }}</span>
                  <span class="pd-template-meta">
                    {{ t.intent === 'backward' ? '溯因' : '前向' }} · {{ t.steps }}步 · {{ t.seeds.length }}起点
                    <span v-if="t.constraints?.length"> · {{ t.constraints.length }}约束</span>
                  </span>
                </div>
                <button class="pd-template-del" @click.stop="removeTemplate(t)" type="button" title="删除模板">×</button>
              </div>
            </div>
            <div v-else class="pd-empty pd-empty-inline">暂无保存的模板</div>
            <div class="pd-template-save">
              <input class="pd-search" v-model="templateName" placeholder="输入模板名称…" @keydown.enter="saveAsTemplate" />
              <Button size="sm" type="button" @click="saveAsTemplate" :disabled="!templateName.trim()">保存当前配置</Button>
            </div>
          </div>
        </div>

        <div class="pd-section">
          <label class="pd-label">推演方向</label>
          <div class="pd-tabs">
            <button
              class="pd-tab"
              :class="{ 'pd-tab-on': intent === 'forward' }"
              @click="intent = 'forward'"
              type="button"
            >
              <span class="pd-tab-arrow">→</span>
              <span class="pd-tab-text">
                <span class="pd-tab-title">前向推演</span>
                <span class="pd-tab-sub">从起点向下游预测</span>
              </span>
            </button>
            <button
              class="pd-tab"
              :class="{ 'pd-tab-on': intent === 'backward' }"
              @click="intent = 'backward'"
              type="button"
            >
              <span class="pd-tab-arrow">←</span>
              <span class="pd-tab-text">
                <span class="pd-tab-title">溯因推演</span>
                <span class="pd-tab-sub">从结果反推可能原因</span>
              </span>
            </button>
          </div>
        </div>

        <div class="pd-section">
          <label class="pd-label">{{ seedLabelHint }}</label>
          <div class="pd-seeds">
            <span v-for="id in seedIds" :key="id" class="pd-seed-chip">
              {{ nodeMap[id]?.label || id }}
              <button type="button" @click="removeSeed(id)">×</button>
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
          <label class="pd-label">{{ stepLabelHint }} <span class="pd-step-val">{{ steps }}</span></label>
          <input type="range" min="1" max="8" step="1" v-model.number="steps" class="pd-slider" />
          <div class="pd-slider-marks">
            <span>1</span><span>2</span><span>3</span><span>4</span><span>5</span><span>6</span><span>7</span><span>8</span>
          </div>
        </div>

        <div class="pd-section">
          <label class="pd-label">场景描述（可选）</label>
          <textarea class="pd-prompt" v-model="prompt" :placeholder="promptHint" rows="2" />
        </div>

        <div class="pd-section">
          <button class="pd-collapse" type="button" @click="showConstraints = !showConstraints">
            <span class="pd-collapse-arrow" :class="{ 'pd-collapse-open': showConstraints }">▶</span>
            <span class="pd-collapse-label">What-if 约束</span>
            <span v-if="constraints.length" class="pd-collapse-badge">{{ constraints.length }}</span>
            <span class="pd-collapse-hint">假设某节点必然 / 不会发生</span>
          </button>
          <div v-if="showConstraints" class="pd-constraint-body">
            <div v-if="constraintConflicts.length" class="pd-conflict-warnings">
              <div class="pd-conflict-title">⚠ 约束冲突检测</div>
              <div v-for="(w, i) in constraintConflicts" :key="i" class="pd-conflict-item">
                {{ w.message }}
              </div>
            </div>
            <div v-if="constraints.length" class="pd-constraint-list">
              <div v-for="c in constraints" :key="c.nodeId" class="pd-constraint-row">
                <button
                  class="pd-cmode"
                  :class="{
                    'pd-cmode-force': c.mode === 'force',
                    'pd-cmode-block': c.mode === 'block',
                    'pd-cmode-prob': c.mode === 'probability',
                  }"
                  @click="toggleConstraint(c.nodeId)"
                  type="button"
                  :title="c.mode === 'force' ? '必然发生（点击切换为禁止）'
                         : c.mode === 'block' ? '不会发生（点击切换为概率）'
                         : '先验概率（点击切换为必然）'"
                >{{ c.mode === 'force' ? '必然' : c.mode === 'block' ? '禁止' : '概率' }}</button>
                <span class="pd-constraint-label">{{ nodeMap[c.nodeId]?.label || c.nodeId }}</span>
                <!-- P1-10：probability 模式追加滑块 + 数值 -->
                <template v-if="c.mode === 'probability'">
                  <input
                    type="range" min="0" max="1" step="0.05"
                    class="pd-prob-slider"
                    :value="c.probability ?? 0.5"
                    @input="(e) => { c.probability = Number((e.target as HTMLInputElement).value); }"
                  />
                  <span class="pd-prob-val">{{ ((c.probability ?? 0.5) as number).toFixed(2) }}</span>
                </template>
                <button class="pd-constraint-x" @click="removeConstraint(c.nodeId)" type="button">×</button>
              </div>
            </div>
            <input class="pd-search" v-model="cSearch" placeholder="搜索节点添加约束…" />
            <div v-if="cSearch.trim()" class="pd-candidates">
              <div v-for="n in constraintCandidates" :key="n.id" class="pd-cand">
                <span class="pd-cand-label">{{ n.label }}</span>
                <div class="pd-cand-actions">
                  <button class="pd-cand-add pd-cand-block" @click="addConstraint(n.id, 'block')" type="button">禁止</button>
                  <button class="pd-cand-add pd-cand-force" @click="addConstraint(n.id, 'force')" type="button">必然</button>
                  <button
                    class="pd-cand-add pd-cand-prob"
                    :disabled="!isExistingGraphNode(n.id)"
                    :title="isExistingGraphNode(n.id) ? '设置先验概率' : '概率约束仅适用于现有图谱节点'"
                    @click="addConstraint(n.id, 'probability')"
                    type="button"
                  >概率</button>
                </div>
              </div>
              <div v-if="!constraintCandidates.length" class="pd-empty pd-empty-inline">无匹配</div>
            </div>
          </div>
        </div>

        <div class="pd-section">
          <label class="pd-label">分支命名（留空自动生成）</label>
          <input class="pd-input" v-model="name" placeholder="如：供应商A断货推演" />
        </div>
      </div>

      <DialogFooter class="border-t border-border px-5 py-3">
        <Button variant="secondary" size="sm" @click="emit('close')">取消</Button>
        <Button
          size="sm"
          class="bg-amber-400 text-black hover:bg-amber-300"
          :disabled="!seedIds.length"
          @click="submit"
        >
          <span>⚡</span> {{ submitLabel }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
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

.pd-tabs { display: flex; gap: 8px; }
.pd-tab {
  flex: 1;
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  color: var(--text-dim);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
  text-align: left;
}
.pd-tab:hover { background: rgba(10, 16, 27, 0.9); border-color: rgba(255,255,255,0.15); }
.pd-tab-on {
  background: rgba(251, 191, 36, 0.12);
  border-color: rgba(251, 191, 36, 0.5);
  color: #fbbf24;
}
.pd-tab-arrow {
  font-size: 18px;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  flex-shrink: 0;
}
.pd-tab-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.pd-tab-title { font-size: 13px; font-weight: 600; }
.pd-tab-sub { font-size: 10px; opacity: 0.7; }

.pd-collapse {
  width: 100%;
  display: flex; align-items: center; gap: 8px;
  background: rgba(10, 16, 27, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  transition: all 0.15s;
}
.pd-collapse:hover { background: rgba(10, 16, 27, 0.85); border-color: rgba(255,255,255,0.15); color: var(--text-main); }
.pd-collapse-arrow {
  font-size: 9px;
  transition: transform 0.15s;
  font-family: 'JetBrains Mono', monospace;
  display: inline-block;
}
.pd-collapse-open { transform: rotate(90deg); }
.pd-collapse-label { font-weight: 600; color: var(--text-main); }
.pd-collapse-badge {
  background: rgba(251, 191, 36, 0.2);
  color: #fbbf24;
  padding: 1px 7px;
  border-radius: 100px;
  font-size: 10px;
  font-family: 'JetBrains Mono', monospace;
}
.pd-collapse-hint { margin-left: auto; font-size: 10px; opacity: 0.6; }

.pd-constraint-body { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.pd-constraint-list { display: flex; flex-direction: column; gap: 4px; }
.pd-constraint-row {
  display: flex; align-items: center; gap: 8px;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.06);
  padding: 6px 8px;
  border-radius: 8px;
}
.pd-cmode {
  font-size: 10px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 6px;
  border: 1px solid;
  cursor: pointer;
  font-family: inherit;
  flex-shrink: 0;
  width: 56px;
  text-align: center;
}
.pd-cmode-block { background: rgba(255, 80, 80, 0.12); color: #ff8a8a; border-color: rgba(255, 80, 80, 0.35); }
.pd-cmode-force { background: rgba(99, 179, 237, 0.12); color: #63b3ed; border-color: rgba(99, 179, 237, 0.35); }
/* P1-10：概率模式紫色调，与 data 节点视觉同源 */
.pd-cmode-prob { background: rgba(187, 119, 255, 0.12); color: #d4a8ff; border-color: rgba(187, 119, 255, 0.35); }
.pd-constraint-label { flex: 1; font-size: 12px; color: var(--text-main); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* P1-10：概率滑块 + 数值显示 */
.pd-prob-slider { width: 80px; accent-color: #bb77ff; flex-shrink: 0; }
.pd-prob-val {
  font-size: 11px; font-family: 'JetBrains Mono', monospace;
  color: #d4a8ff; min-width: 32px; text-align: right; flex-shrink: 0;
}
.pd-constraint-x {
  background: none; border: none; color: var(--text-dim); cursor: pointer;
  padding: 2px 6px; font-size: 16px; line-height: 1; border-radius: 4px;
}
.pd-constraint-x:hover { color: #ff8a8a; background: rgba(255,80,80,0.1); }

.pd-cand-actions { display: flex; gap: 4px; }
.pd-cand-add {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 5px;
  border: 1px solid;
  cursor: pointer;
  font-family: inherit;
}
.pd-cand-block { background: rgba(255, 80, 80, 0.1); color: #ff8a8a; border-color: rgba(255, 80, 80, 0.3); }
.pd-cand-block:hover { background: rgba(255, 80, 80, 0.2); }
.pd-cand-force { background: rgba(99, 179, 237, 0.1); color: #63b3ed; border-color: rgba(99, 179, 237, 0.3); }
.pd-cand-force:hover { background: rgba(99, 179, 237, 0.2); }
/* P1-10：候选项的"概率"按钮 */
.pd-cand-prob { background: rgba(187, 119, 255, 0.1); color: #d4a8ff; border-color: rgba(187, 119, 255, 0.3); }
.pd-cand-prob:hover:not(:disabled) { background: rgba(187, 119, 255, 0.2); }
.pd-cand-prob:disabled { opacity: 0.35; cursor: not-allowed; }
.pd-cand { justify-content: space-between; }
.pd-conflict-warnings {
  background: rgba(255, 80, 80, 0.08);
  border: 1px solid rgba(255, 80, 80, 0.25);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.pd-conflict-title {
  font-size: 11px;
  font-weight: 700;
  color: #ff8a8a;
  margin-bottom: 6px;
}
.pd-conflict-item {
  font-size: 11px;
  color: rgba(255, 138, 138, 0.85);
  line-height: 1.5;
  padding: 2px 0;
}
.pd-template-section {
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  padding-bottom: 12px;
}
.pd-template-body {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pd-template-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 150px;
  overflow-y: auto;
}
.pd-template-row {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  padding: 8px 10px;
  cursor: pointer;
  transition: all 0.15s;
}
.pd-template-row:hover {
  background: rgba(47, 134, 214, 0.08);
  border-color: rgba(47, 134, 214, 0.3);
}
.pd-template-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.pd-template-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pd-template-meta {
  font-size: 10px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.pd-template-del {
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  padding: 2px 6px;
  font-size: 16px;
  line-height: 1;
  border-radius: 4px;
  flex-shrink: 0;
}
.pd-template-del:hover {
  color: #ff8a8a;
  background: rgba(255, 80, 80, 0.1);
}
.pd-template-save {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pd-template-save .pd-search {
  flex: 1;
}
.pd-btn-save {
  padding: 8px 14px;
  border-radius: 8px;
  border: 1px solid rgba(47, 134, 214, 0.3);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  background: rgba(47, 134, 214, 0.15);
  color: var(--accent);
  white-space: nowrap;
  flex-shrink: 0;
}
.pd-btn-save:hover:not(:disabled) {
  background: rgba(47, 134, 214, 0.25);
}
.pd-btn-save:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
