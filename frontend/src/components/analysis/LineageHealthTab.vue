<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { OntologyNode, OntologyEdge } from '../../types';
import { listNodeBindings } from '../../api/nodeBindings';
import { verifyContainment, type ContainmentCheckResult } from '../../api/dataSources';
import { detectSchemaDrift, type SchemaDriftResult } from '../../api/ontology';
import { parseContainmentTarget, verdictConfidence, verdictEvidence, hasVerification, type ContainmentTarget } from '../../utils/lineageVerify';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { toast } from '../../composables/useToast';
import { Button } from '@/components/ui/button';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  modelId?: string;
}>();

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
  (e: 'update-edge-schema', id: string, patch: any): void;
}>();

const SCHEMA_ONLY = new Set(['attribute', 'constraint']);
const nmap = computed(() => Object.fromEntries(props.nodes.map(n => [n.id, n])));
const bizNodes = computed(() => props.nodes.filter(n => !SCHEMA_ONLY.has(n.type)));

// ── 血缘健康度指标 ──────────────────────────────────────
const noSourceNodes = computed(() => bizNodes.value.filter(n => !n.derived_source));
const derivedNoEvidence = computed(() =>
  bizNodes.value.filter(n => n.source === 'derived' && !(n.evidence || '').trim()));
const noRelTypeEdges = computed(() => props.edges.filter(e => !e.rel_type));
const inferredEdges = computed(() => props.edges.filter(e => e.source === 'inferred'));
const unverifiedInferred = computed(() => inferredEdges.value.filter(e => !hasVerification(e)));

const pct = (part: number, total: number) => total === 0 ? 100 : Math.round((1 - part / total) * 100);

// ── 供血绑定覆盖率 ──────────────────────────────────────
const boundNodeIds = ref<Set<string>>(new Set());
const bindingsLoaded = ref(false);
const loadBindings = async () => {
  if (!props.modelId) return;
  try {
    const list = await listNodeBindings(props.modelId);
    boundNodeIds.value = new Set(list.map(b => b.nodeId));
    bindingsLoaded.value = true;
  } catch { /* 绑定接口失败不阻塞体检 */ }
};
watch(() => props.modelId, loadBindings, { immediate: true });
const bindingCoverage = computed(() => {
  const total = bizNodes.value.length;
  if (!total) return 0;
  return Math.round(bizNodes.value.filter(n => boundNodeIds.value.has(n.id)).length / total * 100);
});

// ── 推断边批量数据验证 ──────────────────────────────────
const DB_KINDS = new Set(['mysql', 'pgsql', 'oracle', 'dm', 'gbase']);
const ws = useWorkspaces();
const tree = useSidebarTree();
const dbSources = computed(() => {
  const wsId = ws.currentId.value;
  return wsId ? (tree.getDataSources(wsId) || []).filter(d => DB_KINDS.has(d.kind)) : [];
});
watch(() => ws.currentId.value, (wsId) => { if (wsId) tree.loadDataSources(wsId); }, { immediate: true });

interface VerifyRow {
  edge: OntologyEdge;
  target: ContainmentTarget;
  dsId: string;
  status: 'pending' | 'running' | 'done' | 'error';
  result?: ContainmentCheckResult;
  error?: string;
  applied?: boolean;
}

/** 为一条边解析验证目标并落到数据源：优先按边上的 derived_source 名匹配，否则唯一库兜底。 */
const resolveRow = (e: OntologyEdge): VerifyRow | null => {
  const target = parseContainmentTarget(e);
  if (!target) return null;
  const byName = dbSources.value.find(d => d.name === e.derived_source);
  const ds = byName || (dbSources.value.length === 1 ? dbSources.value[0] : null);
  if (!ds) return null;
  return { edge: e, target, dsId: ds.id, status: 'pending' };
};

const rows = ref<VerifyRow[]>([]);
const running = ref(false);
const prepared = ref(false);

const prepare = () => {
  rows.value = unverifiedInferred.value
    .map(resolveRow)
    .filter((r): r is VerifyRow => r !== null);
  prepared.value = true;
};
const unresolvable = computed(() =>
  prepared.value ? unverifiedInferred.value.length - rows.value.length : 0);

