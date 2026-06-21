<script setup lang="ts">
import { ref, computed, onBeforeUnmount, watch } from 'vue';
import { extractOntologyFromExperiences } from '../api/experiences';
import type { SseHandle } from '../api/http';
import type { OntologyNode, OntologyEdge } from '../types';

const props = defineProps<{
  open: boolean;
  /** 当前工作空间名称，仅用于展示 */
  workspaceName?: string;
  hasCurrentModel: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'commit', payload: {
    mode: 'merge' | 'new';
    name: string;
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }): void;
}>();

const phase = ref<'idle' | 'running' | 'done' | 'error'>('idle');
const steps = ref<{ key: string; label: string; status: 'running' | 'done' | 'error' }[]>([]);
const errMsg = ref('');
const hint = ref('');
const mode = ref<'merge' | 'new'>('merge');
const newName = ref('');
const result = ref<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  reply: string;
  salt: string;
  sourceCount?: number;
} | null>(null);

let sseHandle: SseHandle | null = null;

const reset = () => {
  phase.value = 'idle';
  steps.value = [];
  errMsg.value = '';
  result.value = null;
  hint.value = '';
  mode.value = props.hasCurrentModel ? 'merge' : 'new';
  newName.value = `${props.workspaceName || '经验库'} 本体血缘图`;
};

watch(() => props.open, (v) => {
  if (v) reset();
  else abort();
});

const abort = () => {
  if (sseHandle) { try { sseHandle.abort(); } catch { /* noop */ } sseHandle = null; }
};

onBeforeUnmount(abort);

const markRunningAs = (status: 'done' | 'error') => {
  for (const s of steps.value) if (s.status === 'running') s.status = status;
};

const start = () => {
  phase.value = 'running';
  steps.value = [{ key: 'init', label: '正在准备…', status: 'running' }];
  errMsg.value = '';
  result.value = null;
  sseHandle = extractOntologyFromExperiences({ hint: hint.value.trim() || undefined }, {
    onStep: (key, label) => {
      markRunningAs('done');
      steps.value.push({ key, label, status: 'running' });
    },
    onComplete: (data) => {
      markRunningAs('done');
      result.value = {
        nodes: (data.nodes as OntologyNode[]) || [],
        edges: (data.edges as OntologyEdge[]) || [],
        reply: data.reply || '',
        salt: data.salt,
        sourceCount: data.sourceCount,
      };
      phase.value = 'done';
      sseHandle = null;
    },
    onError: (msg) => {
      markRunningAs('error');
      errMsg.value = msg || '构建失败';
      phase.value = 'error';
      sseHandle = null;
    },
    // 流意外关闭(无 complete/error 事件,如后端重启/网络断开)时不能让进度永远转圈
    onClose: () => {
      sseHandle = null;
      if (phase.value === 'running') {
        markRunningAs('error');
        errMsg.value = '连接中断,未收到完整结果,请重试';
        phase.value = 'error';
      }
    },
  });
};

const canCommit = computed(() => {
  if (!result.value) return false;
  if (result.value.nodes.length === 0) return false;
  if (mode.value === 'merge' && !props.hasCurrentModel) return false;
  if (mode.value === 'new' && !newName.value.trim()) return false;
  return true;
});

const commit = () => {
  if (!canCommit.value || !result.value) return;
  emit('commit', {
    mode: mode.value,
    name: newName.value.trim() || `${props.workspaceName || '经验库'} 本体血缘图`,
    nodes: result.value.nodes,
    edges: result.value.edges,
  });
};

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('dbo-backdrop')) emit('close');
};

const formattedReply = computed(() => {
  if (!result.value?.reply) return '';
  const escaped = result.value.reply
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
  return escaped.replace(/\n/g, '<br/>');
});

const typeStats = computed(() => {
  if (!result.value) return [] as { type: string; count: number }[];
  const m = new Map<string, number>();
  for (const n of result.value.nodes) {
    const t = (n.type as string) || 'entity';
    m.set(t, (m.get(t) || 0) + 1);
  }
  return [...m.entries()].map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count);
});

const relStats = computed(() => {
  if (!result.value) return [] as { rel: string; count: number }[];
  const m = new Map<string, number>();
  for (const e of result.value.edges) {
    const r = (e.rel_type as string) || 'associated_with';
    m.set(r, (m.get(r) || 0) + 1);
  }
  return [...m.entries()].map(([rel, count]) => ({ rel, count }))
    .sort((a, b) => b.count - a.count);
});
</script>

