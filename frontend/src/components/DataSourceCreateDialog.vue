<script setup lang="ts">
import { ref } from 'vue';
import { createDataSource, testDataSourceInline, uploadFileDataSource } from '../api/dataSources';
import type { DataSourceKind } from '../api/dataSources';
import { ApiError } from '../api/http';
import { toast } from '../composables/useToast';
import DataSourceConfigForm from './datasource/DataSourceConfigForm.vue';

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'created', id: string): void;
}>();

const step = ref<'pick' | 'form'>('pick');
const kind = ref<DataSourceKind | null>(null);
const name = ref('');
const cfg = ref<Record<string, any>>({});
const fileToUpload = ref<File | null>(null);
const submitting = ref(false);
const testing = ref(false);
const testMsg = ref<string>('');

const TYPES: { kind: DataSourceKind; icon: string; label: string; desc: string }[] = [
  { kind: 'mysql',       icon: '🗄', label: 'MySQL',       desc: '连接 MySQL 数据库，查表写 SQL' },
  { kind: 'pgsql',       icon: '🐘', label: 'PostgreSQL',  desc: '连接 PgSQL 数据库，查表写 SQL' },
  { kind: 'file_stored', icon: '📄', label: '文件',         desc: '上传 PDF / TXT / MD' },
  { kind: 'https_api',   icon: '🌐', label: 'HTTPS 接口',  desc: 'REST API，可定时拉取' },
];

const pickType = (k: DataSourceKind) => {
  kind.value = k;
  step.value = 'form';
  if (k === 'mysql') cfg.value = { host: 'localhost', port: 3306, database: '', username: '', password: '', params: '' };
  if (k === 'pgsql') cfg.value = { host: 'localhost', port: 5432, database: '', username: '', password: '', params: '' };
  if (k === 'https_api') cfg.value = { url: '', method: 'GET', headers: {}, body: '', timeoutMs: 15000, schedule: { enabled: false, intervalSec: 300 } };
  if (k === 'file_stored') cfg.value = {};
};

const onFilePick = (ev: Event) => {
  const t = ev.target as HTMLInputElement;
  const f = t.files?.[0];
  if (f) {
    fileToUpload.value = f;
    if (!name.value) name.value = f.name;
  }
};

const runTest = async () => {
  if (!kind.value || kind.value === 'file_stored') return;
  testing.value = true;
  testMsg.value = '';
  try {
    const r = await testDataSourceInline({ kind: kind.value, config: cfg.value });
    testMsg.value = r.success
      ? `连接成功 (${r.latencyMs ?? '-'}ms)`
      : `连接失败：${r.message || '未知错误'}`;
  } catch (e) {
    testMsg.value = `异常：${(e as Error).message}`;
  } finally {
    testing.value = false;
  }
};

const submit = async () => {
  if (!kind.value) return;
  if (!name.value.trim()) { toast('请填写名称'); return; }
  submitting.value = true;
  try {
    let created;
    if (kind.value === 'file_stored') {
      if (!fileToUpload.value) { toast('请选择文件'); submitting.value = false; return; }
      created = await uploadFileDataSource(fileToUpload.value, name.value.trim());
    } else {
      created = await createDataSource({ name: name.value.trim(), kind: kind.value, config: cfg.value });
    }
    toast('已创建');
    emit('created', created.id);
    emit('close');
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : (e as Error).message;
    toast(`创建失败：${msg}`);
  } finally {
    submitting.value = false;
  }
};
</script>

<template>
  <div class="dlg-mask" @click.self="emit('close')">
    <div class="dlg">
      <div class="dlg-head">
        <h3>添加数据源</h3>
        <button @click="emit('close')">×</button>
      </div>
      <div class="dlg-body">
        <div v-if="step === 'pick'" class="picker">
          <button v-for="t in TYPES" :key="t.kind" class="type-card" @click="pickType(t.kind)">
            <span class="ic">{{ t.icon }}</span>
            <strong>{{ t.label }}</strong>
            <small>{{ t.desc }}</small>
          </button>
        </div>
        <div v-else class="form">
          <DataSourceConfigForm
            v-if="kind && kind !== 'file_stored'"
            :kind="kind"
            v-model="cfg"
            v-model:name-value="name"
          />
          <template v-else>
            <label class="row">
              <span>名称</span>
              <input v-model="name" placeholder="数据源名称" />
            </label>
            <label class="row block">
              <span>文件 (PDF / TXT / MD)</span>
              <input type="file" accept=".pdf,.txt,.md" @change="onFilePick" />
              <small v-if="fileToUpload">{{ fileToUpload.name }} ({{ (fileToUpload.size / 1024).toFixed(1) }} KB)</small>
            </label>
          </template>
          <div v-if="testMsg" class="test-msg">{{ testMsg }}</div>
        </div>
      </div>
      <div class="dlg-foot">
        <button v-if="step === 'form'" class="ghost" @click="step = 'pick'">‹ 返回</button>
        <span class="spacer" />
        <button
          v-if="step === 'form' && kind && kind !== 'file_stored'"
          class="ghost" :disabled="testing" @click="runTest"
        >{{ testing ? '测试中…' : '测试连接' }}</button>
        <button class="primary" :disabled="step === 'pick' || submitting" @click="submit">
          {{ submitting ? '提交中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dlg-mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.dlg { width: 560px; max-height: 80vh; background: #1d1f24; color: #e8eaed; border-radius: 10px; display: flex; flex-direction: column; border: 1px solid rgba(255,255,255,.08); }
.dlg-head, .dlg-foot { display: flex; align-items: center; padding: 12px 16px; }
.dlg-head { border-bottom: 1px solid rgba(255,255,255,.06); }
.dlg-head h3 { margin: 0; font-size: 15px; flex: 1; }
.dlg-head button { background: none; border: none; color: #aaa; font-size: 18px; cursor: pointer; }
.dlg-body { padding: 16px; overflow-y: auto; flex: 1; }
.dlg-foot { border-top: 1px solid rgba(255,255,255,.06); gap: 8px; }
.spacer { flex: 1; }
.picker { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.type-card { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.08); border-radius: 8px; padding: 12px; cursor: pointer; color: #e8eaed; }
.type-card:hover { background: rgba(255,255,255,.08); }
.type-card .ic { font-size: 20px; }
.type-card strong { font-size: 14px; }
.type-card small { font-size: 12px; color: #aaa; }
.form { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.row.block { flex-direction: column; align-items: stretch; gap: 6px; }
.test-msg { font-size: 13px; color: #aaa; padding: 8px; background: rgba(255,255,255,.03); border-radius: 6px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.primary:disabled { opacity: .5; cursor: not-allowed; }
.ghost { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 12px; color: #e8eaed; cursor: pointer; }
</style>
