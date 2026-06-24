<script setup lang="ts">
import { ref } from 'vue';
import { executeHttp } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { Button } from '@/components/ui/button';
import type { DataSource, HttpExecuteResult } from '../../api/dataSources';

const props = defineProps<{ ds: DataSource }>();
const result = ref<HttpExecuteResult | null>(null);
const running = ref(false);
const err = ref<string>('');

const run = async () => {
  running.value = true;
  err.value = '';
  result.value = null;
  try { result.value = await executeHttp(props.ds.id); }
  catch (e) { err.value = e instanceof ApiError ? e.message : (e as Error).message; }
  finally { running.value = false; }
};

const prettyBody = (s?: string) => {
  if (!s) return '';
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
};
</script>

<template>
  <div class="tab">
    <div class="left">
      <h4>请求</h4>
      <dl>
        <dt>方法</dt><dd>{{ (ds.config as any)?.method || 'GET' }}</dd>
        <dt>URL</dt><dd class="url">{{ (ds.config as any)?.url }}</dd>
        <dt v-if="(ds.config as any)?.headers && Object.keys((ds.config as any).headers).length">Headers</dt>
        <dd v-if="(ds.config as any)?.headers && Object.keys((ds.config as any).headers).length">
          <div v-for="(v, k) in (ds.config as any).headers" :key="k"><b>{{ k }}:</b> {{ v }}</div>
        </dd>
        <dt v-if="(ds.config as any)?.body">Body</dt>
        <dd v-if="(ds.config as any)?.body"><pre>{{ (ds.config as any).body }}</pre></dd>
      </dl>
      <Button :disabled="running" @click="run">{{ running ? '执行中…' : '立即执行' }}</Button>
    </div>
    <div class="right">
      <h4>响应</h4>
      <div v-if="err" class="msg err">{{ err }}</div>
      <div v-else-if="!result" class="msg">点击「立即执行」</div>
      <div v-else>
        <div class="status">
          <span :class="['code', result.success ? 'ok' : 'bad']">{{ result.statusCode ?? '—' }}</span>
          <span class="latency">{{ result.durationMs }}ms</span>
          <span v-if="result.truncated" class="trunc">已截断</span>
        </div>
        <div v-if="result.errorMsg" class="errbox">{{ result.errorMsg }}</div>
        <pre v-if="result.body" class="body">{{ prettyBody(result.body) }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab { display: flex; gap: 12px; padding: 12px; height: 100%; min-height: 320px; }
.left, .right { flex: 1; display: flex; flex-direction: column; min-width: 0; }
h4 { margin: 0 0 8px; color: #aaa; font-size: 13px; }
dl { display: grid; grid-template-columns: 80px 1fr; gap: 4px 12px; font-size: 12px; margin-bottom: 12px; }
dt { color: #888; }
dd { color: #e8eaed; margin: 0; word-break: break-all; }
dd.url { font-family: monospace; }
dd pre { margin: 0; font-size: 12px; background: rgba(255,255,255,.03); padding: 6px; border-radius: 4px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
.status { display: flex; gap: 12px; align-items: center; margin-bottom: 8px; font-size: 12px; }
.code { padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.code.ok { background: rgba(34,221,136,.2); color: #22dd88; }
.code.bad { background: rgba(255,99,71,.2); color: tomato; }
.latency { color: #aaa; }
.trunc { color: #ffaa22; }
.errbox { color: tomato; background: rgba(255,99,71,.1); padding: 6px; border-radius: 4px; font-size: 12px; margin-bottom: 8px; }
.body { flex: 1; overflow: auto; background: rgba(255,255,255,.03); padding: 8px; border-radius: 6px; color: #e8eaed; font-family: monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.msg { color: #888; padding: 8px; }
.msg.err { color: tomato; }
</style>