<template>
  <div v-if="open" class="dbo-backdrop" @mousedown="onBackdrop">
    <div class="dbo-dialog">
      <div class="dbo-head">
        <span class="dbo-icon">🧬</span>
        <span class="dbo-title">从经验库构建本体血缘图</span>
        <span class="dbo-source">「{{ workspaceName || '当前工作空间' }}」</span>
        <button class="dbo-close" @click="emit('close')">×</button>
      </div>

      <div class="dbo-body">
        <!-- 启动阶段 -->
        <div v-if="phase === 'idle'" class="dbo-section">
          <div class="dbo-desc">
            将聚合当前工作空间 <strong>经验库里的全部经验文件</strong>，由大模型抽取实体、流程、事件、规则及其关系，
            构建本体血缘图。<br/>
            • 经验文件可手动撰写、上传文档，或由数据源导出 DDL「供血」沉淀而来<br/>
            • 数据源不再直接出图：先入经验库参与建图，再为图节点绑定真实数据
          </div>
          <label class="dbo-row">
            <span>额外提示（可选）</span>
            <input v-model="hint" placeholder="例如：重点关注审批链路；忽略历史复盘类经验" />
          </label>
          <div class="dbo-actions">
            <button class="dbo-btn primary" @click="start">开始构建</button>
            <button class="dbo-btn ghost" @click="emit('close')">取消</button>
          </div>
        </div>

        <!-- 进度阶段 -->
        <div v-if="phase === 'running' || (phase === 'done' && steps.length)" class="dbo-section">
          <div class="dbo-steps">
            <div v-for="s in steps" :key="s.key" class="dbo-step" :class="s.status">
              <span class="dbo-step-dot">{{
                s.status === 'done' ? '✓' : s.status === 'error' ? '✗' : '●'
              }}</span>
              <span class="dbo-step-label">{{ s.label }}</span>
            </div>
          </div>
        </div>

        <!-- 失败 -->
        <div v-if="phase === 'error'" class="dbo-section">
          <div class="dbo-error">{{ errMsg }}</div>
          <div class="dbo-actions">
            <button class="dbo-btn primary" @click="start">重试</button>
            <button class="dbo-btn ghost" @click="emit('close')">关闭</button>
          </div>
        </div>

        <!-- 结果 -->
        <div v-if="phase === 'done' && result" class="dbo-section">
          <div class="dbo-summary">
            <div class="dbo-summary-row">
              聚合 <strong>{{ result.sourceCount }}</strong> 篇经验
              → 抽出 <strong>{{ result.nodes.length }}</strong> 个节点 /
              <strong>{{ result.edges.length }}</strong> 条关系
            </div>
            <div v-if="result.reply" class="dbo-reply" v-html="formattedReply"></div>
          </div>

          <div class="dbo-stats">
            <div class="dbo-stat-block">
              <div class="dbo-stat-title">节点类型分布</div>
              <div class="dbo-chip-row">
                <span v-for="t in typeStats" :key="t.type" class="dbo-chip">
                  {{ t.type }} · {{ t.count }}
                </span>
              </div>
            </div>
            <div class="dbo-stat-block">
              <div class="dbo-stat-title">关系类型分布</div>
              <div class="dbo-chip-row">
                <span v-for="r in relStats" :key="r.rel" class="dbo-chip rel">
                  {{ r.rel }} · {{ r.count }}
                </span>
              </div>
            </div>
          </div>

          <div class="dbo-mode-pick">
            <label>
              <input type="radio" v-model="mode" value="merge" :disabled="!hasCurrentModel" />
              合并到当前模型
              <span v-if="!hasCurrentModel" class="dbo-muted">（无当前模型）</span>
            </label>
            <label>
              <input type="radio" v-model="mode" value="new" />
              另存为新模型
            </label>
          </div>
          <label v-if="mode === 'new'" class="dbo-row">
            <span>模型名称</span>
            <input v-model="newName" />
          </label>

          <div class="dbo-actions">
            <button class="dbo-btn primary" :disabled="!canCommit" @click="commit">
              确认并{{ mode === 'merge' ? '合并到当前' : '另存为新' }}模型
            </button>
            <button class="dbo-btn ghost" @click="emit('close')">取消</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dbo-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
}
.dbo-dialog {
  width: min(720px, 92vw); max-height: 86vh; overflow: hidden;
  background: #1d1f24; border: 1px solid rgba(255,255,255,.08);
  border-radius: 10px; display: flex; flex-direction: column;
  color: #e8eaed; box-shadow: 0 12px 32px rgba(0,0,0,.4);
}
.dbo-head { display: flex; align-items: center; gap: 8px; padding: 12px 16px;
  border-bottom: 1px solid rgba(255,255,255,.06); }
