<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { listDataSources, listTables, type DataSource } from '../api/dataSources';
import {
  listNodeBindings, createNodeBinding, deleteNodeBinding, fetchNodeBindingData,
  updateBindingStatusConfig, refreshBindingStatus,
  type NodeBinding, type BindingFetchResult, type NodeState, type StatusRule,
} from '../api/nodeBindings';
import { ApiError } from '../api/http';
import { toast } from '../composables/useToast';
import { Button } from '@/components/ui/button';
import BaseInput from './form/BaseInput.vue';
import BaseSelect from './form/BaseSelect.vue';

const props = defineProps<{
  modelId?: string;
  nodeId: string;
  nodeLabel?: string;
}>();

const bindings = ref<NodeBinding[]>([]);
const dataSources = ref<DataSource[]>([]);
const loading = ref(false);

// 新增表单
const adding = ref(false);
const formDsId = ref('');
const formTable = ref('');
const formFilter = ref('');
const tableOptions = ref<string[]>([]);
const tablesLoading = ref(false);
const saving = ref(false);

// 取数预览结果（按 binding id 缓存）
const fetchResults = ref<Record<string, BindingFetchResult>>({});
const fetching = ref<string | null>(null);

const dbSources = computed(() =>
  dataSources.value.filter(d => ['mysql', 'pgsql', 'oracle', 'dm', 'gbase'].includes(d.kind)));
const dsOptions = computed(() => dbSources.value.map(d => ({ value: d.id, label: `${d.name}（${d.kind}）` })));
const dsName = (id: string) => dataSources.value.find(d => d.id === id)?.name || id;

const loadBindings = async () => {
  if (!props.modelId || !props.nodeId) { bindings.value = []; return; }
  loading.value = true;
  try {
    bindings.value = await listNodeBindings(props.modelId, props.nodeId);
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '加载绑定失败');
  } finally {
    loading.value = false;
  }
};

const loadDataSources = async () => {
  try { dataSources.value = await listDataSources(); }
  catch { /* 静默：无数据源时下面给提示 */ }
};

onMounted(() => { loadDataSources(); loadBindings(); });
watch(() => props.nodeId, () => { fetchResults.value = {}; loadBindings(); });

watch(formDsId, async (id) => {
  formTable.value = '';
  tableOptions.value = [];
  if (!id) return;
  tablesLoading.value = true;
  try { tableOptions.value = await listTables(id); }
  catch { tableOptions.value = []; }
  finally { tablesLoading.value = false; }
});

const startAdd = () => {
  adding.value = true;
  formDsId.value = dbSources.value[0]?.id || '';
  formTable.value = '';
  formFilter.value = '';
};
const cancelAdd = () => { adding.value = false; };

const saveBinding = async () => {
  if (!props.modelId) { toast.warn('请先保存当前模型再绑定数据源'); return; }
  if (!formDsId.value) { toast.warn('请选择数据源'); return; }
  if (!formTable.value.trim()) { toast.warn('请选择/填写表名'); return; }
  saving.value = true;
  try {
    const created = await createNodeBinding({
      modelId: props.modelId,
      nodeId: props.nodeId,
      dataSourceId: formDsId.value,
      tableName: formTable.value.trim(),
      filterSql: formFilter.value.trim() || undefined,
    });
    bindings.value = [created, ...bindings.value];
    adding.value = false;
    toast.success('已绑定数据源供血');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '绑定失败');
  } finally {
    saving.value = false;
  }
};

const removeBinding = async (b: NodeBinding) => {
  try {
    await deleteNodeBinding(b.id);
    bindings.value = bindings.value.filter(x => x.id !== b.id);
    delete fetchResults.value[b.id];
    toast.success('已删除绑定');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
};

const runFetch = async (b: NodeBinding) => {
  fetching.value = b.id;
  try {
    fetchResults.value = { ...fetchResults.value, [b.id]: await fetchNodeBindingData(b.id, 50) };
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '取数失败');
  } finally {
    fetching.value = null;
  }
};

// ── 态势层：状态监测配置（状态查询 SQL → 阈值判级 → 定时刷新节点状态）──
interface StatusForm { query: string; alertOp: string; alertVal: string; warnOp: string; warnVal: string;
  interval: number; enabled: boolean }
const statusOpen = ref<Record<string, boolean>>({});
const statusForms = ref<Record<string, StatusForm>>({});
const statusSaving = ref<string | null>(null);
const statusRefreshing = ref<string | null>(null);
const statusResults = ref<Record<string, NodeState>>({});

const OP_OPTIONS = [
  { value: 'gt', label: '＞' }, { value: 'gte', label: '≥' },
  { value: 'lt', label: '＜' }, { value: 'lte', label: '≤' },
  { value: 'eq', label: '=' }, { value: 'ne', label: '≠' },
  { value: 'contains', label: '包含' },
];

