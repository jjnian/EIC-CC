<script setup lang="ts">
import { ref, onMounted } from 'vue';
import {
  listDataSources, createDataSource, uploadFileDataSource,
  deleteDataSource, testDataSourceInline,
} from '../../api/dataSources';
import type { DataSource, DataSourceKind } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { useWorkspaces } from '../../composables/useWorkspaces';
import DataSourceConfigForm from '../datasource/DataSourceConfigForm.vue';

const tree = useSidebarTree();
const ws = useWorkspaces();

const emit = defineEmits<{
  (e: 'open', id: string): void;
}>();

// ── 列表 ──────────────────────────────────────────────
const items = ref<DataSource[]>([]);
const loading = ref(false);

const load = async () => {
  loading.value = true;
  try { items.value = await listDataSources(); }
  catch (e) { toast(`加载失败：${(e as Error).message}`); }
  finally { loading.value = false; }
};
onMounted(load);

const kindLabel: Record<string, string> = {
  mysql: 'MySQL', pgsql: 'PostgreSQL',
  file_stored: '文件', https_api: 'HTTPS 接口',
  file: '文件(旧)', url: 'URL(旧)',
};
const kindIcon: Record<string, string> = {
  mysql: '🗄', pgsql: '🐘', file_stored: '📄', https_api: '🌐',
  file: '📄', url: '🔗',
};
const statusColor: Record<string, string> = {
  connected: '#22dd88', error: 'tomato', idle: '#aaa',
};
const canOpen = (d: DataSource) =>
  ['mysql', 'pgsql', 'file_stored', 'https_api'].includes(d.kind);

const doDelete = async (d: DataSource) => {
  if (!confirm(`确认删除「${d.name}」？`)) return;
  try {
    await deleteDataSource(d.id);
    items.value = items.value.filter(x => x.id !== d.id);
    // 同步从侧栏缓存里移除,避免刷新前侧栏仍显示已删的数据源。
    const wsId = ws.currentId.value;
    if (wsId && tree.getDataSources(wsId).some(x => x.id === d.id)) {
      // 用一次强制重载最简单稳;也可手写过滤,这里选稳。
      tree.loadDataSources(wsId, true);
    }
    toast('已删除');
  } catch (e) { toast(`删除失败：${(e as Error).message}`); }
};

// ── 添加表单 ─────────────────────────────────────────
const showForm = ref(false);
const step = ref<'pick' | 'form'>('pick');
const kind = ref<DataSourceKind | null>(null);
const name = ref('');
const cfg = ref<Record<string, any>>({});
const fileToUpload = ref<File | null>(null);
const submitting = ref(false);
const testing = ref(false);
const testMsg = ref('');

const TYPES: { kind: DataSourceKind; icon: string; label: string; desc: string }[] = [
  { kind: 'mysql',       icon: '🗄', label: 'MySQL',       desc: '连接 MySQL 数据库，查表写 SQL' },
  { kind: 'pgsql',       icon: '🐘', label: 'PostgreSQL',  desc: '连接 PgSQL 数据库，查表写 SQL' },
  { kind: 'file_stored', icon: '📄', label: '文件',         desc: '上传 PDF / TXT / MD' },
  { kind: 'https_api',   icon: '🌐', label: 'HTTPS 接口',  desc: 'REST API，可定时拉取' },
];

const openAdd = () => {
  step.value = 'pick';
  kind.value = null;
  name.value = '';
  cfg.value = {};
  fileToUpload.value = null;
  testMsg.value = '';
  submitting.value = false;
  showForm.value = true;
};

const pickType = (k: DataSourceKind) => {
  kind.value = k;
  step.value = 'form';
  if (k === 'mysql')     cfg.value = { host: 'localhost', port: 3306, database: '', username: '', password: '', params: '' };
  if (k === 'pgsql')     cfg.value = { host: 'localhost', port: 5432, database: '', username: '', password: '', params: '' };
  if (k === 'https_api') cfg.value = { url: '', method: 'GET', headers: {}, body: '', timeoutMs: 15000, schedule: { enabled: false, intervalSec: 300 } };
  if (k === 'file_stored') cfg.value = {};
};

const onFilePick = (ev: Event) => {
  const f = (ev.target as HTMLInputElement).files?.[0];
  if (f) { fileToUpload.value = f; if (!name.value) name.value = f.name; }
};

const runTest = async () => {
  if (!kind.value || kind.value === 'file_stored') return;
  testing.value = true; testMsg.value = '';
  try {
    const r = await testDataSourceInline({ kind: kind.value, config: cfg.value });
    testMsg.value = r.success ? `连接成功 (${r.latencyMs ?? '-'}ms)` : `连接失败：${r.message || '未知错误'}`;
  } catch (e) { testMsg.value = `异常：${(e as Error).message}`; }
  finally { testing.value = false; }
};