const runAll = async () => {
  if (running.value || !rows.value.length) return;
  running.value = true;
  // 串行跑：每条一次只读 SQL，避免并发打爆用户业务库
  for (const row of rows.value) {
    if (row.status === 'done') continue;
    row.status = 'running';
    try {
      row.result = await verifyContainment(row.dsId, { ...row.target });
      row.status = 'done';
    } catch (err: any) {
      row.error = err?.message || '验证失败';
      row.status = 'error';
    }
  }
  running.value = false;
};

const doneRows = computed(() => rows.value.filter(r => r.status === 'done' && r.result));
const applyRow = (row: VerifyRow) => {
  if (!row.result || row.result.verdict === 'empty' || row.applied) return;
  emit('update-edge-schema', row.edge.id, {
    confidence: verdictConfidence(row.result.verdict),
    evidence: verdictEvidence(row.result),
  });
  row.applied = true;
};
const applyAll = () => {
  let n = 0;
  for (const row of doneRows.value) {
    if (!row.applied && row.result!.verdict !== 'empty') { applyRow(row); n++; }
  }
  if (n) toast.success(`已写回 ${n} 条边的验证结果（rejected 的建议删除该边）`);
};

const verdictView = (v: ContainmentCheckResult['verdict']) => ({
  confirmed: { text: '✓ 证实', color: '#22dd88' },
  likely:    { text: '≈ 大概率', color: '#ffcc44' },
  rejected:  { text: '✗ 不支持', color: '#ff7755' },
  empty:     { text: '— 无数据', color: '#999' },
}[v]);

const edgeLabel = (e: OntologyEdge) =>
  `${nmap.value[e.from]?.label || e.from} → ${nmap.value[e.to]?.label || e.to}`;

// ── Schema 漂移检测 ─────────────────────────────────────
const driftDsId = ref('');
const driftRunning = ref(false);
const driftResult = ref<SchemaDriftResult | null>(null);
const driftError = ref('');
const labelToNodeId = computed(() => {
  const m = new Map<string, string>();
  for (const n of props.nodes) if (n.label && !m.has(n.label)) m.set(n.label, n.id);
  return m;
});
const focusByLabel = (label: string) => {
  const id = labelToNodeId.value.get(label);
  if (id) emit('focus-node', id);
};
const runDrift = async () => {
  if (!props.modelId || !driftDsId.value || driftRunning.value) return;
  driftRunning.value = true;
  driftResult.value = null;
  driftError.value = '';
  try {
    driftResult.value = await detectSchemaDrift(props.modelId, driftDsId.value);
  } catch (err: any) {
    driftError.value = err?.message || '检测失败';
  } finally {
    driftRunning.value = false;
  }
};
</script>

