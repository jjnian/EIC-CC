<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { DataSourceKind } from '../../api/dataSources';

const props = defineProps<{
  kind: DataSourceKind;
  modelValue: Record<string, any>;
  nameValue: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void;
  (e: 'update:nameValue', v: string): void;
}>();

const cfg = ref<Record<string, any>>({ ...props.modelValue });
const name = ref<string>(props.nameValue || '');

watch(() => props.modelValue, (v) => { cfg.value = { ...v }; }, { deep: true });
watch(() => props.nameValue, (v) => { name.value = v || ''; });

const emitConfig = () => emit('update:modelValue', { ...cfg.value });
const emitName = () => emit('update:nameValue', name.value);

// 各数据库类型的连接字段提示（端口默认值 / 库字段名称与占位）
const dbPortHint = computed(() => ({
  mysql: '3306', pgsql: '5432', oracle: '1521', dm: '5236', gbase: '5258',
} as Record<string, string>)[props.kind] || '');
const dbNameLabel = computed(() =>
  props.kind === 'oracle' ? 'Service Name' : props.kind === 'dm' ? 'Schema' : 'Database');
const dbNameHint = computed(() =>
  props.kind === 'oracle' ? '服务名或 SID，如 ORCLPDB1'
    : props.kind === 'dm' ? '模式名，可空（默认取登录用户）'
    : '');

// headers 走 [{key,value}] 列表方便编辑
const headerList = ref<{ k: string; v: string }[]>(
  Object.entries(cfg.value.headers || {}).map(([k, v]) => ({ k, v: String(v) })),
);
const syncHeaders = () => {
  const out: Record<string, string> = {};
  for (const it of headerList.value) {
    if (it.k.trim()) out[it.k.trim()] = it.v;
  }
  cfg.value.headers = out;
  emitConfig();
};
const addHeader = () => { headerList.value.push({ k: '', v: '' }); };
const removeHeader = (i: number) => { headerList.value.splice(i, 1); syncHeaders(); };

const scheduleEnabled = computed({
  get: () => !!cfg.value.schedule?.enabled,
  set: (v: boolean) => {
    cfg.value.schedule = { ...(cfg.value.schedule || {}), enabled: v };
    emitConfig();
  },
});
const scheduleInterval = computed({
  get: () => cfg.value.schedule?.intervalSec || 300,
  set: (v: number) => {
    cfg.value.schedule = { ...(cfg.value.schedule || {}), intervalSec: Number(v) || 300 };
    emitConfig();
  },
});
</script>

<template>
  <div class="ds-form">
    <label class="row">
      <span>名称</span>
      <input v-model="name" @input="emitName" placeholder="数据源名称" />
    </label>

    <template v-if="kind === 'mysql' || kind === 'pgsql' || kind === 'oracle' || kind === 'dm' || kind === 'gbase'">
      <label class="row"><span>Host</span><input v-model="cfg.host" @input="emitConfig" placeholder="localhost" /></label>
      <label class="row"><span>Port</span><input v-model.number="cfg.port" @input="emitConfig" :placeholder="dbPortHint" /></label>
      <label class="row"><span>{{ dbNameLabel }}</span><input v-model="cfg.database" @input="emitConfig" :placeholder="dbNameHint" /></label>
      <label class="row"><span>Username</span><input v-model="cfg.username" @input="emitConfig" /></label>
      <label class="row"><span>Password</span><input type="password" v-model="cfg.password" @input="emitConfig" /></label>
      <label v-if="kind !== 'oracle' && kind !== 'dm'" class="row"><span>额外参数</span><input v-model="cfg.params" @input="emitConfig" placeholder="如 useSSL=false&serverTimezone=UTC" /></label>
    </template>

    <template v-if="kind === 'https_api'">
      <label class="row"><span>URL</span><input v-model="cfg.url" @input="emitConfig" placeholder="https://api.example.com/..." /></label>
      <label class="row">
        <span>方法</span>
        <select v-model="cfg.method" @change="emitConfig">
          <option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option>
        </select>
      </label>
      <div class="row block">
        <span>Headers</span>
        <div class="kv-list">
          <div v-for="(h, i) in headerList" :key="i" class="kv">
            <input v-model="h.k" @input="syncHeaders" placeholder="Header" />
            <input v-model="h.v" @input="syncHeaders" placeholder="Value" />
            <button type="button" @click="removeHeader(i)">×</button>
          </div>
          <button type="button" class="add" @click="addHeader">+ 添加 Header</button>
        </div>
      </div>
      <label v-if="cfg.method === 'POST' || cfg.method === 'PUT'" class="row block">
        <span>Body</span>
        <textarea v-model="cfg.body" @input="emitConfig" rows="4" placeholder='{"key":"value"}'></textarea>
      </label>
      <label class="row"><span>超时(ms)</span><input v-model.number="cfg.timeoutMs" @input="emitConfig" placeholder="15000" /></label>
      <div class="row schedule">
        <label class="schedule-toggle">
          <input type="checkbox" v-model="scheduleEnabled" />
          <span>定时拉取</span>
        </label>
        <input v-if="scheduleEnabled" type="number" v-model.number="scheduleInterval" min="60" placeholder="秒，≥60" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.ds-form { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input, .row > select, .row > textarea {
  flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12);
  border-radius: 6px; padding: 6px 8px; color: #e8eaed; font-size: 13px;
}
.row.block { flex-direction: column; align-items: stretch; }
.row.block > span { margin-bottom: 4px; }
.kv-list { display: flex; flex-direction: column; gap: 6px; }
.kv { display: flex; gap: 6px; }
.kv input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.kv button, .add { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 10px; color: #c0c4cf; cursor: pointer; }
.schedule { gap: 12px; }
.schedule-toggle { display: flex; align-items: center; gap: 6px; color: #c0c4cf; }
</style>