/** 从绑定的 statusRules JSON 解析出 alert/warn 两行（超出两条的规则原样保留在首两条外会丢失——UI 简化取舍）。 */
const toStatusForm = (b: NodeBinding): StatusForm => {
  const f: StatusForm = { query: b.statusQuery || '', alertOp: 'gt', alertVal: '', warnOp: 'gt', warnVal: '',
    interval: b.statusIntervalSec || 60, enabled: !!b.statusEnabled };
  try {
    const rules: StatusRule[] = JSON.parse(b.statusRules || '[]');
    for (const r of rules) {
      if (r.level === 'alert' && !f.alertVal) { f.alertOp = r.op; f.alertVal = r.value; }
      if (r.level === 'warn' && !f.warnVal) { f.warnOp = r.op; f.warnVal = r.value; }
    }
  } catch { /* 规则损坏时从空白开始 */ }
  return f;
};

const toggleStatus = (b: NodeBinding) => {
  const open = !statusOpen.value[b.id];
  statusOpen.value = { ...statusOpen.value, [b.id]: open };
  if (open && !statusForms.value[b.id]) {
    statusForms.value = { ...statusForms.value, [b.id]: toStatusForm(b) };
  }
};

const saveStatusConfig = async (b: NodeBinding) => {
  const f = statusForms.value[b.id];
  if (!f) return;
  if (f.enabled && !f.query.trim()) { toast.warn('启用定时刷新前请先填写状态查询 SQL'); return; }
  const rules: StatusRule[] = [];
  if (f.alertVal.trim()) rules.push({ level: 'alert', op: f.alertOp, value: f.alertVal.trim() });
  if (f.warnVal.trim()) rules.push({ level: 'warn', op: f.warnOp, value: f.warnVal.trim() });
  statusSaving.value = b.id;
  try {
    const updated = await updateBindingStatusConfig(b.id, {
      statusQuery: f.query.trim() || undefined,
      statusRules: rules.length ? JSON.stringify(rules) : undefined,
      statusEnabled: f.enabled,
      statusIntervalSec: Math.max(60, f.interval || 60),
    });
    bindings.value = bindings.value.map(x => x.id === b.id ? updated : x);
    toast.success(f.enabled ? '状态源已保存并纳入定时刷新' : '状态源配置已保存');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '保存失败');
  } finally {
    statusSaving.value = null;
  }
};

const runStatusRefresh = async (b: NodeBinding) => {
  const f = statusForms.value[b.id];
  if (f && f.query.trim() && f.query.trim() !== (b.statusQuery || '')) {
    await saveStatusConfig(b);   // 未保存的查询先落库再刷新，避免"刷的是旧 SQL"
  }
  statusRefreshing.value = b.id;
  try {
    const st = await refreshBindingStatus(b.id);
    statusResults.value = { ...statusResults.value, [b.id]: st };
    if (st.level === 'error') toast.warn(`采集失败：${st.message || '未知错误'}`);
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '刷新失败');
  } finally {
    statusRefreshing.value = null;
  }
};

const levelView = (level?: string) => ({
  normal: { text: '正常', color: '#22dd88' },
  warn:   { text: '关注', color: '#ffcc44' },
  alert:  { text: '告警', color: '#ff5555' },
  error:  { text: '失联', color: '#999' },
}[level || 'normal'] || { text: level || '—', color: '#999' });
</script>

