<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { listFetchLogs } from '../../api/dataSources';
import type { FetchLog } from '../../api/dataSources';
import { Button } from '@/components/ui/button';

const props = defineProps<{ dsId: string }>();
const logs = ref<FetchLog[]>([]);
const loading = ref(false);
const expandedId = ref<number | null>(null);

const load = async () => {
  loading.value = true;
  try { logs.value = await listFetchLogs(props.dsId); }
  finally { loading.value = false; }
};
const fmtTime = (t: number) => new Date(t).toLocaleString();

onMounted(load);
watch(() => props.dsId, load);
</script>

<template>
  <div class="tab">
    <div class="bar">
      <span>最近 {{ logs.length }} 条</span>
      <Button variant="ghost" size="icon-sm" @click="load">↻</Button>
    </div>
    <div class="list">
      <div v-if="loading" class="msg">加载中…</div>
      <div v-else-if="!logs.length" class="msg">暂无记录</div>
      <div v-for="l in logs" :key="l.id" class="log">
        <div class="row" @click="expandedId = expandedId === l.id ? null : l.id">
          <span :class="['dot', l.success ? 'ok' : 'bad']" />
          <span class="code">{{ l.statusCode ?? '—' }}</span>
          <span class="time">{{ fmtTime(l.fetchedAt) }}</span>
          <span class="dur">{{ l.durationMs }}ms</span>
        </div>
        <div v-if="expandedId === l.id" class="body">
          <div v-if="l.errorMsg" class="err">{{ l.errorMsg }}</div>
          <pre v-if="l.responseBody">{{ l.responseBody }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab { padding: 12px; height: 100%; min-height: 320px; display: flex; flex-direction: column; }
.bar { display: flex; align-items: center; gap: 8px; color: var(--text-dim, #52525b); font-size: 12px; margin-bottom: 8px; }
.bar button { background: none; border: 1px solid var(--glass-border, rgba(0,0,0,0.09)); border-radius: 6px; padding: 2px 8px; color: var(--text-dim, #52525b); cursor: pointer; }
.list { flex: 1; overflow: auto; }
.log { border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); }
.row { display: flex; gap: 12px; align-items: center; padding: 8px 4px; cursor: pointer; font-size: 12px; }
.row:hover { background: var(--bg-elev, rgba(0,0,0,0.045)); }
.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.ok { background: #059669; }
.dot.bad { background: #dc2626; }
.code { color: var(--text-dim, #52525b); font-weight: 600; min-width: 36px; }
.time { color: var(--text-muted, #a1a1aa); flex: 1; }
.dur { color: var(--text-dim, #52525b); }
.body { padding: 8px; background: var(--bg-subtle, #f7f8fa); }
.body pre { margin: 0; font-family: monospace; font-size: 12px; color: var(--text-main, #18181b); white-space: pre-wrap; word-break: break-all; }
.err { color: #dc2626; font-size: 12px; margin-bottom: 6px; }
.msg { color: var(--text-muted, #a1a1aa); padding: 12px; }
</style>