<template>
  <div class="lh-wrap">
    <!-- 健康度总览 -->
    <div class="gap-section">
      <div class="gap-sec-title">血缘健康度</div>
      <div class="lh-grid">
        <div class="lh-stat">
          <div class="lh-n" :class="{ warn: noSourceNodes.length }">{{ pct(noSourceNodes.length, bizNodes.length) }}%</div>
          <div class="lh-l">来源标注率<span class="lh-sub">{{ noSourceNodes.length }} 个节点无 derived_source</span></div>
        </div>
        <div class="lh-stat">
          <div class="lh-n" :class="{ warn: noRelTypeEdges.length }">{{ pct(noRelTypeEdges.length, edges.length) }}%</div>
          <div class="lh-l">方向语义率<span class="lh-sub">{{ noRelTypeEdges.length }} 条边无 rel_type（血缘方向只能按默认猜）</span></div>
        </div>
        <div class="lh-stat">
          <div class="lh-n" :class="{ warn: derivedNoEvidence.length }">{{ pct(derivedNoEvidence.length, bizNodes.length) }}%</div>
          <div class="lh-l">证据覆盖率<span class="lh-sub">{{ derivedNoEvidence.length }} 个 derived 节点无 evidence</span></div>
        </div>
        <div class="lh-stat">
          <div class="lh-n" :class="{ warn: unverifiedInferred.length }">{{ inferredEdges.length - unverifiedInferred.length }}/{{ inferredEdges.length }}</div>
          <div class="lh-l">推断边已验证<span class="lh-sub">{{ unverifiedInferred.length }} 条推断边未做数据验证</span></div>
        </div>
        <div v-if="bindingsLoaded" class="lh-stat">
          <div class="lh-n">{{ bindingCoverage }}%</div>
          <div class="lh-l">供血绑定覆盖率<span class="lh-sub">节点绑定到真实数据源的比例</span></div>
        </div>
      </div>
    </div>

    <!-- 待补齐清单 -->
    <div v-if="noSourceNodes.length" class="gap-section">
      <div class="gap-sec-title">无来源节点（点击定位，可在节点详情手动补）</div>
      <div class="lh-chip-list">
        <button v-for="n in noSourceNodes.slice(0, 30)" :key="n.id" class="lh-chip" @click="emit('focus-node', n.id)">{{ n.label }}</button>
        <span v-if="noSourceNodes.length > 30" class="lh-more">…共 {{ noSourceNodes.length }} 个</span>
      </div>
    </div>
    <div v-if="noRelTypeEdges.length" class="gap-section">
      <div class="gap-sec-title">无方向语义的边（点击定位起点，可在边详情补 rel_type）</div>
      <div class="lh-chip-list">
        <button v-for="e in noRelTypeEdges.slice(0, 20)" :key="e.id" class="lh-chip" @click="emit('focus-node', e.from)">{{ edgeLabel(e) }}</button>
        <span v-if="noRelTypeEdges.length > 20" class="lh-more">…共 {{ noRelTypeEdges.length }} 条</span>
      </div>
    </div>

    <!-- 批量数据验证 -->
    <div class="gap-section">
      <div class="gap-sec-title">推断边批量数据验证</div>
      <div class="lh-note">
        对未验证的推断血缘边（{{ unverifiedInferred.length }} 条）批量做值包含检验：子表列的值应都能在父表列中找到。
        只读、逐条串行、自动采样。
      </div>
      <div class="lh-actions">
        <Button size="sm" variant="outline" :disabled="!unverifiedInferred.length || running" @click="prepare">1️⃣ 解析验证目标</Button>
        <Button size="sm" :disabled="!rows.length || running" @click="runAll">{{ running ? '验证中…' : '2️⃣ 开始批量验证' }}</Button>
        <Button v-if="doneRows.length" size="sm" variant="outline" @click="applyAll">3️⃣ 全部采信写回</Button>
      </div>
      <div v-if="prepared" class="lh-note">
        可验证 {{ rows.length }} 条{{ unresolvable > 0 ? `；${unresolvable} 条无法解析出 表.列 或找不到对应数据库数据源（可在边详情手动验证）` : '' }}
      </div>
      <div v-if="rows.length" class="lh-rows">
        <div v-for="row in rows" :key="row.edge.id" class="lh-row">
          <span class="lh-row-status">{{
            row.status === 'running' ? '⏳' : row.status === 'done' ? '' : row.status === 'error' ? '⚠️' : '·'
          }}</span>
          <button class="lh-row-label" :title="`${row.target.childTable}.${row.target.childColumn} ⊆ ${row.target.parentTable}.${row.target.parentColumn}`"
                  @click="emit('focus-node', row.edge.from)">{{ edgeLabel(row.edge) }}</button>
          <template v-if="row.result">
            <span class="lh-verdict" :style="{ color: verdictView(row.result.verdict).color }">{{ verdictView(row.result.verdict).text }}</span>
            <span class="lh-rate">{{ (row.result.matchRate * 100).toFixed(1) }}%</span>
            <Button v-if="row.result.verdict !== 'empty'" size="sm" variant="ghost" :disabled="row.applied" @click="applyRow(row)">
              {{ row.applied ? '已写回' : '采信' }}
            </Button>
          </template>
          <span v-else-if="row.error" class="lh-err" :title="row.error">{{ row.error }}</span>
        </div>
      </div>
    </div>

    <!-- Schema 漂移检测 -->
    <div class="gap-section">
      <div class="gap-sec-title">Schema 漂移检测</div>
      <div class="lh-note">
        重新内省数据库最新结构，比对图上引用的来源表/列：删表、删列、改名会让血缘失效。
        图上混有多个数据源的表时，属于其它数据源的"缺失表"可忽略。
      </div>
      <div class="lh-actions">
        <select v-model="driftDsId" class="lh-select">
          <option value="" disabled>选择数据库数据源</option>
          <option v-for="d in dbSources" :key="d.id" :value="d.id">{{ d.name }}（{{ d.kind }}）</option>
        </select>
        <Button size="sm" :disabled="!modelId || !driftDsId || driftRunning" @click="runDrift">
          {{ driftRunning ? '检测中…' : '开始检测' }}
        </Button>
      </div>
      <div v-if="driftError" class="lh-err-block">{{ driftError }}</div>
      <template v-if="driftResult">
        <div class="lh-note">
          库「{{ driftResult.database }}」现有 {{ driftResult.schemaTables }} 张表；
          图上引用 {{ driftResult.referencedTables }} 张，其中 {{ driftResult.okTables }} 张仍存在。
          <b v-if="!driftResult.missingTables.length && !driftResult.missingColumns.length" style="color:#22dd88">✓ 未发现漂移</b>
        </div>
        <div v-if="driftResult.missingTables.length" class="lh-drift-group">
          <div class="lh-drift-title" style="color:#ff7755">缺失的表（{{ driftResult.missingTables.length }}）</div>
          <div v-for="mt in driftResult.missingTables" :key="mt.table" class="lh-drift-item">
            <span class="lh-drift-key">{{ mt.table }}</span>
            <span class="lh-drift-refs">
              <button v-for="nl in mt.nodes.slice(0, 8)" :key="'n'+nl" class="lh-chip" @click="focusByLabel(nl)">{{ nl }}</button>
              <span v-if="mt.edges.length" class="lh-more">+{{ mt.edges.length }} 条边引用</span>
            </span>
          </div>
        </div>
        <div v-if="driftResult.missingColumns.length" class="lh-drift-group">
          <div class="lh-drift-title" style="color:#ffcc44">缺失的列（{{ driftResult.missingColumns.length }}）</div>
          <div v-for="mc in driftResult.missingColumns" :key="mc.table + '.' + mc.column" class="lh-drift-item">
            <span class="lh-drift-key">{{ mc.table }}.{{ mc.column }}</span>
            <span class="lh-drift-refs">
              <button v-for="nl in mc.nodes.slice(0, 8)" :key="'c'+nl" class="lh-chip" @click="focusByLabel(nl)">{{ nl }}</button>
            </span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lh-wrap { display: flex; flex-direction: column; gap: 14px; }
