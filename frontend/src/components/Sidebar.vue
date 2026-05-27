<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
import { confirm as uiConfirm } from '../composables/useConfirm';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { Workspace } from '../api/workspaces';

const props = defineProps<{
  expanded: boolean;
  view: string;
}>();

const emit = defineEmits<{
  (e: 'toggle'): void;
  (e: 'nav', route: string): void;
  (e: 'open-conversation', id: string): void;
  (e: 'new-conversation'): void;
  (e: 'switch-workspace', id: string): void;
}>();

const a = ref(0);
const items = [
  ['✦', '新对话', 'welcome'],
  ['◈', '本体模型', 'list']
];

const onPick = (i: number, route: string) => {
  a.value = i;
  emit('nav', route);
};

// ---------- 工作空间 ----------
const ws = useWorkspaces();
const wsExpanded = ref(true);
const showCreate = ref(false);
const creating = ref(false);
const newName = ref('');

// ---------- 树形:对话记录 / 数据源 ----------
const tree = useSidebarTree();
const convExpanded = ref(true);
const dsExpanded = ref(false);

onMounted(async () => {
  if (ws.workspaces.value.length === 0) {
    try { await ws.reload(); } catch { /* noop */ }
  }
  // 异步初次加载;失败也不阻塞 UI
  tree.loadConversations().catch(() => { /* noop */ });
  tree.loadDataSources().catch(() => { /* noop */ });
});

const sortedWorkspaces = computed(() => {
  return [...ws.workspaces.value].sort((x, y) => {
    if ((x.isDefault ? 1 : 0) !== (y.isDefault ? 1 : 0)) return (y.isDefault ? 1 : 0) - (x.isDefault ? 1 : 0);
    return (y.updatedAt || 0) - (x.updatedAt || 0);
  });
});

const wsInitial = (name: string) => {
  const trimmed = (name || '').trim();
  if (!trimmed) return '?';
  return trimmed.charAt(0);
};

const switchWs = async (w: Workspace) => {
  if (w.id === ws.currentId.value) return;
  const ok = await uiConfirm({
    title: '切换工作空间',
    message: `将切换到「${w.name}」。当前未保存的图谱编辑会立即提交,列表与对话会重新加载。`,
    confirmLabel: '切换',
  });
  if (!ok) return;
  ws.setCurrent(w.id);
  emit('switch-workspace', w.id);
};

const openCreate = () => {
  showCreate.value = true;
  newName.value = '';
};

const submitCreate = async () => {
  const name = newName.value.trim();
  if (!name || creating.value) return;
  creating.value = true;
  try {
    const w = await ws.create({ name });
    showCreate.value = false;
    ws.setCurrent(w.id);
    emit('switch-workspace', w.id);
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '创建失败');
  } finally {
    creating.value = false;
  }
};

// ---------- 对话/数据源操作 ----------
const onOpenConv = (id: string) => emit('open-conversation', id);

