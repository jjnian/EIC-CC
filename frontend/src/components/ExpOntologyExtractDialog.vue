<script setup lang="ts">
import { ref, computed, onBeforeUnmount, watch } from 'vue';
import { extractOntologyFromExperiences, type BuildManifestEntry } from '../api/experiences';
import type { SseHandle } from '../api/http';
import type { OntologyNode, OntologyEdge } from '../types';
import { analyzeLineageHealth } from '../utils/lineageHealth';
import { detectConflicts } from '../utils/lineageConflicts';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import BaseInput from './form/BaseInput.vue';

const props = defineProps<{
  open: boolean;
  /** 当前工作空间名称，仅用于展示 */
  workspaceName?: string;
  hasCurrentModel: boolean;
  /** 当前打开的本体模型 id（增量建图的目标；无则不显示增量选项） */
  currentModelId?: string;
  /** 本空间可参与建图的经验列表（供选择建图范围）；缺省时不展示范围选择 */
  experiences?: { id: string; title: string; origin?: string }[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'commit', payload: {
    mode: 'merge' | 'new';
    name: string;
    nodes: OntologyNode[];
    edges: OntologyEdge[];
    /** 建图来源清单：合并入模型后回写构建记录，供下次增量建图跳过未变更经验 */
    manifest?: BuildManifestEntry[];
  }): void;
}>();

const phase = ref<'idle' | 'running' | 'done' | 'error'>('idle');
// 建图范围：'all' = 全部经验；'pick' = 勾选部分经验
const scope = ref<'all' | 'pick'>('all');
// 增量建图：跳过上次已建图且内容未变化的经验（需要有当前模型作为增量目标）
const incremental = ref(false);
const pickedIds = ref<Set<string>>(new Set());
const scopeList = computed(() => props.experiences || []);
const togglePicked = (id: string) => {
  const next = new Set(pickedIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  pickedIds.value = next;
};
const originBadge = (o?: string) =>
  o === 'ddl' ? 'DDL' : o === 'explore' ? '探索' : o === 'upload' ? '文件' : o === 'websystem' ? 'Web'
  : o === 'datasource' ? '数据源' : o === 'websearch' ? '调研' : '';
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
  incremental?: boolean;
  skippedUnchanged?: number;
  manifest?: BuildManifestEntry[];
} | null>(null);

let sseHandle: SseHandle | null = null;

