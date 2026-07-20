<script setup lang="ts">
import { ref } from 'vue';
import { executeSql } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { Button } from '@/components/ui/button';
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
        <Button :disabled="running" @click="run">{{ running ? '执行中…' : '执行' }}</Button>
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
textarea { background: #fff; border: 1px solid var(--glass-border, rgba(0,0,0,0.09)); border-radius: 6px; padding: 8px; color: var(--text-main, #18181b); font-family: monospace; font-size: 13px; }
.row { display: flex; align-items: center; gap: 12px; }
.row label { color: var(--text-dim, #52525b); font-size: 12px; }
.row input { width: 80px; background: #fff; border: 1px solid var(--glass-border, rgba(0,0,0,0.09)); border-radius: 6px; padding: 4px 8px; color: var(--text-main, #18181b); }
.primary { background: var(--accent, #18181b); border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; margin-left: auto; }
.primary:disabled { opacity: .5; }
.output { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
.scroll { overflow: auto; flex: 1; }
.meta { color: var(--text-dim, #52525b); font-size: 12px; margin-bottom: 8px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); color: var(--text-main, #18181b); white-space: nowrap; }
th { color: var(--text-dim, #52525b); position: sticky; top: 0; background: var(--bg-subtle, #f7f8fa); }
.msg.err { color: #dc2626; padding: 12px; }
</style>