.gap-section { display: flex; flex-direction: column; gap: 8px; }
.gap-sec-title { font-size: 12px; color: #8a93a5; font-weight: 600; }
.lh-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.lh-stat { background: rgba(255,255,255,.03); border-radius: 8px; padding: 10px 12px; }
.lh-n { font-size: 20px; font-weight: 700; color: #22dd88; }
.lh-n.warn { color: #ffcc44; }
.lh-l { font-size: 12px; color: #c0c4cf; display: flex; flex-direction: column; gap: 2px; }
.lh-sub { font-size: 11px; color: #77808f; }
.lh-chip-list { display: flex; flex-wrap: wrap; gap: 5px; }
.lh-chip { background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.1);
  color: #c0c4cf; font-size: 11.5px; padding: 2px 9px; border-radius: 10px; cursor: pointer;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lh-chip:hover { border-color: rgba(47,134,214,.5); color: #fff; }
.lh-more { font-size: 11px; color: #77808f; align-self: center; }
.lh-note { font-size: 11.5px; color: #8a93a5; line-height: 1.6; }
.lh-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.lh-rows { display: flex; flex-direction: column; gap: 3px; max-height: 260px; overflow-y: auto; }
.lh-row { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #c0c4cf;
  background: rgba(255,255,255,.02); border-radius: 6px; padding: 4px 8px; }
.lh-row-status { width: 16px; text-align: center; flex-shrink: 0; }
.lh-row-label { background: none; border: none; color: #9ecbff; cursor: pointer; font-size: 12px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; text-align: left; padding: 0; }
.lh-verdict { font-weight: 600; font-size: 11.5px; flex-shrink: 0; }
.lh-rate { font-size: 11px; color: #8a93a5; flex-shrink: 0; }
.lh-err { font-size: 11px; color: tomato; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 140px; }
.lh-err-block { color: tomato; background: rgba(255,99,71,.12); padding: 8px 12px;
  border-radius: 6px; font-size: 12px; }
.lh-select { background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.14);
  border-radius: 6px; padding: 4px 8px; color: #e8eaed; font-size: 12.5px; max-width: 220px; }
.lh-drift-group { display: flex; flex-direction: column; gap: 6px; }
.lh-drift-title { font-size: 11.5px; font-weight: 600; }
.lh-drift-item { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; }
.lh-drift-key { font-family: 'JetBrains Mono', monospace; color: #e8b4b4; flex-shrink: 0;
  background: rgba(255,255,255,.04); padding: 1px 7px; border-radius: 6px; }
.lh-drift-refs { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
</style>