<template>
  <div class="ndb">
    <div v-if="!modelId" class="ndb-hint">请先保存当前模型，再为节点绑定数据源供血。</div>

    <template v-else>
      <div class="ndb-head">
        <span class="ndb-title">数据供血绑定 <span class="ndb-count">{{ bindings.length }}</span></span>
        <Button v-if="!adding" variant="outline" size="sm" @click="startAdd">＋ 绑定数据源</Button>
      </div>
      <p class="ndb-desc">把节点「{{ nodeLabel || nodeId }}」绑定到数据源的表，运行时按绑定取数为该节点供血。</p>

      <!-- 新增表单 -->
      <div v-if="adding" class="ndb-form">
        <div v-if="dbSources.length === 0" class="ndb-empty-ds">
          当前工作空间还没有数据库数据源（mysql / pgsql），请先在「数据源」里添加。
        </div>
        <template v-else>
          <label class="ndb-row">
            <span>数据源</span>
            <div class="ndb-ctl"><BaseSelect v-model="formDsId" :options="dsOptions" placeholder="请选择数据源" /></div>
          </label>
          <label class="ndb-row">
            <span>表名</span>
            <div class="ndb-ctl">
              <BaseSelect v-if="tableOptions.length" v-model="formTable" :options="tableOptions" :placeholder="tablesLoading ? '加载中…' : '请选择表'" />
              <BaseInput v-else v-model="formTable" :placeholder="tablesLoading ? '加载表…' : '输入表名'" />
            </div>
          </label>
          <label class="ndb-row">
            <span>过滤条件</span>
            <div class="ndb-ctl"><BaseInput v-model="formFilter" placeholder="可选 WHERE 片段，如 status = 1（只读）" /></div>
          </label>
          <div class="ndb-form-actions">
            <Button size="sm" :disabled="saving" @click="saveBinding">{{ saving ? '保存中…' : '保存绑定' }}</Button>
            <Button variant="ghost" size="sm" @click="cancelAdd">取消</Button>
          </div>
        </template>
      </div>

      <div v-if="loading" class="ndb-hint">加载中…</div>
      <div v-else-if="bindings.length === 0 && !adding" class="ndb-hint">该节点还没有数据供血绑定。</div>

      <!-- 绑定列表 -->
      <div v-for="b in bindings" :key="b.id" class="ndb-item">
        <div class="ndb-item-head">
          <span class="ndb-item-ds">{{ dsName(b.dataSourceId) }}</span>
          <span class="ndb-item-table">· {{ b.tableName || '(未指定表)' }}</span>
          <span v-if="b.filterSql" class="ndb-item-filter">WHERE {{ b.filterSql }}</span>
          <span v-if="b.statusEnabled" class="ndb-status-on" title="已纳入定时状态刷新">📡</span>
          <span class="ndb-spacer" />
          <Button variant="outline" size="sm" :disabled="fetching === b.id" @click="runFetch(b)">
            {{ fetching === b.id ? '取数中…' : '取数预览' }}
          </Button>
          <Button variant="outline" size="sm" @click="toggleStatus(b)">
            {{ statusOpen[b.id] ? '收起监测' : '状态监测' }}
          </Button>
          <Button variant="ghost" size="icon-sm" class="text-destructive" title="删除绑定" @click="removeBinding(b)">✕</Button>
        </div>

        <!-- 态势层：状态监测配置 -->
        <div v-if="statusOpen[b.id] && statusForms[b.id]" class="ndb-status">
          <p class="ndb-desc" style="margin:0">
            状态查询（只读 SQL，<b>首行首列</b>作为节点状态值）定时执行，按阈值给节点判级着色——供血让世界活起来。
          </p>
          <label class="ndb-row">
            <span>状态查询</span>
            <div class="ndb-ctl"><BaseInput v-model="statusForms[b.id].query" placeholder="如 SELECT count(*) FROM orders WHERE status = 'blocked'" /></div>
          </label>
          <div class="ndb-row">
            <span style="color:#ff5555">告警阈值</span>
            <div class="ndb-ctl ndb-rule">
              <BaseSelect v-model="statusForms[b.id].alertOp" :options="OP_OPTIONS" />
              <BaseInput v-model="statusForms[b.id].alertVal" placeholder="值（留空=不启用）" />
            </div>
          </div>
          <div class="ndb-row">
            <span style="color:#ffcc44">关注阈值</span>
            <div class="ndb-ctl ndb-rule">
              <BaseSelect v-model="statusForms[b.id].warnOp" :options="OP_OPTIONS" />
              <BaseInput v-model="statusForms[b.id].warnVal" placeholder="值（留空=不启用）" />
            </div>
          </div>
          <div class="ndb-row">
            <span>定时刷新</span>
            <div class="ndb-ctl ndb-rule">
              <label class="ndb-check"><input type="checkbox" v-model="statusForms[b.id].enabled" /> 启用</label>
              <BaseInput v-model.number="statusForms[b.id].interval" type="number" placeholder="间隔秒(≥60)" style="max-width:120px" />
            </div>
          </div>
          <div class="ndb-form-actions">
            <Button size="sm" :disabled="statusSaving === b.id" @click="saveStatusConfig(b)">
              {{ statusSaving === b.id ? '保存中…' : '保存配置' }}
            </Button>
            <Button variant="outline" size="sm" :disabled="statusRefreshing === b.id || !statusForms[b.id].query.trim()" @click="runStatusRefresh(b)">
              {{ statusRefreshing === b.id ? '采集中…' : '立即刷新' }}
            </Button>
            <span v-if="statusResults[b.id]" class="ndb-status-val">
              <span :style="{ color: levelView(statusResults[b.id].level).color }">● {{ levelView(statusResults[b.id].level).text }}</span>
              <span class="ndb-status-num">{{ statusResults[b.id].value ?? '∅' }}</span>
            </span>
          </div>
        </div>

        <div v-if="fetchResults[b.id]" class="ndb-result">
          <div class="ndb-result-meta">
            {{ fetchResults[b.id].rowCount }} 行{{ fetchResults[b.id].truncated ? '（已截断）' : '' }}
          </div>
          <div class="ndb-scroll">
            <table>
              <thead><tr><th v-for="c in fetchResults[b.id].columns" :key="c">{{ c }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, i) in fetchResults[b.id].rows" :key="i">
                  <td v-for="(v, j) in row" :key="j">{{ v === null ? '∅' : String(v) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ndb { padding: 4px 2px; font-size: 13px; color: var(--text-main, #18181b); }
.ndb-hint { color: var(--text-muted, #a1a1aa); font-size: 12.5px; padding: 10px 2px; }
.ndb-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.ndb-title { font-weight: 600; }
.ndb-count { font-size: 11px; background: rgba(37,99,235,0.10); color: #2563eb; padding: 0 7px; border-radius: 100px; margin-left: 4px; }
.ndb-add { background: rgba(37,99,235,0.06); color: #2563eb; border: 1px solid rgba(37,99,235,0.35);
  border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; font-family: inherit; }
.ndb-add:hover { background: rgba(37,99,235,0.12); }
.ndb-desc { margin: 0 0 10px; font-size: 11.5px; color: var(--text-dim, #52525b); line-height: 1.5; }

.ndb-form { background: var(--bg-subtle, #f7f8fa); border: 1px solid var(--hairline, rgba(0,0,0,0.07));
  border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; display: flex; flex-direction: column; gap: 8px; }
.ndb-empty-ds { color: #d97706; font-size: 12px; }
.ndb-row { display: flex; align-items: center; gap: 10px; }
.ndb-row > span { min-width: 56px; color: var(--text-dim, #52525b); font-size: 12px; }
.ndb-ctl { flex: 1; min-width: 0; }
.ndb-form-actions { display: flex; gap: 8px; }
.ndb-btn { padding: 6px 14px; border-radius: 6px; border: none; cursor: pointer; font-size: 12.5px; font-family: inherit; }
.ndb-btn.primary { background: var(--accent, #18181b); color: #fff; font-weight: 600; }
.ndb-btn.primary:disabled { opacity: .5; cursor: default; }
.ndb-btn.ghost { background: transparent; color: var(--text-dim, #52525b); border: 1px solid var(--glass-border, rgba(0,0,0,0.09)); }

.ndb-item { border: 1px solid var(--hairline, rgba(0,0,0,0.07)); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; }
.ndb-item-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ndb-item-ds { font-weight: 600; color: #2563eb; }
.ndb-item-table { color: var(--text-main, #18181b); }
.ndb-item-filter { font-size: 11px; color: var(--text-dim, #52525b); font-family: 'JetBrains Mono', monospace; }
.ndb-spacer { flex: 1; }
.ndb-mini { background: var(--bg-elev, rgba(0,0,0,0.045)); color: var(--text-dim, #52525b); border: 1px solid var(--glass-border, rgba(0,0,0,0.09));
  border-radius: 6px; padding: 3px 9px; font-size: 11.5px; cursor: pointer; font-family: inherit; }
.ndb-mini:hover { background: rgba(0,0,0,0.08); }
.ndb-mini.del { color: #dc2626; }
.ndb-mini:disabled { opacity: .5; cursor: default; }

.ndb-status { margin-top: 8px; padding: 10px 12px; border-radius: 8px;
  background: rgba(255,255,255,.03); border: 1px dashed rgba(255,255,255,.12);
  display: flex; flex-direction: column; gap: 8px; }
.ndb-status-on { font-size: 12px; }
.ndb-rule { display: flex; gap: 8px; align-items: center; }
.ndb-check { display: flex; align-items: center; gap: 5px; color: #c0c4cf; font-size: 12px; white-space: nowrap; }
.ndb-status-val { display: flex; align-items: center; gap: 8px; font-size: 12px; margin-left: 4px; }
.ndb-status-num { font-family: 'JetBrains Mono', monospace; color: #dde3ee; }

.ndb-result { margin-top: 8px; }
.ndb-result-meta { font-size: 11px; color: var(--text-muted, #a1a1aa); margin-bottom: 4px; }
.ndb-scroll { overflow: auto; max-height: 240px; border: 1px solid var(--hairline, rgba(0,0,0,0.07)); border-radius: 6px; }
.ndb-result table { width: 100%; border-collapse: collapse; font-size: 11.5px; }
.ndb-result th, .ndb-result td { padding: 4px 8px; text-align: left;
  border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); white-space: nowrap; color: var(--text-main, #18181b); }
.ndb-result th { color: var(--text-dim, #52525b); position: sticky; top: 0; background: var(--bg-subtle, #f7f8fa); }
</style>
