<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
import { confirm as uiConfirm } from '../composables/useConfirm';
import { prompt as uiPrompt } from '../composables/usePrompt';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { Workspace } from '../api/workspaces';

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
const ctxMenu = ref<null | {
  kind: 'conversation' | 'graph' | 'datasource-section';
  id: string;
  title: string;
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
  }
}, { immediate: true });

onBeforeUnmount(() => closeCtxMenu());

const wsInitial = (name: string) => {
  const trimmed = (name || '').trim();
  return trimmed ? trimmed.charAt(0) : '?';
};

const closeCtxMenu = () => {
  ctxMenu.value = null;
};

const openCtxMenu = (
  kind: 'conversation' | 'graph' | 'datasource-section',
  id: string,
  title: string,
  e: MouseEvent,
) => {
  e.preventDefault();
  e.stopPropagation();
  ctxMenu.value = {
    kind,
    id,
    title,
    x: Math.min(e.clientX, window.innerWidth - 220),
    y: Math.min(e.clientY, window.innerHeight - 132),
  };
};

const openDataSourceSection = () => {
  emit('open-datasources', props.workspace.id);
};

const addDataSourceToWorkspace = () => {
  closeCtxMenu();
  emit('add-datasource', props.workspace.id);
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
watch(ctxMenu, (v, prev) => {
  if (!prev && v) window.addEventListener('mousedown', onGlobalMouseDown, { once: true });
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
          <span v-if="tree.isLoadingDS(workspace.id)" class="ws-loading">加载中...</span>
          <template v-else-if="tree.getDataSources(workspace.id).length">
            <button
              v-for="d in tree.getDataSources(workspace.id)"
              :key="d.id"
              class="ws-item"
              @click.stop="emit('open-datasource', d.id)"
              :title="d.name"
            >
              <span class="ws-item-label">{{ d.name }}</span>
              <span class="ws-item-kind">{{ kindLabel[d.kind] || d.kind }}</span>
            </button>
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
      <button v-if="ctxMenu.kind === 'datasource-section'" class="ctx-item" @click="openDataSourceSection(); closeCtxMenu()">
        <span class="ctx-icon">◈</span>
        <span>查看数据源</span>
        <span class="ctx-hint">Open</span>
      </button>
      <button v-if="ctxMenu.kind === 'datasource-section'" class="ctx-item" @click="addDataSourceToWorkspace">
        <span class="ctx-icon">＋</span>
        <span>添加数据源</span>
        <span class="ctx-hint">Add</span>
      </button>
      <button v-if="ctxMenu.kind !== 'datasource-section'" class="ctx-item" @click="renameItem">
        <span class="ctx-icon">✎</span>
        <span>重新命名</span>
        <span class="ctx-hint">Rename</span>
      </button>
      <button v-if="ctxMenu.kind !== 'datasource-section'" class="ctx-item ctx-danger" @click="deleteItem">
        <span class="ctx-icon">🗑</span>
        <span>删除</span>
        <span class="ctx-hint">Delete</span>
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
</style>
