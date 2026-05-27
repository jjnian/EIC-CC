<script setup lang="ts">
import { ref } from 'vue';
import { executeSql } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import type { SqlExecuteResult } from '../../api/dataSources';

const props = defineProps<{ dsId: string }>();
const sql = ref('SELECT 1');
const limit = ref(100);
const result = ref<SqlExecuteResult | null>(null);
const running = ref(false);
const err = ref<string>('');

const run = async () => {
  running.value = true;
  err.value = '';
  result.value = null;
  try { result.value = await executeSql(props.dsId, sql.value, limit.value); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { running.value = false; }
};
</script>

<template>
  <div class="tab">
    <div class="toolbar">
      <textarea v-model="sql" rows="4" placeholder="仅允许 SELECT / SHOW / DESC / EXPLAIN" />
      <div class="row">
        <label>LIMIT <input type="number" v-model.number="limit" min="1" max="1000" /></label>
        <button class="primary" :disabled="running" @click="run">{{ running ? '执行中…' : '执行' }}</button>
      </div>
    </div>
    <div class="output">
      <div v-if="err" class="msg err">{{ err }}</div>
      <div v-else-if="result">
        <div class="meta">{{ result.rowCount }} 行 · {{ result.durationMs }}ms{{ result.truncated ? ' · 已截断' : '' }}</div>
        <div class="scroll">
          <table>
            <thead><tr><th v-for="c in result.columns" :key="c">{{ c }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, i) in result.rows" :key="i">
                <td v-for="(v, j) in row" :key="j">{{ v === null ? '∅' : String(v) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab { padding: 12px; display: flex; flex-direction: column; gap: 10px; height: 100%; min-height: 320px; }
.toolbar { display: flex; flex-direction: column; gap: 8px; }
textarea { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 8px; color: #e8eaed; font-family: monospace; font-size: 13px; }
.row { display: flex; align-items: center; gap: 12px; }
.row label { color: #aaa; font-size: 12px; }
.row input { width: 80px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 8px; color: #e8eaed; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; margin-left: auto; }
.primary:disabled { opacity: .5; }
.output { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
.scroll { overflow: auto; flex: 1; }
.meta { color: #aaa; font-size: 12px; margin-bottom: 8px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid rgba(255,255,255,.06); color: #e8eaed; white-space: nowrap; }
th { color: #aaa; position: sticky; top: 0; background: #1d1f24; }
.msg.err { color: tomato; padding: 12px; }
</style>
