<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
import { confirm as uiConfirm } from '../composables/useConfirm';
import { prompt as uiPrompt } from '../composables/usePrompt';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { Workspace } from '../api/workspaces';
import type { DataSource } from '../api/dataSources';
import {
  createFolder, renameFolder, deleteFolder, moveFolder,
  moveDataSourceToFolder, type DataSourceFolder,
} from '../api/folders';

const props = defineProps<{
  workspace: Workspace;
  isCurrent: boolean;
}>();

const emit = defineEmits<{
  (e: 'switch-current', id: string): void;
  (e: 'open-conversation', id: string): void;
  (e: 'open-graph', id: string): void;
  (e: 'open-datasource', id: string): void;
  (e: 'open-datasources', workspaceId: string): void;
  (e: 'add-datasource', workspaceId: string): void;
  (e: 'rename-conversation', id: string, title: string): void;
  (e: 'delete-conversation', id: string): void;
  (e: 'rename-graph', id: string, title: string): void;
  (e: 'delete-graph', id: string): void;
}>();

const ws = useWorkspaces();
const tree = useSidebarTree();

const expanded = ref(props.isCurrent);
type CtxKind = 'conversation' | 'graph' | 'datasource-section' | 'folder' | 'datasource';
const ctxMenu = ref<null | {
  kind: CtxKind;
  id: string;
  title: string;
  x: number;
  y: number;
}>(null);
// 「移动到文件夹」二级菜单:列出可选目标文件夹 + 根目录。
const moveMenu = ref<null | {
  kind: 'datasource' | 'folder';
  id: string;
  name: string;
  x: number;
  y: number;
}>(null);

// 三个区段(历史对话/血缘图/数据源)独立折叠;状态用 localStorage 持久化,
// 全局共享(所有工作空间共用一份偏好,避免每个 ws 都要单独点开)。
type SectionKey = 'convs' | 'ontologies' | 'ds';
const SECTIONS_KEY = 'tuiyan.sidebar-sections';
const defaultSections = (): Record<SectionKey, boolean> => ({ convs: true, ontologies: true, ds: true });
const readSections = (): Record<SectionKey, boolean> => {
  try {
    const raw = localStorage.getItem(SECTIONS_KEY);
    if (!raw) return defaultSections();
    const parsed = JSON.parse(raw) as Partial<Record<SectionKey, boolean>>;
    return {
      convs: parsed.convs !== false,
      ontologies: parsed.ontologies !== false,
      ds: parsed.ds !== false,
    };
  } catch { return defaultSections(); }
};
const sections = ref<Record<SectionKey, boolean>>(readSections());
const toggleSection = (key: SectionKey, e: Event) => {
  e.stopPropagation();
  sections.value = { ...sections.value, [key]: !sections.value[key] };
  try { localStorage.setItem(SECTIONS_KEY, JSON.stringify(sections.value)); } catch { /* noop */ }
};

watch(() => props.isCurrent, (v) => {
  if (v) expanded.value = true;
});

watch(expanded, (v) => {
  if (v) {
    tree.loadConversations(props.workspace.id);
    tree.loadOntologies(props.workspace.id);
    tree.loadDataSources(props.workspace.id);
    tree.loadFolders(props.workspace.id);
  }
}, { immediate: true });

onBeforeUnmount(() => closeCtxMenu());

const wsInitial = (name: string) => {
  const trimmed = (name || '').trim();
  return trimmed ? trimmed.charAt(0) : '?';
};

const closeCtxMenu = () => {
  ctxMenu.value = null;
  moveMenu.value = null;
};

const openCtxMenu = (
  kind: CtxKind,
  id: string,
  title: string,
  e: MouseEvent,
) => {
  e.preventDefault();
  e.stopPropagation();
  moveMenu.value = null;
  // 文件夹菜单条目更多,给它留更高的纵向空间。
  const reserveH = kind === 'folder' ? 180 : kind === 'datasource' ? 96 : 132;
  ctxMenu.value = {
    kind,
    id,
    title,
    x: Math.min(e.clientX, window.innerWidth - 220),
    y: Math.min(e.clientY, window.innerHeight - reserveH),
  };
};

