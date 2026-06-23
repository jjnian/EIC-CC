<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { listDataSources, listTables, type DataSource } from '../api/dataSources';
import {
  listNodeBindings, createNodeBinding, deleteNodeBinding, fetchNodeBindingData,
  type NodeBinding, type BindingFetchResult,
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
</script>

<template>
  <div class="ndb">
    <div v-if="!modelId" class="ndb-hint">请先保存当前模型，再为节点绑定数据源供血。</div>

    <template v-else>
      <div class="ndb-head">
        <span class="ndb-title">数据供血绑定 <span class="ndb-count">{{ bindings.length }}</span></span>
        <Button v-if="!adding" variant="ghost" size="sm" class="ndb-add" @click="startAdd">＋ 绑定数据源</Button>
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
          <span class="ndb-spacer" />
          <Button variant="outline" size="sm" class="ndb-mini" :disabled="fetching === b.id" @click="runFetch(b)">
            {{ fetching === b.id ? '取数中…' : '取数预览' }}
          </Button>
          <Button variant="ghost" size="icon-sm" class="ndb-mini del" title="删除绑定" @click="removeBinding(b)">✕</Button>
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
.ndb { padding: 4px 2px; font-size: 13px; color: var(--text-main, #e8eaed); }
.ndb-hint { color: #888; font-size: 12.5px; padding: 10px 2px; }
.ndb-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.ndb-title { font-weight: 600; }
.ndb-count { font-size: 11px; background: rgba(74,141,240,.18); color: #9cc4ff; padding: 0 7px; border-radius: 100px; margin-left: 4px; }
.ndb-add { background: rgba(47,134,214,.16); color: var(--accent-soft); border: 1px solid rgba(47,134,214,.4);
  border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; font-family: inherit; }
.ndb-add:hover { background: rgba(47,134,214,.26); }
.ndb-desc { margin: 0 0 10px; font-size: 11.5px; color: #8a91a0; line-height: 1.5; }

.ndb-form { background: rgba(255,255,255,.03); border: 1px solid rgba(255,255,255,.08);
  border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; display: flex; flex-direction: column; gap: 8px; }
.ndb-empty-ds { color: #f0c660; font-size: 12px; }
.ndb-row { display: flex; align-items: center; gap: 10px; }
.ndb-row > span { min-width: 56px; color: #8a91a0; font-size: 12px; }
.ndb-ctl { flex: 1; min-width: 0; }
.ndb-form-actions { display: flex; gap: 8px; }
.ndb-btn { padding: 6px 14px; border-radius: 6px; border: none; cursor: pointer; font-size: 12.5px; font-family: inherit; }
.ndb-btn.primary { background: var(--accent); color: #fff; font-weight: 600; }
.ndb-btn.primary:disabled { opacity: .5; cursor: default; }
.ndb-btn.ghost { background: transparent; color: #aaa; border: 1px solid rgba(255,255,255,.12); }

.ndb-item { border: 1px solid rgba(255,255,255,.08); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; }
.ndb-item-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ndb-item-ds { font-weight: 600; color: #b9d4ff; }
.ndb-item-table { color: var(--text-main, #e8eaed); }
.ndb-item-filter { font-size: 11px; color: #8a91a0; font-family: 'JetBrains Mono', monospace; }
.ndb-spacer { flex: 1; }
.ndb-mini { background: rgba(255,255,255,.06); color: #c0c4cf; border: 1px solid rgba(255,255,255,.12);
  border-radius: 6px; padding: 3px 9px; font-size: 11.5px; cursor: pointer; font-family: inherit; }
.ndb-mini:hover { background: rgba(255,255,255,.12); }
.ndb-mini.del { color: #ff8a6f; }
.ndb-mini:disabled { opacity: .5; cursor: default; }

.ndb-result { margin-top: 8px; }
.ndb-result-meta { font-size: 11px; color: #888; margin-bottom: 4px; }
.ndb-scroll { overflow: auto; max-height: 240px; border: 1px solid rgba(255,255,255,.06); border-radius: 6px; }
.ndb-result table { width: 100%; border-collapse: collapse; font-size: 11.5px; }
.ndb-result th, .ndb-result td { padding: 4px 8px; text-align: left;
  border-bottom: 1px solid rgba(255,255,255,.06); white-space: nowrap; color: var(--text-main, #e8eaed); }
.ndb-result th { color: #8a91a0; position: sticky; top: 0; background: #1d1f24; }
</style>
