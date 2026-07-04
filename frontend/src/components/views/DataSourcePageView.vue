<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import {
  listAllDataSources, listDataSourceReferences, createDataSource,
  deleteDataSource, testDataSourceInline,
} from '../../api/dataSources';
import type { DataSource, DataSourceKind } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { useWorkspaces } from '../../composables/useWorkspaces';
import DataSourceConfigForm from '../datasource/DataSourceConfigForm.vue';
import { Button } from '@/components/ui/button';
import {
  Database, FileText, Globe, Link2, Package, Plus, X, ChevronLeft, Diamond, Circle,
  type LucideIcon,
} from '@lucide/vue';

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
// 数据源 id → 引用它的工作空间 id 列表(通过节点供血绑定)
const refs = ref<Record<string, string[]>>({});

const load = async () => {
  loading.value = true;
  try {
    // 顺带确保工作空间名映射可用
    if (!ws.workspaces.value.length) await ws.reload();
    const [list, references] = await Promise.all([
      listAllDataSources(),
      listDataSourceReferences().catch(() => ({} as Record<string, string[]>)),
    ]);
    items.value = list;
    refs.value = references;
  } catch (e) {
    toast.error(`加载失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally {
    loading.value = false;
  }
};
onMounted(load);

const kindLabel: Record<string, string> = {
  mysql: 'MySQL', pgsql: 'PostgreSQL', oracle: 'Oracle', dm: '达梦 DM', gbase: 'GBase 8a',
  file_stored: '文件', https_api: 'HTTPS 接口',
  file: '文件(旧)', url: 'URL(旧)',
};
const kindIcon: Record<string, LucideIcon> = {
  mysql: Database, pgsql: Database, oracle: Database, dm: Database, gbase: Database,
  file_stored: FileText, https_api: Globe, file: FileText, url: Link2,
};
const iconFor = (kind: string): LucideIcon => kindIcon[kind] ?? Package;
const statusLabel: Record<string, string> = {
  connected: '已连接', error: '异常', idle: '未测试',
};
const statusColor: Record<string, string> = {
  connected: '#22dd88', error: '#ff6644', idle: 'var(--text-muted)',
};
const canOpen = (d: DataSource) =>
  ['mysql', 'pgsql', 'oracle', 'dm', 'gbase', 'file_stored', 'https_api'].includes(d.kind);

const wsName = (id?: string) => {
  if (!id) return '—';
  return ws.workspaces.value.find(w => w.id === id)?.name || '(已删除)';
};

// 引用某数据源的工作空间名列表(通过节点供血绑定)
const refWsNames = (id: string): string[] =>
  (refs.value[id] || []).map(wsName);

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

const SKIP_KEYS = new Set(['storagePath', 'extractedTextPath']);
const previewRows = computed<{ label: string; value: string }[]>(() => {
  const d = previewItem.value;
  if (!d) return [];
  const refNames = refWsNames(d.id);
  const rows: { label: string; value: string }[] = [
    { label: '类型', value: kindLabel[d.kind] ?? d.kind },
    { label: '创建于工作空间', value: wsName(d.workspaceId) },
    { label: '被引用工作空间', value: refNames.length ? refNames.join('、') : '暂未被引用' },
    { label: '状态', value: d.status ? (statusLabel[d.status] ?? d.status) : '未测试' },
  ];
  if (d.createdAt) rows.push({ label: '创建时间', value: new Date(d.createdAt).toLocaleString('zh-CN') });
  // 文件类:名称/大小/页数/字数(抽取阶段写入的旁挂字段)
  if (d.originalName) rows.push({ label: '文件名', value: String(d.originalName) });
  if (typeof d.size === 'number' && d.size > 0) rows.push({ label: '大小', value: `${(d.size / 1024).toFixed(1)} KB` });
  if (d.pages != null) rows.push({ label: '页数', value: String(d.pages) });
  if (d.chars != null) rows.push({ label: '字数', value: String(d.chars) });
  // 音频转写 / 图片识别正文(落库):预览只展示前若干字符,避免撑爆弹窗
  if (typeof d.transcript === 'string' && d.transcript.trim()) {
    const t = d.transcript.trim();
    const label = d.type === 'image' ? '识别文字' : '转写正文';
    rows.push({ label, value: t.length > 500 ? t.slice(0, 500) + '…' : t });
  }
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
const submitting = ref(false);
const testing = ref(false);
const testMsg = ref('');

const TYPES: { kind: DataSourceKind; icon: LucideIcon; label: string; desc: string }[] = [
  { kind: 'mysql',       icon: Database, label: 'MySQL',       desc: '连接 MySQL 数据库，查表写 SQL' },
  { kind: 'pgsql',       icon: Database, label: 'PostgreSQL',  desc: '连接 PgSQL 数据库，查表写 SQL' },
  { kind: 'oracle',      icon: Database, label: 'Oracle',      desc: '连接 Oracle 数据库，查表写 SQL' },
  { kind: 'dm',          icon: Database, label: '达梦 DM',     desc: '国产库，兼容 Oracle 语法' },
  { kind: 'gbase',       icon: Database, label: 'GBase 8a',    desc: '国产 MPP 库，兼容 MySQL 协议' },
  { kind: 'https_api',   icon: Globe,    label: 'HTTPS 接口',  desc: 'REST API，可定时拉取' },
];

const openAdd = () => {
  step.value = 'pick';
  kind.value = null;
  name.value = '';
  cfg.value = {};
  testMsg.value = '';
  submitting.value = false;
  showForm.value = true;
};

const pickType = (k: DataSourceKind) => {
  kind.value = k;
  step.value = 'form';
  if (k === 'mysql')     cfg.value = { host: 'localhost', port: 3306, database: '', username: '', password: '', params: '' };
  if (k === 'pgsql')     cfg.value = { host: 'localhost', port: 5432, database: '', username: '', password: '', params: '' };
  if (k === 'oracle')    cfg.value = { host: 'localhost', port: 1521, database: '', username: '', password: '' };
  if (k === 'dm')        cfg.value = { host: 'localhost', port: 5236, database: '', username: '', password: '' };
  if (k === 'gbase')     cfg.value = { host: 'localhost', port: 5258, database: '', username: '', password: '', params: '' };
  if (k === 'https_api') cfg.value = { url: '', method: 'GET', headers: {}, body: '', timeoutMs: 15000, schedule: { enabled: false, intervalSec: 300 } };
};

const runTest = async () => {
  if (!kind.value) return;
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
    const created: DataSource = await createDataSource({ name: name.value.trim(), kind: kind.value, config: cfg.value });
    // 数据源为公共库：新建只进公共库，不自动进任何工作空间侧栏（需在工作空间右键「引用」纳入）。
    items.value.unshift(created);
    showForm.value = false;
    toast.success(`数据源「${created.name}」已添加到公共库（在工作空间右键「引用」纳入）`);
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
      <Button @click="openAdd"><Plus :size="15" :stroke-width="2" /> 添加数据源</Button>
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
        <Button variant="ghost" size="icon-sm" @click="showForm = false"><X :size="16" /></Button>
      </div>

      <div v-if="step === 'pick'" class="picker">
        <button v-for="t in TYPES" :key="t.kind" class="type-card" @click="pickType(t.kind)">
          <span class="ic"><component :is="t.icon" :size="22" :stroke-width="1.75" /></span>
          <strong>{{ t.label }}</strong>
          <small>{{ t.desc }}</small>
        </button>
      </div>

      <div v-else class="form-area">
        <DataSourceConfigForm
          v-if="kind"
          :kind="kind"
          v-model="cfg"
          v-model:name-value="name"
        />
        <div v-if="testMsg" class="test-msg">{{ testMsg }}</div>
        <div class="form-actions">
          <Button variant="ghost" size="sm" type="button" @click="step = 'pick'"><ChevronLeft :size="15" /> 返回</Button>
          <span style="flex:1" />
          <Button
            v-if="kind"
            variant="outline"
            size="sm"
            type="button"
            :disabled="testing" @click="runTest"
          >{{ testing ? '测试中…' : '测试连接' }}</Button>
          <Button size="sm" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中…' : '保存' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 扁平列表 -->
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!visibleItems.length && !showForm" class="empty">
      暂无数据源，点击「添加数据源」开始
    </div>
    <div v-else class="ds-list">
      <!-- 列表表头 -->
      <div class="ds-row ds-head">
        <span class="row-ic"></span>
        <div class="ds-main"><span class="ds-name">数据源名称 / 类型</span></div>
        <span class="ws-col">被引用的工作空间</span>
        <span class="status-col">连接状态</span>
        <span class="actions-col">操作</span>
      </div>
      <div
        v-for="d in visibleItems"
        :key="d.id"
        class="ds-row clickable"
        @click="openPreview(d)"
      >
        <span class="row-ic"><component :is="iconFor(d.kind)" :size="18" :stroke-width="1.75" /></span>
        <div class="ds-main">
          <span class="ds-name">{{ d.name }}</span>
          <span class="ds-kind">{{ kindLabel[d.kind] ?? d.kind }}</span>
        </div>
        <span
          v-if="refWsNames(d.id).length"
          class="ws-badge"
          :title="`被引用工作空间：${refWsNames(d.id).join('、')}（创建于：${wsName(d.workspaceId)}）`"
        >
          <Diamond class="ws-badge-ic" :size="9" fill="currentColor" />{{ refWsNames(d.id)[0] }}<span
            v-if="refWsNames(d.id).length > 1" class="ws-badge-more"
          >+{{ refWsNames(d.id).length - 1 }}</span>
        </span>
        <span v-else class="ws-badge none" :title="`创建于：${wsName(d.workspaceId)}`">
          <Diamond class="ws-badge-ic" :size="9" />未被引用
        </span>
        <span class="ds-status" :style="{ color: statusColor[d.status ?? 'idle'] ?? 'var(--text-muted)' }">
          <Circle :size="8" fill="currentColor" :stroke-width="0" /> {{ statusLabel[d.status ?? 'idle'] ?? d.status }}
        </span>
        <span class="row-actions">
          <Button variant="ghost" size="sm" title="预览" @click.stop="openPreview(d)">预览</Button>
          <Button variant="ghost" size="sm" title="删除" class="text-destructive" @click.stop="doDelete(d)">删除</Button>
        </span>
      </div>
    </div>

    <!-- 只读预览(不切换工作空间) -->
    <Teleport to="body">
    <div v-if="previewItem" class="preview-overlay" @click.self="closePreview">
      <div class="preview-panel">
        <div class="preview-head">
          <span class="row-ic"><component :is="iconFor(previewItem.kind)" :size="18" :stroke-width="1.75" /></span>
          <span class="preview-title">{{ previewItem.name }}</span>
          <span class="ws-badge"><Diamond class="ws-badge-ic" :size="9" fill="currentColor" />{{ wsName(previewItem.workspaceId) }}</span>
          <Button variant="ghost" size="icon-sm" class="close-btn" @click="closePreview"><X :size="16" /></Button>
        </div>
        <div class="preview-body">
          <div v-for="r in previewRows" :key="r.label" class="preview-row">
            <span class="preview-k">{{ r.label }}</span>
            <span class="preview-v">{{ r.value }}</span>
          </div>
        </div>
        <div class="preview-foot">
          <span class="preview-hint">公共数据源 · 所有工作空间均可查看与使用</span>
          <span style="flex:1" />
          <Button variant="ghost" size="sm" @click="closePreview">关闭</Button>
          <Button
            v-if="canOpen(previewItem)"
            size="sm"
            @click="openFullDetail"
          >打开完整详情</Button>
        </div>
      </div>
    </div>
    </Teleport>
  </div>
</template>

<style scoped>
.ds-page { display: flex; flex-direction: column; height: 100%; padding: 28px 32px; color: var(--text-main); overflow-y: auto; }
.ds-header { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.ds-header h2 { margin: 0; font-size: 22px; font-weight: 600; color: var(--text-main); letter-spacing: 0.3px; }
.ds-total { font-size: 12px; color: var(--text-dim); flex: 1; font-family: var(--font-mono); }

.ws-filter { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
.chip {
  background: var(--bg-elev); border: 1px solid var(--glass-border);
  border-radius: 100px; padding: 4px 13px; color: var(--text-dim); cursor: pointer; font-size: 12px;
  transition: background .14s var(--ease-out), color .14s var(--ease-out), border-color .14s var(--ease-out);
}
.chip:hover { background: var(--bg-elev-hi); color: var(--text-main); }
.chip.on { background: var(--accent-tint); border-color: rgba(47,134,214,.5); color: var(--accent-soft); }

.add-panel {
  background: linear-gradient(180deg, var(--bg-panel) 0%, rgba(11,18,32,.5) 100%);
  border: 1px solid var(--hairline); border-radius: 14px; margin-bottom: 20px;
  box-shadow: var(--shadow-md), inset 0 1px 0 var(--hairline-top);
  overflow: hidden;
}
.add-panel-head { display: flex; align-items: center; justify-content: space-between; padding: 13px 18px; border-bottom: 1px solid var(--hairline); font-size: 13px; font-weight: 500; color: var(--text-main); background: linear-gradient(180deg, rgba(255,255,255,.03), transparent); }
.close-btn { color: var(--text-dim); }

.picker { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding: 18px; }
.type-card {
  display: flex; flex-direction: column; align-items: flex-start; gap: 6px;
  background: var(--bg-elev); border: 1px solid var(--glass-border); border-radius: 12px;
  padding: 16px; cursor: pointer; color: var(--text-main); text-align: left;
  transition: transform .18s var(--ease-out), background .18s var(--ease-out), border-color .18s var(--ease-out), box-shadow .18s var(--ease-out);
}
.type-card:hover { transform: translateY(-2px); background: var(--bg-elev-hi); border-color: rgba(47,134,214,.42); box-shadow: 0 10px 26px rgba(0,0,0,.28); }
.type-card .ic { display: inline-flex; color: var(--accent-soft); margin-bottom: 2px; }
.type-card strong { font-size: 14px; font-weight: 600; }
.type-card small { font-size: 12px; color: var(--text-dim); line-height: 1.5; }

.form-area { padding: 18px; display: flex; flex-direction: column; gap: 12px; max-width: 560px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: var(--text-dim); }
.row > input { flex: 1; background: rgba(8,13,22,.7); border: 1px solid var(--glass-border); border-radius: 8px; padding: 8px 10px; color: var(--text-main); outline: none; transition: border-color .15s; }
.row > input:focus { border-color: rgba(47,134,214,.55); }
.row.block { flex-direction: column; align-items: stretch; gap: 6px; }
.test-msg { font-size: 13px; color: var(--text-dim); padding: 10px 12px; background: var(--bg-elev); border: 1px solid var(--hairline); border-radius: 8px; }
.form-actions { display: flex; gap: 8px; align-items: center; padding-top: 4px; }

/* 扁平列表 */
.ds-list { display: flex; flex-direction: column; gap: 3px; }
.ds-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; border-radius: 11px; min-height: 48px;
  border: 1px solid transparent;
  transition: background .14s var(--ease-out), border-color .14s var(--ease-out);
}
.ds-row:hover { background: var(--bg-elev); border-color: var(--hairline); }

/* 列表表头 */
.ds-head {
  min-height: 0; padding-top: 2px; padding-bottom: 9px; margin-bottom: 2px;
  border-radius: 0; cursor: default;
  border-bottom: 1px solid var(--hairline);
}
.ds-head:hover { background: none; border-color: transparent; border-bottom-color: var(--hairline); }
.ds-head .ds-name { font-size: 11px; font-weight: 600; color: var(--text-dim); text-transform: uppercase; letter-spacing: 0.6px; }
.ds-head .ws-col, .ds-head .status-col, .ds-head .actions-col {
  flex: none; font-size: 11px; font-weight: 600; color: var(--text-dim); text-transform: uppercase; letter-spacing: 0.6px;
}
.ds-head .ws-col { width: 160px; }
.ds-head .status-col { width: 120px; }
.ds-head .actions-col { width: 120px; text-align: center; }
.ds-row.clickable { cursor: pointer; }
.ds-row:hover .row-actions { opacity: 1; }
.row-ic { display: inline-flex; align-items: center; justify-content: center; flex: none; width: 30px; height: 30px; border-radius: 9px; background: var(--accent-tint); color: var(--accent-soft); }
.ds-main { display: flex; flex-direction: column; gap: 2px; flex: 0 0 360px; min-width: 0; }
.ds-name { font-size: 14px; font-weight: 500; color: var(--text-main); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ds-kind { font-size: 12px; color: var(--text-muted); font-family: var(--font-mono); }

.ws-badge {
  display: inline-flex; align-items: center; gap: 5px; flex: none;
  font-size: 12px; color: var(--accent-soft);
  background: var(--accent-tint); border: 1px solid rgba(61,155,255,.28);
  border-radius: 100px; padding: 3px 11px; width: 160px; box-sizing: border-box;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ws-badge-ic { flex-shrink: 0; opacity: .85; }
.ws-badge-more { margin-left: 4px; font-size: 10px; opacity: .75; }
.ws-badge.none { color: var(--text-muted); background: var(--bg-elev); border-color: var(--glass-border); }
.ds-status { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; flex: none; width: 120px; }

.row-actions { display: flex; gap: 4px; flex: none; width: 120px; justify-content: center; opacity: 0; transition: opacity .14s; }
.row-actions button:hover { color: #fff; background: var(--bg-elev-hi); }
.row-actions button.danger:hover { color: #fff; background: rgba(255,99,71,.22); border-color: rgba(255,99,71,.5); }

.empty { color: var(--text-dim); padding: 48px; text-align: center; font-size: 14px; }

/* 只读预览弹层 */
.preview-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.55); backdrop-filter: blur(4px); -webkit-backdrop-filter: blur(4px); display: flex; align-items: center; justify-content: center; z-index: 60; }
.preview-panel { background: linear-gradient(180deg, #182338 0%, #101729 100%); border: 1px solid var(--glass-border); border-radius: 16px; width: 460px; max-width: 92vw; max-height: 80vh; display: flex; flex-direction: column; overflow: hidden; box-shadow: var(--shadow-panel); }
.preview-head { display: flex; align-items: center; gap: 10px; padding: 15px 18px; border-bottom: 1px solid var(--hairline); background: linear-gradient(180deg, rgba(255,255,255,.03), transparent); }
.preview-title { font-size: 15px; font-weight: 600; color: var(--text-main); flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.preview-head .close-btn { margin-left: 4px; }
.preview-body { padding: 8px 18px 12px; overflow-y: auto; display: flex; flex-direction: column; }
.preview-row { display: flex; gap: 12px; padding: 9px 0; border-bottom: 1px dashed var(--hairline); font-size: 13px; }
.preview-row:last-child { border-bottom: none; }
.preview-k { flex: none; width: 120px; color: var(--text-dim); word-break: break-all; text-transform: uppercase; font-size: 11px; letter-spacing: 0.3px; }
.preview-v { flex: 1; min-width: 0; color: var(--text-main); word-break: break-all; }
.preview-foot { display: flex; align-items: center; gap: 8px; padding: 13px 18px; border-top: 1px solid var(--hairline); }
.preview-hint { font-size: 12px; color: var(--text-dim); }
</style>