const submit = async () => {
  if (!kind.value) return;
  if (!name.value.trim()) { toast('请填写名称'); return; }
  submitting.value = true;
  try {
    let created: DataSource;
    if (kind.value === 'file_stored') {
      if (!fileToUpload.value) { toast('请选择文件'); submitting.value = false; return; }
      created = await uploadFileDataSource(fileToUpload.value, name.value.trim());
    } else {
      created = await createDataSource({ name: name.value.trim(), kind: kind.value, config: cfg.value });
    }
    toast('已创建');
    items.value.unshift(created);
    // 让侧栏立刻看到新数据源,无需等下次刷新或工作空间切换。
    const wsId = ws.currentId.value;
    if (wsId) tree.upsertDataSource(wsId, created);
    showForm.value = false;
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : (e as Error).message;
    toast(`创建失败：${msg}`);
  } finally { submitting.value = false; }
};
</script>

<template>
  <div class="ds-page">
    <div class="ds-header">
      <h2>数据源</h2>
      <button class="btn-primary" @click="openAdd">＋ 添加数据源</button>
    </div>

    <!-- 添加表单（内嵌，非弹层） -->
    <div v-if="showForm" class="add-panel">
      <div class="add-panel-head">
        <span>添加数据源</span>
        <button class="close-btn" @click="showForm = false">×</button>
      </div>

      <div v-if="step === 'pick'" class="picker">
        <button v-for="t in TYPES" :key="t.kind" class="type-card" @click="pickType(t.kind)">
          <span class="ic">{{ t.icon }}</span>
          <strong>{{ t.label }}</strong>
          <small>{{ t.desc }}</small>
        </button>
      </div>

      <div v-else class="form-area">
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
        <div class="form-actions">
          <button type="button" class="btn-ghost" @click="step = 'pick'">‹ 返回</button>
          <span style="flex:1" />
          <button
            v-if="kind && kind !== 'file_stored'"
            type="button"
            class="btn-ghost" :disabled="testing" @click="runTest"
          >{{ testing ? '测试中…' : '测试连接' }}</button>
          <button type="button" class="btn-primary" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 数据源列表 -->
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!items.length && !showForm" class="empty">
      暂无数据源，点击「添加数据源」开始
    </div>
    <div v-else class="ds-list">
      <div
        v-for="d in items" :key="d.id"
        :class="['ds-item', { clickable: canOpen(d) }]"
        @click="canOpen(d) && emit('open', d.id)"
      >
        <span class="ds-icon">{{ kindIcon[d.kind] ?? '📦' }}</span>
        <div class="ds-info">
          <span class="ds-name">{{ d.name }}</span>
          <span class="ds-kind">{{ kindLabel[d.kind] ?? d.kind }}</span>
        </div>
        <span v-if="d.status" class="ds-status" :style="{ color: statusColor[d.status] ?? '#aaa' }">
          {{ d.status }}
        </span>
        <button class="ds-del" @click.stop="doDelete(d)" title="删除">×</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ds-page { display: flex; flex-direction: column; height: 100%; padding: 24px 32px; color: #e8eaed; overflow-y: auto; }
.ds-header { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.ds-header h2 { margin: 0; font-size: 18px; font-weight: 600; }

.add-panel { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.1); border-radius: 10px; margin-bottom: 20px; }
.add-panel-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid rgba(255,255,255,.06); font-size: 14px; font-weight: 500; }
.close-btn { background: none; border: none; color: #aaa; font-size: 18px; cursor: pointer; line-height: 1; }

.picker { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; padding: 16px; }
.type-card { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.08); border-radius: 8px; padding: 14px; cursor: pointer; color: #e8eaed; text-align: left; }
.type-card:hover { background: rgba(255,255,255,.09); border-color: rgba(255,255,255,.16); }
.type-card .ic { font-size: 22px; }
.type-card strong { font-size: 14px; }
.type-card small { font-size: 12px; color: #999; }

.form-area { padding: 16px; display: flex; flex-direction: column; gap: 12px; max-width: 560px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.row.block { flex-direction: column; align-items: stretch; gap: 6px; }
.test-msg { font-size: 13px; color: #aaa; padding: 8px; background: rgba(255,255,255,.03); border-radius: 6px; }
.form-actions { display: flex; gap: 8px; align-items: center; padding-top: 4px; }

.ds-list { display: flex; flex-direction: column; gap: 8px; }
.ds-item { display: flex; align-items: center; gap: 12px; padding: 12px 16px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.07); border-radius: 8px; }
.ds-item.clickable { cursor: pointer; }
.ds-item.clickable:hover { background: rgba(255,255,255,.08); border-color: rgba(255,255,255,.14); }
.ds-icon { font-size: 20px; }
.ds-info { display: flex; flex-direction: column; gap: 2px; flex: 1; }
.ds-name { font-size: 14px; font-weight: 500; }
.ds-kind { font-size: 12px; color: #888; }
.ds-status { font-size: 12px; }
.ds-del { background: none; border: none; color: #666; font-size: 16px; cursor: pointer; padding: 4px 6px; border-radius: 4px; }
.ds-del:hover { color: tomato; background: rgba(255,99,71,.1); }

.empty { color: #888; padding: 40px; text-align: center; font-size: 14px; }

.btn-primary { background: #4a8df0; border: none; color: #fff; padding: 7px 16px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn-primary:disabled { opacity: .5; cursor: not-allowed; }
.btn-ghost { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 12px; color: #e8eaed; cursor: pointer; font-size: 13px; }
.btn-ghost:disabled { opacity: .5; }
</style>