const reset = () => {
  phase.value = 'idle';
  steps.value = [];
  errMsg.value = '';
  result.value = null;
  hint.value = '';
  scope.value = 'all';
  incremental.value = !!props.currentModelId && props.hasCurrentModel;
  pickedIds.value = new Set(scopeList.value.map(e => e.id));
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

const canStart = computed(() =>
  scope.value === 'all' || pickedIds.value.size > 0);

const start = () => {
  if (!canStart.value) return;
  phase.value = 'running';
  steps.value = [{ key: 'init', label: '正在准备…', status: 'running' }];
  errMsg.value = '';
  result.value = null;
  // 全选（或未提供列表）时不传范围，让后端聚合全部；勾选部分时只建所选
  const experienceIds = scope.value === 'pick' && pickedIds.value.size < scopeList.value.length
    ? [...pickedIds.value] : undefined;
  sseHandle = extractOntologyFromExperiences({
    hint: hint.value.trim() || undefined,
    experienceIds,
    incrementalModelId: incremental.value && props.currentModelId ? props.currentModelId : undefined,
  }, {
    onStep: (key, label) => {
      // 分批建图进度(llm_batch)会多次上报，原地更新同一行，避免刷出几十行
      const last = steps.value[steps.value.length - 1];
      if (key === 'llm_batch' && last && last.key === 'llm_batch') {
        last.label = label;
        return;
      }
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
        incremental: data.incremental,
        skippedUnchanged: data.skippedUnchanged,
        manifest: data.manifest,
      };
      // 增量结果只包含新增/变更部分，只能合并进目标模型，不能另存为新模型
      if (data.incremental) mode.value = 'merge';
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
    manifest: result.value.manifest,
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

// 合并前结构体检：在抽取结果上先跑一遍血缘环/方向矛盾/孤立节点检查，把抽取错误挡在合并进模型之前。
const HEALTH_SCHEMA_ONLY = new Set(['attribute', 'constraint']);
const healthCheck = computed(() => {
  if (!result.value) return null;
  const biz = result.value.nodes.filter(n => !HEALTH_SCHEMA_ONLY.has(n.type));
  const h = analyzeLineageHealth(biz, result.value.edges);
  const c = detectConflicts(result.value.nodes, result.value.edges);
  return {
    isolated: h.isolatedIds.length,
    components: h.componentCount,
    cycles: c.cycles.length,
    directionConflicts: c.directionConflicts.length,
    duplicateLabels: c.duplicateLabels.length,
  };
});
// 需人工核对的强信号（环/方向矛盾/重复/孤立）；碎片化只作提示，不算“问题”。
const hasQualityIssue = computed(() => {
  const h = healthCheck.value;
  return !!h && (h.cycles > 0 || h.directionConflicts > 0 || h.duplicateLabels > 0 || h.isolated > 0);
});
</script>

<template>
  <Dialog :open="open" @update:open="(v: boolean) => { if (!v) emit('close'); }">
    <DialogContent class="flex max-h-[86vh] flex-col gap-0 overflow-hidden p-0 sm:max-w-[720px]">
      <DialogHeader class="border-b border-border px-5 py-4">
        <DialogTitle class="flex items-center gap-2">
          <span class="dbo-icon">🧬</span>
          <span>从经验库构建本体血缘图</span>
          <span class="dbo-source">「{{ workspaceName || '当前工作空间' }}」</span>
        </DialogTitle>
      </DialogHeader>

      <div class="dbo-body">
        <!-- 启动阶段 -->
        <div v-if="phase === 'idle'" class="dbo-section">
          <div class="dbo-desc">
            将聚合当前工作空间 <strong>经验库里的全部经验文件</strong>，由大模型抽取实体、流程、事件、规则及其关系，
            构建本体血缘图。<br/>
            • 经验文件可手动撰写、上传文档，或由数据源导出 DDL「供血」沉淀而来<br/>
            • 数据源不再直接出图：先入经验库参与建图，再为图节点绑定真实数据
          </div>
          <div v-if="scopeList.length" class="dbo-scope">
            <div class="dbo-scope-head">
              <span>建图范围</span>
              <label><input type="radio" v-model="scope" value="all" /> 全部经验（{{ scopeList.length }} 篇）</label>
              <label><input type="radio" v-model="scope" value="pick" /> 选择部分</label>
            </div>
            <div v-if="scope === 'pick'" class="dbo-scope-list">
              <label v-for="e in scopeList" :key="e.id" class="dbo-scope-item">
                <input type="checkbox" :checked="pickedIds.has(e.id)" @change="togglePicked(e.id)" />
                <span class="dbo-scope-title">{{ e.title }}</span>
                <span v-if="originBadge(e.origin)" class="dbo-scope-badge">{{ originBadge(e.origin) }}</span>
              </label>
              <div v-if="!pickedIds.size" class="dbo-muted">请至少勾选一篇经验</div>
            </div>
          </div>
          <label v-if="currentModelId && hasCurrentModel" class="dbo-incr">
            <input type="checkbox" v-model="incremental" />
            <span>增量建图：跳过上次已建图且内容未变化的经验，只抽新增/变更部分（海量经验时推荐）</span>
          </label>
          <label class="dbo-row">
            <span>额外提示（可选）</span>
            <BaseInput v-model="hint" placeholder="例如：重点关注审批链路；忽略历史复盘类经验" />
          </label>
          <div class="dbo-actions">
            <Button size="sm" :disabled="!canStart" @click="start">开始构建</Button>
            <Button variant="secondary" size="sm" @click="emit('close')">取消</Button>
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
            <Button size="sm" @click="start">重试</Button>
            <Button variant="secondary" size="sm" @click="emit('close')">关闭</Button>
          </div>
        </div>

        <!-- 结果 -->
        <div v-if="phase === 'done' && result" class="dbo-section">
          <div class="dbo-summary">
            <div class="dbo-summary-row">
              聚合 <strong>{{ result.sourceCount }}</strong> 篇经验
              → 抽出 <strong>{{ result.nodes.length }}</strong> 个节点 /
              <strong>{{ result.edges.length }}</strong> 条关系
              <span v-if="result.skippedUnchanged" class="dbo-incr-tag">增量：跳过 {{ result.skippedUnchanged }} 篇未变化</span>
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

          <div v-if="healthCheck" class="dbo-health" :class="{ warn: hasQualityIssue }">
            <div class="dbo-health-title">{{ hasQualityIssue ? '⚠ 合并前建议核对' : '✓ 结构体检通过' }}</div>
            <div v-if="hasQualityIssue" class="dbo-health-items">
              <span v-if="healthCheck.cycles" class="dbo-h-item bad">血缘环 {{ healthCheck.cycles }}</span>
              <span v-if="healthCheck.directionConflicts" class="dbo-h-item bad">方向矛盾 {{ healthCheck.directionConflicts }}</span>
              <span v-if="healthCheck.duplicateLabels" class="dbo-h-item warn">疑似重复 {{ healthCheck.duplicateLabels }}</span>
              <span v-if="healthCheck.isolated" class="dbo-h-item warn">孤立节点 {{ healthCheck.isolated }}</span>
              <span v-if="healthCheck.components > 1" class="dbo-h-item">血缘 {{ healthCheck.components }} 块</span>
            </div>
            <div v-if="hasQualityIssue" class="dbo-health-note">
              环 / 方向矛盾多为抽取把血缘方向标反；合并后可在「体检」页逐项定位修正。仍可继续合并。
            </div>
          </div>

          <div class="dbo-mode-pick">
            <label>
              <input type="radio" v-model="mode" value="merge" :disabled="!hasCurrentModel" />
              合并到当前模型
              <span v-if="!hasCurrentModel" class="dbo-muted">（无当前模型）</span>
            </label>
            <label>
              <input type="radio" v-model="mode" value="new" :disabled="result?.incremental" />
              另存为新模型
              <span v-if="result?.incremental" class="dbo-muted">（增量结果只能合并）</span>
            </label>
          </div>
          <label v-if="mode === 'new'" class="dbo-row">
            <span>模型名称</span>
            <BaseInput v-model="newName" />
          </label>

          <div class="dbo-actions">
            <Button size="sm" :disabled="!canCommit" @click="commit">
              确认并{{ mode === 'merge' ? '合并到当前' : '另存为新' }}模型
            </Button>
            <Button variant="secondary" size="sm" @click="emit('close')">取消</Button>
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>
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

.dbo-health { border-radius: 6px; padding: 10px 12px; margin-bottom: 12px;
  background: rgba(34,221,136,.08); border: 1px solid rgba(34,221,136,.25); }
.dbo-health.warn { background: rgba(255,180,60,.08); border-color: rgba(255,180,60,.3); }
.dbo-health-title { font-size: 12.5px; color: #e8eaed; margin-bottom: 6px; }
.dbo-health-items { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.dbo-h-item { font-size: 11.5px; padding: 2px 8px; border-radius: 10px;
  background: rgba(255,255,255,.06); color: #cbd0d6; }
.dbo-h-item.warn { color: #ffcc66; background: rgba(255,180,60,.14); }
.dbo-h-item.bad { color: #ff8866; background: rgba(255,90,60,.16); }
.dbo-health-note { font-size: 11.5px; color: #999; line-height: 1.5; }

.dbo-stats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px;
  margin-bottom: 14px; }
.dbo-stat-block { background: rgba(255,255,255,.03); border-radius: 6px;
  padding: 10px 12px; }
.dbo-stat-title { font-size: 12px; color: #888; margin-bottom: 6px; }
.dbo-chip-row { display: flex; flex-wrap: wrap; gap: 4px; }
.dbo-chip { font-size: 11.5px; color: #c0c4cf; background: rgba(255,255,255,.06);
  padding: 2px 8px; border-radius: 10px; }
.dbo-chip.rel { background: rgba(47,134,214,.15); color: #cfe4fb; }

.dbo-scope { background: rgba(255,255,255,.03); border-radius: 6px; padding: 10px 12px;
  margin-bottom: 12px; }
.dbo-scope-head { display: flex; align-items: center; gap: 16px; font-size: 13px;
  color: #c0c4cf; }
.dbo-scope-head > span { color: #888; font-size: 12px; }
.dbo-scope-head label { display: flex; align-items: center; gap: 5px; cursor: pointer; }
.dbo-scope-list { margin-top: 8px; max-height: 180px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 4px; }
.dbo-scope-item { display: flex; align-items: center; gap: 7px; font-size: 12.5px;
  color: #c0c4cf; cursor: pointer; padding: 2px 0; }
.dbo-scope-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dbo-scope-badge { flex-shrink: 0; font-size: 10.5px; color: #8fb8e8;
  background: rgba(47,134,214,.15); padding: 1px 6px; border-radius: 8px; }

.dbo-incr { display: flex; align-items: flex-start; gap: 7px; font-size: 12.5px;
  color: #c0c4cf; margin-bottom: 10px; cursor: pointer; line-height: 1.5; }
.dbo-incr input { margin-top: 2px; }
.dbo-incr-tag { margin-left: 8px; font-size: 11.5px; color: #8fb8e8;
  background: rgba(47,134,214,.15); padding: 1px 8px; border-radius: 10px; }

.dbo-mode-pick { display: flex; gap: 18px; margin-bottom: 10px; font-size: 13px;
  color: #c0c4cf; }
.dbo-mode-pick label { display: flex; align-items: center; gap: 6px;
  cursor: pointer; }
.dbo-muted { color: #666; font-size: 11.5px; }
</style>