const openDataSourceSection = () => {
  emit('open-datasources', props.workspace.id);
};

const addDataSourceToWorkspace = () => {
  closeCtxMenu();
  emit('add-datasource', props.workspace.id);
};

// ── 数据源文件夹树 ───────────────────────────────────────
const wsId = computed(() => props.workspace.id);
// 文件夹展开状态(本工作空间内,内存态即可)。
const folderOpen = ref<Set<string>>(new Set());
const isFolderOpen = (id: string) => folderOpen.value.has(id);
const toggleFolder = (id: string) => {
  const next = new Set(folderOpen.value);
  next.has(id) ? next.delete(id) : next.add(id);
  folderOpen.value = next;
};

interface DsRow { kind: 'folder' | 'ds'; depth: number; folder?: DataSourceFolder; ds?: DataSource; }

// 文件夹(按 parentId 任意层级) + 数据源(按 folderId)扁平成带 depth 的行,折叠的文件夹不展开子节点。
const dsRows = computed<DsRow[]>(() => {
  const folders = tree.getFolders(wsId.value);
  const dss = tree.getDataSources(wsId.value);
  const byParent = new Map<string, DataSourceFolder[]>();
  for (const f of folders) {
    const k = f.parentId || '__root__';
    if (!byParent.has(k)) byParent.set(k, []);
    byParent.get(k)!.push(f);
  }
  const dsByFolder = new Map<string, DataSource[]>();
  for (const d of dss) {
    const k = d.folderId || '__root__';
    if (!dsByFolder.has(k)) dsByFolder.set(k, []);
    dsByFolder.get(k)!.push(d);
  }
  const rows: DsRow[] = [];
  const walk = (parentKey: string, depth: number) => {
    for (const f of (byParent.get(parentKey) ?? [])) {
      rows.push({ kind: 'folder', depth, folder: f });
      if (folderOpen.value.has(f.id)) {
        walk(f.id, depth + 1);
        for (const d of (dsByFolder.get(f.id) ?? [])) rows.push({ kind: 'ds', depth: depth + 1, ds: d });
      }
    }
  };
  walk('__root__', 0);
  for (const d of (dsByFolder.get('__root__') ?? [])) rows.push({ kind: 'ds', depth: 0, ds: d });
  return rows;
});

const folderDsCount = (folderId: string) =>
  tree.getDataSources(wsId.value).filter(d => d.folderId === folderId).length;

const refreshDS = async () => {
  await Promise.all([
    tree.loadFolders(wsId.value, true),
    tree.loadDataSources(wsId.value, true),
  ]);
};

const createFolderIn = async (parentId: string | null) => {
  closeCtxMenu();
  const name = await uiPrompt({
    title: parentId ? '新建子文件夹' : '新建文件夹',
    message: '请输入文件夹名称',
    defaultValue: '新文件夹',
    confirmLabel: '创建',
    cancelLabel: '取消',
  });
  if (!name || !name.trim()) return;
  try {
    await createFolder({ name: name.trim(), parentId });
    if (parentId) folderOpen.value = new Set(folderOpen.value).add(parentId);
    await refreshDS();
    toast.success('已创建文件夹');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '创建失败');
  }
};

const renameFolderAct = async () => {
  const current = ctxMenu.value;
  if (!current || current.kind !== 'folder') return;
  closeCtxMenu();
  const name = await uiPrompt({
    title: '重命名文件夹',
    message: '请输入新的名称',
    defaultValue: current.title,
    confirmLabel: '保存',
    cancelLabel: '取消',
  });
  const next = (name || '').trim();
  if (!next || next === current.title) return;
  try {
    await renameFolder(current.id, next);
    await refreshDS();
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '重命名失败');
  }
};