.dbo-icon { font-size: 18px; }
.dbo-title { font-size: 15px; font-weight: 600; }
.dbo-source { font-size: 12px; color: #aaa; }
.dbo-close { margin-left: auto; background: none; border: none; color: #aaa;
  font-size: 22px; cursor: pointer; line-height: 1; }
.dbo-close:hover { color: #fff; }
.dbo-body { padding: 16px; overflow-y: auto; }
.dbo-section { margin-bottom: 16px; }
.dbo-section:last-child { margin-bottom: 0; }
.dbo-desc { color: #c0c4cf; font-size: 13px; line-height: 1.7; margin-bottom: 12px; }
.dbo-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.dbo-row > span { min-width: 100px; font-size: 13px; color: #aaa; }
.dbo-row > input { flex: 1; background: rgba(255,255,255,.04);
  border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px;
  color: #e8eaed; font-size: 13px; }
.dbo-actions { display: flex; gap: 8px; margin-top: 14px; }
.dbo-btn { padding: 7px 14px; border-radius: 6px; border: none; cursor: pointer;
  font-size: 13px; }
.dbo-btn.primary { background: var(--accent, #2f86d6); color: #fff; font-weight: 600; }
.dbo-btn.primary:disabled { opacity: .45; cursor: not-allowed; }
.dbo-btn.ghost { background: transparent; color: #aaa;
  border: 1px solid rgba(255,255,255,.12); }
.dbo-btn.ghost:hover { color: #fff; }

.dbo-steps { display: flex; flex-direction: column; gap: 4px;
  background: rgba(255,255,255,.03); border-radius: 6px; padding: 10px 12px;
  max-height: 220px; overflow-y: auto; }
.dbo-step { display: flex; align-items: center; gap: 8px; font-size: 12.5px;
  color: #c0c4cf; }
.dbo-step-dot { width: 14px; text-align: center; font-weight: bold; }
.dbo-step.done .dbo-step-dot { color: #22dd88; }
.dbo-step.running .dbo-step-dot { color: var(--accent, #2f86d6); animation: blink 1s infinite; }
.dbo-step.error .dbo-step-dot { color: tomato; }
@keyframes blink { 50% { opacity: .35; } }

.dbo-error { color: tomato; background: rgba(255,99,71,.12);
  padding: 10px 14px; border-radius: 6px; font-size: 13px; }

.dbo-summary { background: rgba(47,134,214,.08); padding: 12px 14px;
  border-radius: 6px; margin-bottom: 12px; }
.dbo-summary-row { font-size: 13.5px; color: #e8eaed; margin-bottom: 6px; }
.dbo-summary-row strong { color: #5aa6ee; font-weight: 600; }
.dbo-reply { font-size: 12px; color: #aaa; line-height: 1.6; }

.dbo-stats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px;
  margin-bottom: 14px; }
.dbo-stat-block { background: rgba(255,255,255,.03); border-radius: 6px;
  padding: 10px 12px; }
.dbo-stat-title { font-size: 12px; color: #888; margin-bottom: 6px; }
.dbo-chip-row { display: flex; flex-wrap: wrap; gap: 4px; }
.dbo-chip { font-size: 11.5px; color: #c0c4cf; background: rgba(255,255,255,.06);
  padding: 2px 8px; border-radius: 10px; }
.dbo-chip.rel { background: rgba(47,134,214,.15); color: #cfe4fb; }

.dbo-mode-pick { display: flex; gap: 18px; margin-bottom: 10px; font-size: 13px;
  color: #c0c4cf; }
.dbo-mode-pick label { display: flex; align-items: center; gap: 6px;
  cursor: pointer; }
.dbo-muted { color: #666; font-size: 11.5px; }
</style>
