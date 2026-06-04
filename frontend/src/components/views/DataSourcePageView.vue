<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import {
  listAllDataSources, createDataSource, uploadFileDataSource,
  deleteDataSource, testDataSourceInline,
} from '../../api/dataSources';
import type { DataSource, DataSourceKind } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { useWorkspaces } from '../../composables/useWorkspaces';
import DataSourceConfigForm from '../datasource/DataSourceConfigForm.vue';

const tree = useSidebarTree();
const ws = useWorkspaces();

const emit = defineEmits<{
  (e: 'open', id: string): void;
}>();

// ── 全量列表(跨工作空间)────────────────────────────────
const items = ref<DataSource[]>([]);
const loading = ref(false);
// 按工作空间筛选(null = 全部)
const filterWs = ref<string | null>(null);

const load = async () => {
  loading.value = true;
  try {
    // 顺带确保工作空间名映射可用
    if (!ws.workspaces.value.length) await ws.reload();
    items.value = await listAllDataSources();
  } catch (e) {
    toast.error(`加载失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally {
    loading.value = false;
  }
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
const statusLabel: Record<string, string> = {
  connected: '已连接', error: '异常', idle: '未测试',
};
const statusColor: Record<string, string> = {
  connected: '#22dd88', error: 'tomato', idle: '#aaa',
};
const canOpen = (d: DataSource) =>
  ['mysql', 'pgsql', 'file_stored', 'https_api'].includes(d.kind);

const wsName = (id?: string) => {
  if (!id) return '—';
  return ws.workspaces.value.find(w => w.id === id)?.name || '(已删除)';
};

// 用到的工作空间(用于筛选条)
const usedWorkspaces = computed(() => {
  const ids = new Set(items.value.map(d => d.workspaceId).filter(Boolean) as string[]);
  return ws.workspaces.value.filter(w => ids.has(w.id));
});

const visibleItems = computed(() =>
  filterWs.value ? items.value.filter(d => d.workspaceId === filterWs.value) : items.value);

// ── 只读预览(不切换工作空间)─────────────────────────────
// 跨工作空间的详情/查表/SQL 接口都按工作空间隔离,直接调会 403。
// 这里只用列表里已加载的数据(含脱敏 config)做只读预览,不发跨工作空间请求。
const previewItem = ref<DataSource | null>(null);
const openPreview = (d: DataSource) => { previewItem.value = d; };
const closePreview = () => { previewItem.value = null; };

// 预览项是否属于当前工作空间(只有同工作空间才允许打开完整详情/编辑)
const previewInCurrentWs = computed(() =>
  !!previewItem.value && previewItem.value.workspaceId === ws.currentId.value);

const SKIP_KEYS = new Set(['storagePath', 'extractedTextPath']);
const previewRows = computed<{ label: string; value: string }[]>(() => {
  const d = previewItem.value;
  if (!d) return [];
  const rows: { label: string; value: string }[] = [
    { label: '类型', value: kindLabel[d.kind] ?? d.kind },
    { label: '所属工作空间', value: wsName(d.workspaceId) },
    { label: '状态', value: d.status ? (statusLabel[d.status] ?? d.status) : '未测试' },
  ];
  if (d.createdAt) rows.push({ label: '创建时间', value: new Date(d.createdAt).toLocaleString('zh-CN') });
  // 文件类:名称/大小/页数/字数(抽取阶段写入的旁挂字段)
  if (d.originalName) rows.push({ label: '文件名', value: String(d.originalName) });
  if (typeof d.size === 'number' && d.size > 0) rows.push({ label: '大小', value: `${(d.size / 1024).toFixed(1)} KB` });
  if (d.pages != null) rows.push({ label: '页数', value: String(d.pages) });
  if (d.chars != null) rows.push({ label: '字数', value: String(d.chars) });
  // 连接/接口类:展开 config 里的标量字段(密码等已被后端脱敏)
  const cfg = d.config && typeof d.config === 'object' ? d.config as Record<string, unknown> : null;
  if (cfg) {
    for (const [k, v] of Object.entries(cfg)) {
      if (SKIP_KEYS.has(k)) continue;
      if (v == null) continue;
      if (typeof v === 'object') continue; // headers 等嵌套对象略过
      rows.push({ label: k, value: String(v) });
    }
  }
  return rows;
});

const openFullDetail = () => {
  const d = previewItem.value;
  if (!d) return;
  closePreview();
  emit('open', d.id);
};

// ── 删除(支持删除其它工作空间下的数据源)────────────────
const doDelete = async (d: DataSource) => {
  const ok = await uiConfirm({
    title: '删除数据源',
    message: `确认删除「${d.name}」吗？此操作不可恢复。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await deleteDataSource(d.id, d.workspaceId);
    items.value = items.value.filter(x => x.id !== d.id);
    // 同步刷新侧栏该工作空间的数据源缓存
    if (d.workspaceId && tree.getDataSources(d.workspaceId).some(x => x.id === d.id)) {
      tree.loadDataSources(d.workspaceId, true);
    }
    toast.success('已删除');
  } catch (e) {
    toast.error(`删除失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  }
};

// ── 添加表单(新数据源始终归入当前工作空间)──────────────
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
  { kind: 'file_stored', icon: '📄', label: '文件',         desc: '上传 PDF / Word / TXT / MD' },
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
    if (r.success) {
      testMsg.value = `连接成功 (${r.latencyMs ?? '-'}ms)`;
      toast.success(testMsg.value);
    } else {
      testMsg.value = `连接失败：${r.message || '未知错误'}`;
      toast.warn(testMsg.value);
    }
  } catch (e) {
    testMsg.value = `异常：${(e as Error).message}`;
    toast.error(testMsg.value);
  } finally { testing.value = false; }
};

const submit = async () => {
  if (!kind.value) return;
  if (!name.value.trim()) { toast.warn('请填写名称'); return; }
  submitting.value = true;
  try {
    let created: DataSource;
    if (kind.value === 'file_stored') {
      if (!fileToUpload.value) { toast.warn('请选择文件'); submitting.value = false; return; }
      created = await uploadFileDataSource(fileToUpload.value, name.value.trim());
    } else {
      created = await createDataSource({ name: name.value.trim(), kind: kind.value, config: cfg.value });
    }
    items.value.unshift(created);
    const curWs = ws.currentId.value;
    if (curWs) tree.upsertDataSource(curWs, created);
    showForm.value = false;
    toast.success(`数据源「${created.name}」已创建`);
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : (e as Error).message;
    toast.error(`创建失败：${msg}`);
  } finally { submitting.value = false; }
};
</script>

<template>
  <div class="ds-page">
    <div class="ds-header">
      <h2>数据源</h2>
      <span class="ds-total">共 {{ items.length }} 个 · 跨全部工作空间</span>
      <button class="btn-primary" @click="openAdd">＋ 添加数据源</button>
    </div>

    <!-- 工作空间筛选条 -->
    <div v-if="usedWorkspaces.length > 1" class="ws-filter">
      <button :class="['chip', { on: filterWs === null }]" @click="filterWs = null">全部</button>
      <button
        v-for="w in usedWorkspaces" :key="w.id"
        :class="['chip', { on: filterWs === w.id }]"
        @click="filterWs = w.id"
      >{{ w.name }}</button>
    </div>

    <!-- 添加表单（内嵌，非弹层） -->
    <div v-if="showForm" class="add-panel">
      <div class="add-panel-head">
        <span>添加数据源 · 归入当前工作空间「{{ wsName(ws.currentId.value) }}」</span>
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
            <span>文件 (PDF / Word / TXT / MD / 音频)</span>
            <input type="file" accept=".pdf,.docx,.txt,.md,.mp3,.wav,.m4a,.flac,.aac,.ogg,.opus,.wma,.amr,audio/*" @change="onFilePick" />
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

    <!-- 扁平列表 -->
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!visibleItems.length && !showForm" class="empty">
      暂无数据源，点击「添加数据源」开始
    </div>
    <div v-else class="ds-list">
      <div
        v-for="d in visibleItems"
        :key="d.id"
        class="ds-row clickable"
        @click="openPreview(d)"
      >
        <span class="row-ic">{{ kindIcon[d.kind] ?? '📦' }}</span>
        <div class="ds-main">
          <span class="ds-name">{{ d.name }}</span>
          <span class="ds-kind">{{ kindLabel[d.kind] ?? d.kind }}</span>
        </div>
        <span class="ws-badge" :title="`所属工作空间：${wsName(d.workspaceId)}`">
          <span class="ws-badge-ic">◆</span>{{ wsName(d.workspaceId) }}
        </span>
        <span v-if="d.status" class="ds-status" :style="{ color: statusColor[d.status] ?? '#aaa' }">
          ● {{ statusLabel[d.status] ?? d.status }}
        </span>
        <span class="row-actions">
          <button title="预览" @click.stop="openPreview(d)">预览</button>
          <button title="删除" class="danger" @click.stop="doDelete(d)">删除</button>
        </span>
      </div>
    </div>

    <!-- 只读预览(不切换工作空间) -->
    <div v-if="previewItem" class="preview-overlay" @click.self="closePreview">
      <div class="preview-panel">
        <div class="preview-head">
          <span class="row-ic">{{ kindIcon[previewItem.kind] ?? '📦' }}</span>
          <span class="preview-title">{{ previewItem.name }}</span>
          <span class="ws-badge"><span class="ws-badge-ic">◆</span>{{ wsName(previewItem.workspaceId) }}</span>
          <button class="close-btn" @click="closePreview">×</button>
        </div>
        <div class="preview-body">
          <div v-for="r in previewRows" :key="r.label" class="preview-row">
            <span class="preview-k">{{ r.label }}</span>
            <span class="preview-v">{{ r.value }}</span>
          </div>
        </div>
        <div class="preview-foot">
          <span v-if="!previewInCurrentWs" class="preview-hint">
            只读预览 · 该数据源属于其它工作空间
          </span>
          <span style="flex:1" />
          <button class="btn-ghost" @click="closePreview">关闭</button>
          <button
            v-if="previewInCurrentWs && canOpen(previewItem)"
            class="btn-primary"
            @click="openFullDetail"
          >打开完整详情</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ds-page { display: flex; flex-direction: column; height: 100%; padding: 24px 32px; color: #e8eaed; overflow-y: auto; }
.ds-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.ds-header h2 { margin: 0; font-size: 18px; font-weight: 600; }
.ds-total { font-size: 12px; color: #8a909c; flex: 1; }

.ws-filter { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
.chip {
  background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.12);
  border-radius: 100px; padding: 4px 12px; color: #c0c4cf; cursor: pointer; font-size: 12px;
  transition: background .12s, color .12s, border-color .12s;
}
.chip:hover { background: rgba(255,255,255,.09); }
.chip.on { background: rgba(66,184,131,.18); border-color: rgba(66,184,131,.5); color: #6dd4a7; }

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

/* 扁平列表 */
.ds-list { display: flex; flex-direction: column; gap: 4px; }
.ds-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; border-radius: 9px; min-height: 46px;
  border: 1px solid transparent;
  transition: background .12s, border-color .12s;
}
.ds-row:hover { background: rgba(255,255,255,.05); border-color: rgba(255,255,255,.08); }
.ds-row.clickable { cursor: pointer; }
.ds-row:hover .row-actions { opacity: 1; }
.row-ic { font-size: 20px; flex: none; width: 24px; text-align: center; }
.ds-main { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.ds-name { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ds-kind { font-size: 12px; color: #888; }

.ws-badge {
  display: inline-flex; align-items: center; gap: 5px; flex: none;
  font-size: 12px; color: #9fb6ff;
  background: rgba(93,158,255,.12); border: 1px solid rgba(93,158,255,.28);
  border-radius: 100px; padding: 3px 11px; max-width: 180px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ws-badge-ic { font-size: 9px; opacity: .8; }
.ds-status { font-size: 12px; flex: none; min-width: 64px; }

.row-actions { display: flex; gap: 4px; flex: none; opacity: 0; transition: opacity .12s; }
.row-actions button {
  background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12);
  color: #c0c4cf; font-size: 12px; cursor: pointer; padding: 4px 12px; border-radius: 6px; line-height: 1.4;
}
.row-actions button:hover { color: #fff; background: rgba(255,255,255,.12); }
.row-actions button.danger:hover { color: #fff; background: rgba(255,99,71,.22); border-color: rgba(255,99,71,.5); }

.empty { color: #888; padding: 40px; text-align: center; font-size: 14px; }

.btn-primary { background: #4a8df0; border: none; color: #fff; padding: 7px 16px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn-primary:disabled { opacity: .5; cursor: not-allowed; }
.btn-ghost { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 12px; color: #e8eaed; cursor: pointer; font-size: 13px; }
.btn-ghost:disabled { opacity: .5; }

/* 只读预览弹层 */
.preview-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 60; }
.preview-panel { background: #1c1f26; border: 1px solid rgba(255,255,255,.14); border-radius: 12px; width: 460px; max-width: 92vw; max-height: 80vh; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 24px 60px rgba(0,0,0,.5); }
.preview-head { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border-bottom: 1px solid rgba(255,255,255,.08); }
.preview-title { font-size: 15px; font-weight: 600; flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.preview-head .close-btn { margin-left: 4px; }
.preview-body { padding: 8px 16px 12px; overflow-y: auto; display: flex; flex-direction: column; }
.preview-row { display: flex; gap: 12px; padding: 8px 0; border-bottom: 1px solid rgba(255,255,255,.05); font-size: 13px; }
.preview-row:last-child { border-bottom: none; }
.preview-k { flex: none; width: 120px; color: #8a909c; word-break: break-all; }
.preview-v { flex: 1; min-width: 0; color: #e8eaed; word-break: break-all; }
.preview-foot { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-top: 1px solid rgba(255,255,255,.08); }
.preview-hint { font-size: 12px; color: #8a909c; }
</style>
