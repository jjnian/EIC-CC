<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import { listTables, previewTable } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import type { TablePreview } from '../../api/dataSources';
import type { OntologyNode, OntologyEdge } from '../../types';
import DbOntologyExtractDialog from './DbOntologyExtractDialog.vue';

const props = defineProps<{ dsId: string; dsName?: string; hasCurrentModel?: boolean }>();
const emit = defineEmits<{
  (e: 'ontology-extracted', payload: {
    mode: 'merge' | 'new';
    name: string;
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }): void;
}>();
const tables = ref<string[]>([]);
const loading = ref(false);
const selected = ref<string | null>(null);
const preview = ref<TablePreview | null>(null);
const previewing = ref(false);
const err = ref<string>('');
const extractDialogOpen = ref(false);

const onExtractCommit = (payload: {
  mode: 'merge' | 'new';
  name: string;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}) => {
  extractDialogOpen.value = false;
  emit('ontology-extracted', payload);
};

const load = async () => {
  loading.value = true;
  err.value = '';
  try { tables.value = await listTables(props.dsId); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { loading.value = false; }
};
const pick = async (name: string) => {
  selected.value = name;
  preview.value = null;
  previewing.value = true;
  err.value = '';
  try { preview.value = await previewTable(props.dsId, name, 50); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { previewing.value = false; }
};

onMounted(load);
watch(() => props.dsId, () => { tables.value = []; selected.value = null; preview.value = null; load(); });
</script>

<template>
  <div class="tab">
    <aside class="tlist">
      <div class="head">
        <span>表 ({{ tables.length }})</span>
        <button @click="load">↻</button>
      </div>
      <div class="extract-action">
        <button
          class="extract-btn"
          :disabled="loading || tables.length === 0"
          :title="tables.length === 0 ? '请先确保数据库连接成功' : '基于 schema (表+列+外键) 生成本体血缘图'"
          @click="extractDialogOpen = true"
        >🧬 一键生成本体血缘图</button>
      </div>
      <div v-if="loading" class="msg">加载中…</div>
      <div v-else-if="err" class="msg err">{{ err }}</div>
      <ul v-else>
        <li v-for="t in tables" :key="t" :class="{ active: t === selected }" @click="pick(t)">{{ t }}</li>
      </ul>
    </aside>
    <main class="ptable">
      <div v-if="!selected" class="hint">从左侧选择一张表预览前 50 行</div>
      <div v-else-if="previewing" class="msg">查询中…</div>
      <div v-else-if="preview">
        <div class="meta">{{ selected }}：{{ preview.rowCount }} 行{{ preview.truncated ? '（已截断）' : '' }}</div>
        <div class="scroll">
          <table>
            <thead><tr><th v-for="c in preview.columns" :key="c">{{ c }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, i) in preview.rows" :key="i">
                <td v-for="(v, j) in row" :key="j">{{ v === null ? '∅' : String(v) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </main>

    <DbOntologyExtractDialog
      :open="extractDialogOpen"
      :ds-id="dsId"
      :ds-name="dsName || ''"
      :has-current-model="!!hasCurrentModel"
      @close="extractDialogOpen = false"
      @commit="onExtractCommit"
    />
  </div>
</template>

<style scoped>
.tab { display: flex; height: 100%; min-height: 320px; }
.tlist { width: 220px; border-right: 1px solid rgba(255,255,255,.06); display: flex; flex-direction: column; }
.tlist .head { display: flex; align-items: center; padding: 8px 12px; color: #888; font-size: 12px; }
.tlist .head span { flex: 1; }
.tlist .head button { background: none; border: none; color: #aaa; cursor: pointer; }
.extract-action { padding: 4px 8px 8px; border-bottom: 1px solid rgba(255,255,255,.04); }
.extract-btn { width: 100%; padding: 6px 8px; font-size: 12px; border-radius: 6px;
  background: linear-gradient(135deg, rgba(74,141,240,.18), rgba(74,141,240,.08));
  color: #b9d4ff; border: 1px solid rgba(74,141,240,.3); cursor: pointer;
  transition: background .15s; }
.extract-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, rgba(74,141,240,.32), rgba(74,141,240,.18));
  color: #fff; }
.extract-btn:disabled { opacity: .35; cursor: not-allowed; }
.tlist ul { list-style: none; padding: 0; margin: 0; overflow-y: auto; flex: 1; }
.tlist li { padding: 6px 12px; cursor: pointer; color: #c0c4cf; font-size: 13px; }
.tlist li:hover { background: rgba(255,255,255,.06); }
.tlist li.active { background: rgba(74,141,240,.2); color: #fff; }
.ptable { flex: 1; padding: 12px; overflow: hidden; display: flex; flex-direction: column; }
.meta { color: #aaa; font-size: 12px; margin-bottom: 8px; }
.scroll { overflow: auto; flex: 1; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid rgba(255,255,255,.06); color: #e8eaed; white-space: nowrap; }
th { color: #aaa; position: sticky; top: 0; background: #1d1f24; }
.hint, .msg { color: #888; padding: 12px; font-size: 13px; }
.msg.err { color: tomato; }
</style>