const deleteFolderAct = async () => {
  const current = ctxMenu.value;
  if (!current || current.kind !== 'folder') return;
  closeCtxMenu();
  const ok = await uiConfirm({
    title: '删除文件夹',
    message: `删除「${current.title}」？其中的子文件夹与数据源会上移到上一级，不会被删除。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await deleteFolder(current.id);
    await refreshDS();
    toast.success('已删除文件夹');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
};

const deleteDataSourceAct = async () => {
  const current = ctxMenu.value;
  if (!current || current.kind !== 'datasource') return;
  closeCtxMenu();
  const ok = await uiConfirm({
    title: '删除数据源',
    message: `确认删除「${current.title}」吗？此操作不可恢复。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await tree.removeDataSource(wsId.value, current.id);
    toast.success('已删除');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
};

// 把某文件夹及其全部子孙 id 收集起来(移动文件夹时从候选目标里排除,避免成环)。
const subtreeIds = (rootId: string): Set<string> => {
  const out = new Set<string>([rootId]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const f of tree.getFolders(wsId.value)) {
      if (f.parentId && out.has(f.parentId) && !out.has(f.id)) { out.add(f.id); changed = true; }
    }
  }
  return out;
};

const moveTargets = computed<{ id: string; name: string; depth: number }[]>(() => {
  const mm = moveMenu.value;
  const exclude = mm?.kind === 'folder' ? subtreeIds(mm.id) : new Set<string>();
  const byParent = new Map<string, DataSourceFolder[]>();
  for (const f of tree.getFolders(wsId.value)) {
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

const openMoveMenu = () => {
  const current = ctxMenu.value;
  if (!current || (current.kind !== 'datasource' && current.kind !== 'folder')) return;
  moveMenu.value = {
    kind: current.kind,
    id: current.id,
    name: current.title,
    x: current.x,
    y: Math.min(current.y, window.innerHeight - 240),
  };
  ctxMenu.value = null;
};

const doMove = async (targetFolderId: string | null) => {
  const mm = moveMenu.value;
  if (!mm) return;
  closeCtxMenu();
  try {
    if (mm.kind === 'datasource') await moveDataSourceToFolder(mm.id, targetFolderId);
    else await moveFolder(mm.id, targetFolderId);
    if (targetFolderId) folderOpen.value = new Set(folderOpen.value).add(targetFolderId);
    await refreshDS();
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '移动失败');
  }
};

const kindIcon: Record<string, string> = {
  mysql: '🗄', pgsql: '🐘', file_stored: '📄', https_api: '🌐',
};

const renameItem = async () => {
  const current = ctxMenu.value;
  if (!current || current.kind === 'datasource-section') return;
  closeCtxMenu();
  const next = await uiPrompt({
    title: current.kind === 'conversation' ? '重命名对话' : '重命名血缘图',
    message: '请输入新的名称',
    defaultValue: current.title,
    confirmLabel: '保存',
    cancelLabel: '取消',
  });
  const title = (next || '').trim();
  if (!title || title === current.title) return;
  if (current.kind === 'conversation') emit('rename-conversation', current.id, title);
  else emit('rename-graph', current.id, title);
};

const deleteItem = async () => {
  const current = ctxMenu.value;
  if (!current || current.kind === 'datasource-section') return;
  closeCtxMenu();
  const ok = await uiConfirm({
    title: current.kind === 'conversation' ? '删除对话' : '删除血缘图',
    message: `确认删除「${current.title}」吗？此操作不可恢复。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  if (current.kind === 'conversation') emit('delete-conversation', current.id);
  else emit('delete-graph', current.id);
};

const onGlobalMouseDown = () => closeCtxMenu();
watch([ctxMenu, moveMenu], ([c, m], [pc, pm]) => {
  if ((!pc && c) || (!pm && m)) {
    window.addEventListener('mousedown', onGlobalMouseDown, { once: true });
  }
});

const toggle = async (e: Event) => {
  e.stopPropagation();
  if (!props.isCurrent) {
    const ok = await uiConfirm({
      title: '切换工作空间',
      message: `将切换到「${props.workspace.name}」。未保存内容会立即提交，列表和对话会重新加载。`,
      confirmLabel: '切换',
    });
    if (!ok) return;
    emit('switch-current', props.workspace.id);
    expanded.value = true;
    return;
  }
  expanded.value = !expanded.value;
};

const onClickDelete = async (e: Event) => {
  e.stopPropagation();
  const isLast = ws.workspaces.value.length <= 1;
  const extraNote = isLast
    ? '这是最后一个工作空间，删除后会回到工作空间选择页。'
    : props.workspace.isDefault
      ? '这是默认工作空间，删除后会自动把另一个工作空间设为默认。'
      : '';
  const ok = await uiConfirm({
    title: '删除工作空间',
    message: `「${props.workspace.name}」内的内容将一并清空，且无法恢复。${extraNote}确定继续吗？`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  const wasDefault = props.workspace.isDefault;
  try {
    const res = await ws.remove(props.workspace.id);
    toast.success('已删除');
    if (res.switchedTo || wasDefault || ws.workspaces.value.length === 0) window.location.reload();
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
};

const fmtTime = (t: number) => {
  if (!t) return '';
  const d = new Date(t);
  const now = new Date();
  return d.toDateString() === now.toDateString()
    ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
};

const kindLabel: Record<string, string> = {
  mysql: 'MySQL',
  pgsql: 'PgSQL',
  file_stored: '文件',
  https_api: 'HTTPS',
};

const onDeleteLineageModel = (modelId: string, e: Event) => {
  e.stopPropagation();
  // 仅在当前 ws 暴露删除按钮(列表本就只在 isCurrent 时注入);二次确认与 toast 在父级 composable 里
  emit('delete-ontology-model', props.workspace.id, modelId);
};
</script>

<template>
  <div class="ws-wrap">
    <div :class="['ws-node', { active: isCurrent }]" @click="toggle">
      <span class="ws-caret" :class="{ open: expanded }">▶</span>
      <span class="ws-avatar">{{ wsInitial(workspace.name) }}</span>
      <span class="ws-name">{{ workspace.name }}</span>
      <span v-if="workspace.isDefault" class="ws-tag">默认</span>
      <span v-if="isCurrent" class="ws-dot" title="当前工作空间" />
      <button class="ws-del-btn" :title="`删除 ${workspace.name}`" @click="onClickDelete">×</button>
    </div>

    <div v-if="expanded" class="ws-children">
      <div class="ws-section">
        <button type="button" class="ws-section-head" @click="toggleSection('convs', $event)">
          <span class="ws-section-caret" :class="{ open: sections.convs }">▸</span>
          <span class="ws-section-lbl">历史对话</span>
          <span v-if="tree.getConversations(workspace.id).length" class="ws-section-count">{{ tree.getConversations(workspace.id).length }}</span>
        </button>
        <template v-if="sections.convs">
          <span v-if="tree.isLoadingConv(workspace.id)" class="ws-loading">加载中...</span>
          <template v-else-if="tree.getConversations(workspace.id).length">
            <button
              v-for="c in tree.getConversations(workspace.id).slice(0, 10)"
              :key="c.id"
              class="ws-item"
              @click.stop="emit('open-conversation', c.id)"
              @contextmenu="openCtxMenu('conversation', c.id, c.title, $event)"
              :title="c.title"
            >
              <span class="ws-item-label">{{ c.title || '新对话' }}</span>
              <span v-if="c.updatedAt" class="ws-item-time">{{ fmtTime(c.updatedAt) }}</span>
            </button>
          </template>
          <span v-else class="ws-empty">暂无对话</span>
        </template>
      </div>

      <div class="ws-section">
        <button type="button" class="ws-section-head" @click="toggleSection('ontologies', $event)">
          <span class="ws-section-caret" :class="{ open: sections.ontologies }">▸</span>
          <span class="ws-section-lbl">血缘图</span>
          <span v-if="tree.getOntologies(workspace.id).length" class="ws-section-count">{{ tree.getOntologies(workspace.id).length }}</span>
        </button>
        <template v-if="sections.ontologies">
          <span v-if="tree.isLoadingOntology(workspace.id)" class="ws-loading">加载中...</span>
          <template v-else-if="tree.getOntologies(workspace.id).length">
            <button
              v-for="m in tree.getOntologies(workspace.id)"
              :key="m.id"
              class="ws-item"
              @click.stop="emit('open-graph', m.id)"
              @contextmenu="openCtxMenu('graph', m.id, m.name, $event)"
              :title="m.name"
            >
              <span class="ws-item-label">{{ m.name }}</span>
              <span v-if="m.updatedAt" class="ws-item-time">{{ fmtTime(m.updatedAt) }}</span>
            </button>
          </template>
          <span v-else class="ws-empty">暂无图谱</span>
        </template>
      </div>

      <div class="ws-section">
        <button
          type="button"
          class="ws-section-head"
          @click="toggleSection('ds', $event)"
          @contextmenu="openCtxMenu('datasource-section', workspace.id, '数据源', $event)"
        >
          <span class="ws-section-caret" :class="{ open: sections.ds }">▸</span>
          <span class="ws-section-lbl">数据源</span>
          <span v-if="tree.getDataSources(workspace.id).length" class="ws-section-count">{{ tree.getDataSources(workspace.id).length }}</span>
          <span
            class="ws-section-open"
            title="打开数据源列表"
            @click.stop="openDataSourceSection"
          >↗</span>
        </button>
        <template v-if="sections.ds">
          <span v-if="tree.isLoadingDS(workspace.id) || tree.isLoadingFolders(workspace.id)" class="ws-loading">加载中...</span>
          <template v-else-if="dsRows.length">
            <template v-for="row in dsRows" :key="row.kind + ':' + (row.folder?.id || row.ds?.id)">
              <!-- 文件夹行 -->
              <button
                v-if="row.kind === 'folder'"
                class="ws-tree-row is-folder"
                @click.stop="toggleFolder(row.folder!.id)"
                @contextmenu="openCtxMenu('folder', row.folder!.id, row.folder!.name, $event)"
                :title="row.folder!.name"
              >
                <span v-for="i in row.depth" :key="'g' + i" class="ws-guide" />
                <span class="ws-tree-main">
                  <span class="ws-tw" :class="{ open: isFolderOpen(row.folder!.id) }">▸</span>
                  <span class="ws-tree-ico">{{ isFolderOpen(row.folder!.id) ? '📂' : '📁' }}</span>
                  <span class="ws-item-label">{{ row.folder!.name }}</span>
                  <span v-if="folderDsCount(row.folder!.id)" class="ws-section-count">{{ folderDsCount(row.folder!.id) }}</span>
                </span>
              </button>
              <!-- 数据源行 -->
              <button
                v-else
                class="ws-tree-row is-ds"
                @click.stop="emit('open-datasource', row.ds!.id)"
                @contextmenu="openCtxMenu('datasource', row.ds!.id, row.ds!.name, $event)"
                :title="row.ds!.name"
              >
                <span v-for="i in row.depth" :key="'g' + i" class="ws-guide" />
                <span class="ws-tree-main">
                  <span class="ws-tw ws-tw-spacer" />
                  <span class="ws-tree-ico">{{ kindIcon[row.ds!.kind] || '◦' }}</span>
                  <span class="ws-item-label">{{ row.ds!.name }}</span>
                  <span class="ws-item-kind">{{ kindLabel[row.ds!.kind] || row.ds!.kind }}</span>
                </span>
              </button>
            </template>
          </template>
          <span v-else class="ws-empty">暂无数据源</span>
        </template>
      </div>
    </div>

    <div
      v-if="ctxMenu"
      class="node-ctx-menu"
      :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
      @mousedown.stop
      @click.stop
    >
      <!-- 数据源区段 -->
      <template v-if="ctxMenu.kind === 'datasource-section'">
        <button class="ctx-item" @click="openDataSourceSection(); closeCtxMenu()">
          <span class="ctx-icon">◈</span>
          <span>查看数据源</span>
          <span class="ctx-hint">Open</span>
        </button>
        <button class="ctx-item" @click="addDataSourceToWorkspace">
          <span class="ctx-icon">＋</span>
          <span>添加数据源</span>
          <span class="ctx-hint">Add</span>
        </button>
        <button class="ctx-item" @click="createFolderIn(null)">
          <span class="ctx-icon">📁</span>
          <span>新建文件夹</span>
          <span class="ctx-hint">Folder</span>
        </button>
      </template>

      <!-- 文件夹 -->
      <template v-else-if="ctxMenu.kind === 'folder'">
        <button class="ctx-item" @click="createFolderIn(ctxMenu.id)">
          <span class="ctx-icon">📁</span>
          <span>新建子文件夹</span>
          <span class="ctx-hint">Sub</span>
        </button>
        <button class="ctx-item" @click="openMoveMenu">
          <span class="ctx-icon">⇄</span>
          <span>移动到…</span>
          <span class="ctx-hint">Move</span>
        </button>
        <button class="ctx-item" @click="renameFolderAct">
          <span class="ctx-icon">✎</span>
          <span>重新命名</span>
          <span class="ctx-hint">Rename</span>
        </button>
        <button class="ctx-item ctx-danger" @click="deleteFolderAct">
          <span class="ctx-icon">🗑</span>
          <span>删除文件夹</span>
          <span class="ctx-hint">Delete</span>
        </button>
      </template>

      <!-- 数据源条目 -->
      <template v-else-if="ctxMenu.kind === 'datasource'">
        <button class="ctx-item" @click="openMoveMenu">
          <span class="ctx-icon">⇄</span>
          <span>移动到文件夹…</span>
          <span class="ctx-hint">Move</span>
        </button>
        <button class="ctx-item ctx-danger" @click="deleteDataSourceAct">
          <span class="ctx-icon">🗑</span>
          <span>删除</span>
          <span class="ctx-hint">Delete</span>
        </button>
      </template>

      <!-- 对话 / 血缘图 -->
      <template v-else>
        <button class="ctx-item" @click="renameItem">
          <span class="ctx-icon">✎</span>
          <span>重新命名</span>
          <span class="ctx-hint">Rename</span>
        </button>
        <button class="ctx-item ctx-danger" @click="deleteItem">
          <span class="ctx-icon">🗑</span>
          <span>删除</span>
          <span class="ctx-hint">Delete</span>
        </button>
      </template>
    </div>

    <!-- 移动到文件夹:二级菜单 -->
    <div
      v-if="moveMenu"
      class="node-ctx-menu move-menu"
      :style="{ left: moveMenu.x + 'px', top: moveMenu.y + 'px' }"
      @mousedown.stop
      @click.stop
    >
      <div class="ctx-title">移动「{{ moveMenu.name }}」到</div>
      <button class="ctx-item" @click="doMove(null)">
        <span class="ctx-icon">◎</span>
        <span>根目录</span>
      </button>
      <button
        v-for="t in moveTargets"
        :key="t.id"
        class="ctx-item"
        :style="{ paddingLeft: 10 + t.depth * 14 + 'px' }"
        @click="doMove(t.id)"
      >
        <span class="ctx-icon">📁</span>
        <span class="ctx-move-label">{{ t.name }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.ws-wrap { display: flex; flex-direction: column; }
.ws-node {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; border-radius: 9px; cursor: pointer;
  color: var(--text-dim); font-size: 12.5px;
  transition: background .15s ease, color .15s ease, box-shadow .15s ease;
  letter-spacing: 0.15px;
}
.ws-node:hover { background: rgba(255,255,255,.05); color: var(--text-main); }
.ws-node.active {
  background: linear-gradient(135deg, rgba(66,184,131,.18), rgba(66,184,131,.08));
  color: #5fd4a3;
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.04);
}
.ws-caret {
  font-size: 9px; color: rgba(255,255,255,.40);
  transition: transform .2s cubic-bezier(.34,1.56,.64,1); flex-shrink: 0;
}
.ws-caret.open { transform: rotate(90deg); }
.ws-avatar {
  width: 22px; height: 22px; flex-shrink: 0; border-radius: 7px;
  background: linear-gradient(135deg,#5d9eff 0%, #6366f1 60%, #8b5cf6 100%);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; font-family: 'Inter',sans-serif;
  box-shadow: 0 3px 10px rgba(99,102,241,0.30), inset 0 1px 0 rgba(255,255,255,0.20);
  letter-spacing: 0.3px;
}
.ws-node.active .ws-avatar {
  background: linear-gradient(135deg,#5fd4a3 0%, #42b883 55%, #2d9b6e 100%);
  box-shadow: 0 3px 12px rgba(66,184,131,0.42), 0 0 0 1px rgba(66,184,131,.45), inset 0 1px 0 rgba(255,255,255,0.22);
}
.ws-name { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ws-tag {
  font-size: 9px; padding: 1px 6px; border-radius: 100px;
  background: rgba(255,255,255,.06); color: rgba(255,255,255,.50); flex-shrink: 0;
  font-family: 'JetBrains Mono', monospace;
  letter-spacing: 0.3px;
}
.ws-node.active .ws-tag {
  background: rgba(66,184,131,.18); color: #6dd4a7;
  box-shadow: inset 0 0 0 1px rgba(66,184,131,0.22);
}
.ws-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: #5fd4a3;
  box-shadow: 0 0 6px #42b883, 0 0 0 2px rgba(66,184,131,0.18);
  flex-shrink: 0;
}
.ws-del-btn {
  background: transparent; border: none; color: rgba(255,255,255,.32);
  font-size: 14px; line-height: 1; width: 18px; height: 18px;
  border-radius: 50%; cursor: pointer; font-family: inherit;
  display: flex; align-items: center; justify-content: center; padding: 0; flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;
}
.ws-del-btn:hover { background: rgba(255,102,68,.20); color: #ff8a6f; }
.ws-children { display: flex; flex-direction: column; gap: 2px; margin: 2px 0 4px; }
.ws-section { display: flex; flex-direction: column; gap: 0; }
.ws-section-head {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding: 6px 10px 4px 24px;
  background: none;
  border: none;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  user-select: none;
  border-radius: 6px;
  transition: background 0.12s ease, color 0.12s ease;
}
.ws-section-head:hover { background: rgba(255,255,255,.04); }
.ws-section-head:hover .ws-section-lbl { color: rgba(255,255,255,.62); }
.ws-section-caret {
  font-size: 8px;
  color: rgba(255,255,255,.32);
  transition: transform .2s cubic-bezier(.34,1.56,.64,1);
  flex-shrink: 0;
  width: 10px;
  text-align: center;
}
.ws-section-caret.open { transform: rotate(90deg); }
.ws-section-lbl {
  font-size: 10px;
  letter-spacing: 1.2px;
  color: rgba(255,255,255,.42);
  font-family: 'JetBrains Mono', 'Inter', sans-serif;
  font-weight: 600;
  text-transform: uppercase;
  transition: color 0.12s ease;
}
.ws-section-count {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 100px;
  background: rgba(255,255,255,.06);
  color: rgba(255,255,255,.40);
  font-family: 'JetBrains Mono', monospace;
  letter-spacing: 0.2px;
  margin-left: 2px;
}
.ws-section-open {
  margin-left: auto;
  font-size: 11px;
  color: rgba(255,255,255,.32);
  padding: 0 6px;
  border-radius: 4px;
  transition: background 0.12s ease, color 0.12s ease;
  cursor: pointer;
}
.ws-section-open:hover { color: #5fd4a3; background: rgba(66,184,131,.10); }
.ws-loading { font-size: 11px; color: rgba(255,255,255,.32); padding: 2px 10px 2px 44px; font-style: italic; }
.ws-empty { font-size: 11px; color: rgba(255,255,255,.22); padding: 2px 10px 4px 44px; font-style: italic; }
.ws-item {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 10px 4px 44px; border-radius: 7px; cursor: pointer;
  background: none; border: none; color: rgba(255,255,255,.62);
  font-size: 12px; text-align: left; width: 100%; font-family: inherit;
  transition: background .12s ease, color .12s ease;
  position: relative;
  letter-spacing: 0.1px;
}
.ws-item::before {
  content: '';
  position: absolute;
  left: 32px; top: 50%;
  width: 4px; height: 4px;
  border-radius: 50%;
  background: rgba(255,255,255,0.18);
  transform: translateY(-50%);
  transition: background 0.12s ease;
}
.ws-item:hover { background: rgba(255,255,255,.05); color: #f4f7fb; }
.ws-item:hover::before { background: rgba(66,184,131,0.6); box-shadow: 0 0 6px rgba(66,184,131,0.5); }
.ws-item-label { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ws-item-time { font-size: 10px; color: rgba(255,255,255,.30); flex-shrink: 0; font-family: 'JetBrains Mono', monospace; }
.ws-item-kind {
  font-size: 9px; padding: 1px 5px; border-radius: 100px; flex-shrink: 0;
  background: rgba(255,255,255,.06); color: rgba(255,255,255,.40);
  font-family: 'JetBrains Mono', monospace;
  letter-spacing: 0.3px;
}

/* ── 数据源文件夹树 ───────────────────────────────── */
.ws-tree-row {
  display: flex;
  align-items: stretch;
  width: 100%;
  min-height: 26px;
  padding: 0 8px 0 22px;   /* 左对齐到区段标题文字下方 */
  background: none;
  border: none;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
}
/* 每一层一条竖直引导线,让层级一目了然 */
.ws-guide {
  flex: 0 0 16px;
  align-self: stretch;
  margin-left: 2px;
  border-left: 1px solid rgba(255,255,255,.10);
}
.ws-tree-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 4px;
  border-radius: 7px;
  color: rgba(255,255,255,.62);
  font-size: 12px;
  transition: background .12s ease, color .12s ease;
  letter-spacing: 0.1px;
}
.ws-tree-row:hover .ws-tree-main { background: rgba(255,255,255,.05); color: #f4f7fb; }
.ws-tw {
  font-size: 8px;
  color: rgba(255,255,255,.40);
  flex-shrink: 0;
  width: 10px;
  text-align: center;
  transition: transform .2s cubic-bezier(.34,1.56,.64,1);
}
.ws-tw.open { transform: rotate(90deg); }
.ws-tw-spacer { visibility: hidden; }
.ws-tree-ico { flex-shrink: 0; font-size: 13px; line-height: 1; width: 16px; text-align: center; }
.ws-tree-row.is-folder .ws-tree-main { color: rgba(255,255,255,.80); font-weight: 600; }
.ws-tree-row.is-folder:hover .ws-tree-main { color: #ffe7a8; }
.ws-tree-row.is-ds:hover .ws-tree-ico { filter: drop-shadow(0 0 5px rgba(66,184,131,.5)); }

.node-ctx-menu {
  position: fixed;
  z-index: 2000;
  min-width: 176px;
  padding: 6px;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.98), rgba(11, 18, 32, 0.96));
  border: 1px solid rgba(255,255,255,.14);
  border-radius: 12px;
  box-shadow: 0 20px 48px rgba(0,0,0,.55), inset 0 1px 0 rgba(255,255,255,0.05);
  backdrop-filter: blur(12px) saturate(140%);
  -webkit-backdrop-filter: blur(12px) saturate(140%);
}
.ctx-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,.85);
  font: inherit;
  font-size: 12px;
  text-align: left;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease;
  letter-spacing: 0.15px;
}
.ctx-item:hover { background: rgba(255,255,255,.07); }
.ctx-danger { color: #ff8a6f; }
.ctx-danger:hover { background: rgba(255,102,68,.12); }
.ctx-icon { width: 14px; text-align: center; opacity: .85; }
.ctx-hint { margin-left: auto; font-size: 10px; color: rgba(255,255,255,.35); font-family: 'JetBrains Mono', monospace; }
.move-menu { max-height: 260px; overflow-y: auto; min-width: 184px; }
.ctx-title {
  padding: 4px 10px 6px;
  font-size: 10px;
  letter-spacing: 0.4px;
  color: rgba(255,255,255,.42);
  border-bottom: 1px solid rgba(255,255,255,.08);
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ctx-move-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