const onDeleteConv = async (id: string, title: string, e: Event) => {
  e.stopPropagation();
  const ok = await uiConfirm({
    title: '删除对话',
    message: `确定删除对话「${title}」?`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await tree.removeConversation(id);
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
};

const onDeleteDataSource = async (id: string, name: string, e: Event) => {
  e.stopPropagation();
  const ok = await uiConfirm({
    title: '删除数据源',
    message: `确定删除「${name}」?该操作仅清理记录,不影响已并入的图谱节点。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await tree.removeDataSource(id);
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
};

const refreshTree = () => {
  tree.loadConversations().catch(() => { /* noop */ });
  tree.loadDataSources().catch(() => { /* noop */ });
};

const formatBytes = (bytes?: number): string => {
  if (!bytes || bytes <= 0) return '';
  if (bytes < 1024) return bytes + 'B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'K';
  return (bytes / 1024 / 1024).toFixed(1) + 'M';
};

const dataSourceIcon = (kind: string) => kind === 'url' ? '🔗' : '📄';
</script>

<template>
  <div :class="['sidebar', { exp: expanded }]">
    <div class="sidebar-logo" @click="emit('toggle')" style="cursor:pointer" :title="expanded ? '收起侧栏' : '展开侧栏'">
      <div class="logo-mark">推</div>
      <div class="logo-text">推演平台</div>
    </div>
    <button v-for="(item, i) in items" :key="i"
            :class="['sb-item', { active: (item[2] === 'welcome' && view === 'welcome') || (item[2] === 'list' && view === 'list') }]"
            @click="onPick(i, item[2] as string)">
      <span class="sb-icon">{{ item[0] }}</span><span class="sb-item-label">{{ item[1] }}</span>
    </button>

    <!-- 工作空间 -->
    <div v-if="expanded" class="sb-ws">
      <button class="sb-section-head" @click="wsExpanded = !wsExpanded">
        <span class="sb-section-label">工作空间</span>
        <span :class="['sb-caret', { open: wsExpanded }]">▸</span>
      </button>
      <div v-if="wsExpanded" class="sb-ws-list">
        <div v-for="w in sortedWorkspaces" :key="w.id"
             :class="['sb-ws-item', { active: w.id === ws.currentId.value }]"
             :title="w.name + (w.description ? ' · ' + w.description : '')"
             @click="switchWs(w)">
          <span class="sb-ws-avatar">{{ wsInitial(w.name) }}</span>
          <span class="sb-ws-name">{{ w.name }}</span>
          <span v-if="w.isDefault" class="sb-ws-tag">默认</span>
          <span v-if="w.id === ws.currentId.value" class="sb-ws-dot" />
        </div>
        <button class="sb-ws-new" @click="openCreate" title="新建工作空间">
          <span class="sb-ws-avatar plus">＋</span>
          <span class="sb-ws-name">新建工作空间</span>
        </button>
      </div>
    </div>

    <!-- 树形:对话记录 / 数据源 -->
    <div v-if="expanded" class="sb-tree">
      <!-- 对话记录 -->
      <div class="sb-tree-node">
        <button class="sb-tree-head" @click="convExpanded = !convExpanded">
          <span :class="['sb-caret', { open: convExpanded }]">▸</span>
          <span class="sb-tree-icon">💬</span>
          <span class="sb-tree-label">对话记录</span>
          <span v-if="tree.conversations.value.length" class="sb-tree-count">{{ tree.conversations.value.length }}</span>
          <span class="sb-tree-spacer" />
          <button class="sb-tree-action" :title="'新对话'" @click.stop="emit('new-conversation')">＋</button>
          <button class="sb-tree-action" :title="'刷新'" @click.stop="refreshTree">↻</button>
        </button>
        <div v-if="convExpanded" class="sb-tree-list">
          <div v-if="tree.loadingConvs.value && !tree.conversations.value.length" class="sb-tree-empty">加载中…</div>
          <div v-else-if="!tree.conversations.value.length" class="sb-tree-empty">暂无对话</div>
          <div v-for="c in tree.conversations.value" :key="c.id"
               class="sb-tree-item"
               :title="c.title"
               @click="onOpenConv(c.id)">
            <span class="sb-tree-dot" />
            <span class="sb-tree-item-title">{{ c.title }}</span>
            <button class="sb-tree-del" :title="`删除 ${c.title}`"
                    @click="(e) => onDeleteConv(c.id, c.title, e)">×</button>
          </div>
        </div>
      </div>

      <!-- 数据源 -->
      <div class="sb-tree-node">
        <button class="sb-tree-head" @click="dsExpanded = !dsExpanded">
          <span :class="['sb-caret', { open: dsExpanded }]">▸</span>
          <span class="sb-tree-icon">📂</span>
          <span class="sb-tree-label">数据源</span>
          <span v-if="tree.dataSources.value.length" class="sb-tree-count">{{ tree.dataSources.value.length }}</span>
        </button>
        <div v-if="dsExpanded" class="sb-tree-list">
          <div v-if="tree.loadingDataSources.value && !tree.dataSources.value.length" class="sb-tree-empty">加载中…</div>
          <div v-else-if="!tree.dataSources.value.length" class="sb-tree-empty">还没有导入文档</div>
          <div v-for="d in tree.dataSources.value" :key="d.id"
               class="sb-tree-item"
               :title="d.name + (d.size ? ' · ' + formatBytes(d.size) : '')">
            <span class="sb-tree-icon-mini">{{ dataSourceIcon(d.kind) }}</span>
            <span class="sb-tree-item-title">{{ d.name }}</span>
            <span class="sb-tree-meta">{{ formatBytes(d.size) }}</span>
            <button class="sb-tree-del" :title="`删除 ${d.name}`"
                    @click="(e) => onDeleteDataSource(d.id, d.name, e)">×</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="!expanded" class="sb-spacer" />
    <button :class="['sb-item', { active: view === 'settings' }]" @click="emit('nav', 'settings')">
      <span class="sb-icon">⚙</span><span class="sb-item-label">设置</span>
    </button>

    <!-- 新建工作空间弹层 -->
    <div v-if="showCreate" class="sb-mask" @click.self="showCreate = false">
      <div class="sb-dialog">
        <h3>新建工作空间</h3>
        <input
          v-model="newName"
          placeholder="工作空间名称"
          maxlength="120"
          autofocus
          @keydown.enter="submitCreate"
          @keydown.escape="showCreate = false"
        />
        <div class="sb-dialog-actions">
          <button class="sb-btn-cancel" @click="showCreate = false">取消</button>
          <button class="sb-btn-primary" :disabled="!newName.trim() || creating" @click="submitCreate">
            {{ creating ? '创建中…' : '创建并进入' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 工作空间区块 ---------- */
.sb-ws {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
}
.sb-section-head {
  background: none;
  border: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px 6px;
  cursor: pointer;
  color: inherit;
  font-family: inherit;
}
.sb-caret {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.35);
  transition: transform .18s;
}
.sb-caret.open {
  transform: rotate(90deg);
}
.sb-ws-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 6px;
  max-height: 220px;
  overflow-y: auto;
}
.sb-ws-list::-webkit-scrollbar { width: 4px; }
.sb-ws-list::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 4px; }
.sb-ws-item,
.sb-ws-new {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-dim);
  background: none;
  border: none;
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  width: 100%;
  transition: all 0.12s;
}
.sb-ws-item:hover,
.sb-ws-new:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.sb-ws-item.active {
  background: rgba(66, 184, 131, 0.12);
  color: #42b883;
}
.sb-ws-avatar {
  width: 22px; height: 22px;
  flex-shrink: 0;
  border-radius: 6px;
  background: linear-gradient(135deg, #3d9bff, #6366f1);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  font-family: 'Inter', sans-serif;
}
.sb-ws-item.active .sb-ws-avatar {
  background: linear-gradient(135deg, #42b883, #2d9b6e);
  box-shadow: 0 0 0 1px rgba(66, 184, 131, 0.45);
}
.sb-ws-avatar.plus {
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.55);
  border: 1px dashed rgba(255, 255, 255, 0.18);
}
.sb-ws-name {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-ws-tag {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 100px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.45);
  flex-shrink: 0;
}
.sb-ws-item.active .sb-ws-tag {
  background: rgba(66, 184, 131, 0.18);
  color: #6dd4a7;
}
.sb-ws-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #42b883;
  box-shadow: 0 0 6px #42b883;
  flex-shrink: 0;
}
.sb-section-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1.2px;
  color: rgba(255, 255, 255, 0.35);
  font-family: 'Inter', sans-serif;
  font-weight: 500;
}

/* ---------- 树形:对话记录 / 数据源 ---------- */
.sb-tree {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.sb-tree::-webkit-scrollbar { width: 4px; }
.sb-tree::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 4px; }
.sb-tree-node {
  display: flex;
  flex-direction: column;
}
.sb-tree-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: none;
  border: none;
  color: var(--text-dim);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  text-align: left;
  width: 100%;
  transition: all 0.12s;
}
.sb-tree-head:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-main);
}
.sb-tree-icon {
  font-size: 13px;
  flex-shrink: 0;
}
.sb-tree-label {
  font-weight: 500;
  flex-shrink: 0;
}
.sb-tree-count {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 100px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.5);
  font-family: 'JetBrains Mono', monospace;
  flex-shrink: 0;
}
.sb-tree-spacer {
  flex: 1;
}
.sb-tree-action {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.4);
  font-size: 12px;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  cursor: pointer;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 0;
  font-family: inherit;
  flex-shrink: 0;
}
.sb-tree-head:hover .sb-tree-action { display: flex; }
.sb-tree-action:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-main);
}
.sb-tree-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 0 6px 4px 22px;
}
.sb-tree-empty {
  padding: 6px 10px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
}
.sb-tree-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  border-radius: 6px;
  text-align: left;
  width: 100%;
  overflow: hidden;
  transition: all 0.1s;
}
.sb-tree-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.sb-tree-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
  opacity: 0.5;
}
.sb-tree-icon-mini {
  font-size: 11px;
  flex-shrink: 0;
  opacity: 0.7;
}
.sb-tree-item-title {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-tree-meta {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.3);
  font-family: 'JetBrains Mono', monospace;
  flex-shrink: 0;
}
.sb-tree-del {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.3);
  font-size: 14px;
  line-height: 1;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  cursor: pointer;
  flex-shrink: 0;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 0;
  font-family: inherit;
}
.sb-tree-item:hover .sb-tree-del { display: flex; }
.sb-tree-item:hover .sb-tree-meta { display: none; }
.sb-tree-del:hover {
  background: rgba(255, 102, 68, 0.2);
  color: #ff8a6f;
}

/* ---------- 新建对话框 ---------- */
.sb-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1500;
}
.sb-dialog {
  background: #141e30;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 14px;
  padding: 22px;
  width: 360px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.sb-dialog h3 { margin: 0; font-size: 16px; color: var(--text-main); }
.sb-dialog input {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #e2e8f0;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
}
.sb-dialog input:focus { border-color: #42b883; }
.sb-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.sb-btn-primary {
  background: #42b883;
  color: #002418;
  border: none;
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
}
.sb-btn-primary:hover { background: #50caa3; }
.sb-btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }
.sb-btn-cancel {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.65);
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
}
.sb-btn-cancel:hover { background: rgba(255, 255, 255, 0.1); }
</style>
