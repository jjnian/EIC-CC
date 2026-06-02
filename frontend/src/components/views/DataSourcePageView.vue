<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import {
  listDataSources, createDataSource, uploadFileDataSource,
  deleteDataSource, testDataSourceInline,
} from '../../api/dataSources';
import type { DataSource, DataSourceKind } from '../../api/dataSources';
import {
  listFolders, createFolder, renameFolder, moveFolder, deleteFolder,
  moveDataSourceToFolder,
} from '../../api/folders';
import type { DataSourceFolder } from '../../api/folders';
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

// ── 列表 + 文件夹 ─────────────────────────────────────
const items = ref<DataSource[]>([]);
const folders = ref<DataSourceFolder[]>([]);
const expanded = ref<Set<string>>(new Set());
const expandInited = ref(false);
const loading = ref(false);

const load = async () => {
  loading.value = true;
  try {
    const [ds, fds] = await Promise.all([listDataSources(), listFolders()]);
    items.value = ds;
    folders.value = fds;
    // 仅首次默认展开全部；之后的重载保留用户的展开/折叠状态
    if (!expandInited.value) {
      expanded.value = new Set(fds.map(f => f.id));
      expandInited.value = true;
    }
  } catch (e) {
    toast(`加载失败：${(e as Error).message}`);
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
const statusColor: Record<string, string> = {
  connected: '#22dd88', error: 'tomato', idle: '#aaa',
};
const canOpen = (d: DataSource) =>
  ['mysql', 'pgsql', 'file_stored', 'https_api'].includes(d.kind);

// ── 树形扁平化（任意层级用 depth 缩进渲染）──────────────
interface Row { kind: 'folder' | 'ds'; depth: number; folder?: DataSourceFolder; ds?: DataSource; }

const flatRows = computed<Row[]>(() => {
  const byParent = new Map<string, DataSourceFolder[]>();
  for (const f of folders.value) {
    const k = f.parentId || '__root__';
    if (!byParent.has(k)) byParent.set(k, []);
    byParent.get(k)!.push(f);
  }
  const dsByFolder = new Map<string, DataSource[]>();
  for (const d of items.value) {
    const k = d.folderId || '__root__';
    if (!dsByFolder.has(k)) dsByFolder.set(k, []);
    dsByFolder.get(k)!.push(d);
  }
  const rows: Row[] = [];
  const walk = (parentKey: string, depth: number) => {
    for (const f of (byParent.get(parentKey) ?? [])) {
      rows.push({ kind: 'folder', depth, folder: f });
      if (expanded.value.has(f.id)) {
        walk(f.id, depth + 1);
        for (const d of (dsByFolder.get(f.id) ?? [])) rows.push({ kind: 'ds', depth: depth + 1, ds: d });
      }
    }
  };
  walk('__root__', 0);
  for (const d of (dsByFolder.get('__root__') ?? [])) rows.push({ kind: 'ds', depth: 0, ds: d });
  return rows;
});

const dsCountIn = (folderId: string) => items.value.filter(d => d.folderId === folderId).length;
const isExpanded = (id: string) => expanded.value.has(id);
const toggle = (id: string) => {
  const next = new Set(expanded.value);
  next.has(id) ? next.delete(id) : next.add(id);
  expanded.value = next;
};

// ── 文件夹操作 ─────────────────────────────────────────
const newFolder = async (parentId: string | null) => {
  const name = prompt('文件夹名称', '新文件夹');
  if (name == null || !name.trim()) return;
  try {
    await createFolder({ name: name.trim(), parentId });
    if (parentId) expanded.value = new Set(expanded.value).add(parentId);
    await load();
    toast('已创建文件夹');
  } catch (e) { toast(`创建失败：${(e as Error).message}`); }
};

const renameFolderAction = async (f: DataSourceFolder) => {
  const name = prompt('重命名文件夹', f.name);
  if (name == null || !name.trim() || name.trim() === f.name) return;
  try { await renameFolder(f.id, name.trim()); await load(); }
  catch (e) { toast(`重命名失败：${(e as Error).message}`); }
};

const deleteFolderAction = async (f: DataSourceFolder) => {
  if (!confirm(`删除文件夹「${f.name}」？其中的子文件夹与数据源会上移到上一级（不会被删除）。`)) return;
  try { await deleteFolder(f.id); await load(); toast('已删除文件夹'); }
  catch (e) { toast(`删除失败：${(e as Error).message}`); }
};

const doDelete = async (d: DataSource) => {
  if (!confirm(`确认删除「${d.name}」？`)) return;
  try {
    await deleteDataSource(d.id);
    items.value = items.value.filter(x => x.id !== d.id);
    const wsId = ws.currentId.value;
    if (wsId && tree.getDataSources(wsId).some(x => x.id === d.id)) {
      tree.loadDataSources(wsId, true);
    }
    toast('已删除');
  } catch (e) { toast(`删除失败：${(e as Error).message}`); }
};

// ── 移动（数据源 或 文件夹）────────────────────────────
const moveTarget = ref<{ kind: 'ds' | 'folder'; id: string; name: string } | null>(null);

/** 把某文件夹及其全部子孙的 id 收集起来（移动文件夹时要从候选目标里排除，避免成环）。 */
const subtreeIds = (rootId: string): Set<string> => {
  const out = new Set<string>([rootId]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const f of folders.value) {
      if (f.parentId && out.has(f.parentId) && !out.has(f.id)) { out.add(f.id); changed = true; }
    }
  }
  return out;
};

const moveTargets = computed<{ id: string; name: string; depth: number }[]>(() => {
  const exclude = moveTarget.value?.kind === 'folder' ? subtreeIds(moveTarget.value.id) : new Set<string>();
  const byParent = new Map<string, DataSourceFolder[]>();
  for (const f of folders.value) {
    const k = f.parentId || '__root__';
    if (!byParent.has(k)) byParent.set(k, []);
    byParent.get(k)!.push(f);
  }
  const out: { id: string; name: string; depth: number }[] = [];
  const walk = (parentKey: string, depth: number) => {
    for (const f of (byParent.get(parentKey) ?? [])) {
      if (!exclude.has(f.id)) out.push({ id: f.id, name: f.name, depth });
      walk(f.id, depth + 1);
    }
  };
  walk('__root__', 0);
  return out;
});

const openMove = (kind: 'ds' | 'folder', id: string, name: string) => {
  moveTarget.value = { kind, id, name };
};

const doMove = async (targetFolderId: string | null) => {
  const mt = moveTarget.value;
  if (!mt) return;
  try {
    if (mt.kind === 'ds') await moveDataSourceToFolder(mt.id, targetFolderId);
    else await moveFolder(mt.id, targetFolderId);
    moveTarget.value = null;
    if (targetFolderId) expanded.value = new Set(expanded.value).add(targetFolderId);
    await load();
  } catch (e) { toast(`移动失败：${(e as Error).message}`); }
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
      <button class="btn-ghost" @click="newFolder(null)">＋ 新建文件夹</button>
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
            <span>文件 (PDF / Word / TXT / MD)</span>
            <input type="file" accept=".pdf,.docx,.txt,.md" @change="onFilePick" />
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

    <!-- 文件夹树 + 数据源 -->
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!flatRows.length && !showForm" class="empty">
      暂无数据源，点击「添加数据源」开始；可先「新建文件夹」归类
    </div>
    <div v-else class="ds-tree">
      <div
        v-for="row in flatRows"
        :key="row.kind + ':' + (row.folder?.id || row.ds?.id)"
        class="tree-row"
        :style="{ paddingLeft: (row.depth * 26 + 8) + 'px' }"
      >
        <!-- 文件夹行 -->
        <template v-if="row.kind === 'folder'">
          <span class="twisty" @click="toggle(row.folder!.id)">{{ isExpanded(row.folder!.id) ? '▾' : '▸' }}</span>
          <span class="row-ic">📁</span>
          <span class="folder-name" @click="toggle(row.folder!.id)">{{ row.folder!.name }}</span>
          <span class="folder-count">{{ dsCountIn(row.folder!.id) }}</span>
          <span class="row-actions">
            <button title="新建子文件夹" @click.stop="newFolder(row.folder!.id)">＋</button>
            <button title="重命名" @click.stop="renameFolderAction(row.folder!)">✎</button>
            <button title="移动" @click.stop="openMove('folder', row.folder!.id, row.folder!.name)">⤷</button>
            <button title="删除" class="danger" @click.stop="deleteFolderAction(row.folder!)">×</button>
          </span>
        </template>
        <!-- 数据源行 -->
        <template v-else>
          <span class="twisty-spacer"></span>
          <span class="row-ic">{{ kindIcon[row.ds!.kind] ?? '📦' }}</span>
          <div
            class="ds-info"
            :class="{ clickable: canOpen(row.ds!) }"
            @click="canOpen(row.ds!) && emit('open', row.ds!.id)"
          >
            <span class="ds-name">{{ row.ds!.name }}</span>
            <span class="ds-kind">{{ kindLabel[row.ds!.kind] ?? row.ds!.kind }}</span>
          </div>
          <span v-if="row.ds!.status" class="ds-status" :style="{ color: statusColor[row.ds!.status] ?? '#aaa' }">
            {{ row.ds!.status }}
          </span>
          <span class="row-actions">
            <button title="移动到文件夹" @click.stop="openMove('ds', row.ds!.id, row.ds!.name)">⤷</button>
            <button title="删除" class="danger" @click.stop="doDelete(row.ds!)">×</button>
          </span>
        </template>
      </div>
    </div>

    <!-- 移动目标选择 -->
    <div v-if="moveTarget" class="move-overlay" @click.self="moveTarget = null">
      <div class="move-panel">
        <div class="move-head">移动「{{ moveTarget.name }}」到…</div>
        <div class="move-list">
          <button class="move-item" @click="doMove(null)">📂 根目录</button>
          <button
            v-for="t in moveTargets" :key="t.id"
            class="move-item"
            :style="{ paddingLeft: (t.depth * 16 + 12) + 'px' }"
            @click="doMove(t.id)"
          >📁 {{ t.name }}</button>
        </div>
        <div class="move-actions">
          <button class="btn-ghost" @click="moveTarget = null">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ds-page { display: flex; flex-direction: column; height: 100%; padding: 24px 32px; color: #e8eaed; overflow-y: auto; }
.ds-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.ds-header h2 { margin: 0; font-size: 18px; font-weight: 600; flex: 1; }

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

/* 树 */
.ds-tree { display: flex; flex-direction: column; gap: 2px; }
.tree-row { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 8px; min-height: 40px; }
.tree-row:hover { background: rgba(255,255,255,.05); }
.tree-row:hover .row-actions { opacity: 1; }
.twisty { width: 16px; text-align: center; color: #aaa; cursor: pointer; font-size: 11px; user-select: none; flex: none; }
.twisty-spacer { width: 16px; flex: none; }
.row-ic { font-size: 18px; flex: none; }
.folder-name { font-size: 14px; font-weight: 500; cursor: pointer; }
.folder-count { font-size: 11px; color: #888; background: rgba(255,255,255,.06); border-radius: 10px; padding: 1px 8px; }
.ds-info { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.ds-info.clickable { cursor: pointer; }
.ds-name { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ds-kind { font-size: 12px; color: #888; }
.ds-status { font-size: 12px; }

.row-actions { display: flex; gap: 2px; margin-left: auto; opacity: 0; transition: opacity .12s; }
.folder-name + .folder-count + .row-actions { margin-left: auto; }
.row-actions button { background: none; border: none; color: #888; font-size: 14px; cursor: pointer; padding: 3px 7px; border-radius: 4px; line-height: 1; }
.row-actions button:hover { color: #e8eaed; background: rgba(255,255,255,.1); }
.row-actions button.danger:hover { color: tomato; background: rgba(255,99,71,.12); }
/* 文件夹行需要把 actions 推到最右：folder-name 占据弹性空间 */
.tree-row .folder-name { flex: 1; }

.empty { color: #888; padding: 40px; text-align: center; font-size: 14px; }

.btn-primary { background: #4a8df0; border: none; color: #fff; padding: 7px 16px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn-primary:disabled { opacity: .5; cursor: not-allowed; }
.btn-ghost { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 12px; color: #e8eaed; cursor: pointer; font-size: 13px; }
.btn-ghost:disabled { opacity: .5; }

/* 移动目标弹层 */
.move-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 50; }
.move-panel { background: #1f2126; border: 1px solid rgba(255,255,255,.14); border-radius: 10px; width: 360px; max-height: 70vh; display: flex; flex-direction: column; overflow: hidden; }
.move-head { padding: 14px 16px; font-size: 14px; font-weight: 500; border-bottom: 1px solid rgba(255,255,255,.08); }
.move-list { overflow-y: auto; padding: 6px; display: flex; flex-direction: column; gap: 2px; }
.move-item { text-align: left; background: none; border: none; color: #e8eaed; padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.move-item:hover { background: rgba(255,255,255,.08); }
.move-actions { padding: 10px 16px; border-top: 1px solid rgba(255,255,255,.08); display: flex; justify-content: flex-end; }
</style>
