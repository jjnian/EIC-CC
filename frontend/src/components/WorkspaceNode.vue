<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { useSidebarTree } from '../composables/useSidebarTree';
import { confirm as uiConfirm } from '../composables/useConfirm';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { Workspace } from '../api/workspaces';

const props = defineProps<{
  workspace: Workspace;
  isCurrent: boolean;
  expanded: boolean;
}>();

const emit = defineEmits<{
  (e: 'toggle-expand', id: string): void;
  (e: 'switch-current', id: string): void;
  (e: 'open-conversation', wsId: string, convId: string): void;
  (e: 'new-conversation', wsId: string): void;
  (e: 'open-data-source', id: string): void;
  (e: 'open-create-data-source'): void;
}>();

const tree = useSidebarTree();

// 二级展开状态:对话/数据源各自独立,初始收起
const convExpanded = ref(false);
const dsExpanded = ref(false);

const conversations = computed(() => tree.getConversations(props.workspace.id));
const dataSources = computed(() => tree.getDataSources(props.workspace.id));
const loadingConv = computed(() => tree.isLoadingConv(props.workspace.id));
const loadingDS = computed(() => tree.isLoadingDS(props.workspace.id));
const loadedConv = computed(() => tree.isLoadedConv(props.workspace.id));
const loadedDS = computed(() => tree.isLoadedDS(props.workspace.id));

// 顶级收起时一并收起二级,避免下次再次展开仍残留旧状态
watch(() => props.expanded, (val) => {
  if (!val) {
    convExpanded.value = false;
    dsExpanded.value = false;
  }
});

const wsInitial = (name: string) => {
  const trimmed = (name || '').trim();
  if (!trimmed) return '?';
  return trimmed.charAt(0);
};

const onToggleTop = () => {
  emit('toggle-expand', props.workspace.id);
};

const onClickSwitch = async (e: Event) => {
  e.stopPropagation();
  if (props.isCurrent) return;
  const ok = await uiConfirm({
    title: '切换工作空间',
    message: `将切换到「${props.workspace.name}」。当前未保存的图谱编辑会立即提交，列表与对话会重新加载。`,
    confirmLabel: '切换',
  });
  if (!ok) return;
  emit('switch-current', props.workspace.id);
};

const onToggleConv = () => {
  convExpanded.value = !convExpanded.value;
  if (convExpanded.value && !loadedConv.value) {
    tree.loadConversations(props.workspace.id).catch(() => { /* noop */ });
  }
};

const onToggleDS = () => {
  dsExpanded.value = !dsExpanded.value;
  if (dsExpanded.value && !loadedDS.value) {
    tree.loadDataSources(props.workspace.id).catch(() => { /* noop */ });
  }
};

const refreshConv = (e: Event) => {
  e.stopPropagation();
  tree.loadConversations(props.workspace.id, true).catch(() => { /* noop */ });
};

const onNewConv = (e: Event) => {
  e.stopPropagation();
  emit('new-conversation', props.workspace.id);
};

const onClickConv = async (id: string) => {
  // 当前工作空间直接打开;非当前则先切换
  if (props.isCurrent) {
    emit('open-conversation', props.workspace.id, id);
    return;
  }
  const ok = await uiConfirm({
    title: '打开其他工作空间的对话',
    message: `将切换到「${props.workspace.name}」并打开该对话。`,
    confirmLabel: '切换并打开',
  });
  if (!ok) return;
  emit('switch-current', props.workspace.id);
  // 切换后由父组件等待重新加载完成,这里再发一次 open
  // 简化处理:直接发出,父组件自行决定时序
  emit('open-conversation', props.workspace.id, id);
};

const onDeleteConv = async (id: string, title: string, e: Event) => {
  e.stopPropagation();
  const ok = await uiConfirm({
    title: '删除对话',
    message: `确定删除对话「${title}」？`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await tree.removeConversation(props.workspace.id, id);
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
};

const onDeleteDS = async (id: string, name: string, e: Event) => {
  e.stopPropagation();
  const ok = await uiConfirm({
    title: '删除数据源',
    message: `确定删除「${name}」？该操作仅清理记录，不影响已并入的图谱节点。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await tree.removeDataSource(props.workspace.id, id);
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
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
  <div class="ws-node">
    <!-- 顶级行:caret + 头像 + 名称 + 默认徽章 + 切换按钮 -->
    <div :class="['ws-head', { active: isCurrent }]" @click="onToggleTop">
      <span :class="['ws-caret', { open: expanded }]">▸</span>
      <span class="ws-avatar">{{ wsInitial(workspace.name) }}</span>
      <span class="ws-name">{{ workspace.name }}</span>
      <span v-if="workspace.isDefault" class="ws-tag">默认</span>
      <button v-if="!isCurrent"
              class="ws-switch-btn"
              :title="`切换到 ${workspace.name}`"
              @click="onClickSwitch">切换</button>
      <span v-else class="ws-dot" title="当前工作空间" />
    </div>

    <!-- 二级:对话记录 + 数据源 -->
    <div v-if="expanded" class="ws-children">
      <!-- 对话记录 -->
      <div class="ws-child-node">
        <div class="ws-child-head" role="button" tabindex="0"
             @click="onToggleConv"
             @keydown.enter.prevent="onToggleConv"
             @keydown.space.prevent="onToggleConv">
          <span :class="['ws-caret', { open: convExpanded }]">▸</span>
          <span class="ws-child-icon">💬</span>
          <span class="ws-child-label">对话记录</span>
          <span v-if="conversations.length" class="ws-child-count">{{ conversations.length }}</span>
          <span class="ws-child-spacer" />
          <button v-if="isCurrent" class="ws-child-action" :title="'新对话'" @click.stop="onNewConv">＋</button>
          <button class="ws-child-action" :title="'刷新'" @click.stop="refreshConv">↻</button>
        </div>
        <div v-if="convExpanded" class="ws-child-list">
          <div v-if="loadingConv && !conversations.length" class="ws-child-empty">加载中…</div>
          <div v-else-if="loadedConv && !conversations.length" class="ws-child-empty">暂无对话</div>
          <div v-else-if="!loadedConv" class="ws-child-empty">点击 ↻ 加载</div>
          <div v-for="c in conversations" :key="c.id"
               class="ws-child-item"
               :title="c.title"
               @click="onClickConv(c.id)">
            <span class="ws-child-dot" />
            <span class="ws-child-item-title">{{ c.title }}</span>
            <button class="ws-child-del" :title="`删除 ${c.title}`"
                    @click="(e) => onDeleteConv(c.id, c.title, e)">×</button>
          </div>
        </div>
      </div>

      <!-- 数据源 -->
      <div class="ws-child-node">
        <button class="ws-child-head" @click="onToggleDS">
          <span :class="['ws-caret', { open: dsExpanded }]">▸</span>
          <span class="ws-child-icon">📂</span>
          <span class="ws-child-label">数据源</span>
          <span v-if="dataSources.length" class="ws-child-count">{{ dataSources.length }}</span>
          <span class="ws-child-spacer" />
          <button class="ws-child-action" title="添加数据源" @click.stop="emit('open-create-data-source')">＋</button>
        </button>
        <div v-if="dsExpanded" class="ws-child-list">
          <div v-if="loadingDS && !dataSources.length" class="ws-child-empty">加载中…</div>
          <div v-else-if="loadedDS && !dataSources.length" class="ws-child-empty">还没有导入文档</div>
          <div v-else-if="!loadedDS" class="ws-child-empty">展开自动加载</div>
          <div v-for="d in dataSources" :key="d.id"
               class="ws-child-item"
               :title="d.name + (d.size ? ' · ' + formatBytes(d.size) : '')"
               @click="if (['mysql','pgsql','file_stored','https_api'].includes(String(d.kind))) emit('open-data-source', String(d.id))">
            <span class="ws-child-icon-mini">{{ ({ mysql:'🗄', pgsql:'🐘', file_stored:'📄', https_api:'🌐', file:'📎', url:'🔗' } as Record<string,string>)[d.kind] || '📁' }}</span>
            <span class="ws-child-item-title">{{ d.name }}</span>
            <span v-if="['mysql','pgsql','file_stored','https_api'].includes(String(d.kind))" :class="['status-dot', String((d as any).status || 'idle')]" />
            <span class="ws-child-meta">{{ formatBytes(d.size) }}</span>
            <button class="ws-child-del" :title="`删除 ${d.name}`"
                    @click="(e) => onDeleteDS(d.id, d.name, e)">×</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ws-node {
  display: flex;
  flex-direction: column;
}

/* ---------- 顶级行 ---------- */
.ws-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-dim);
  font-size: 12.5px;
  transition: all .12s;
}
.ws-head:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.ws-head.active {
  background: rgba(66, 184, 131, 0.12);
  color: #42b883;
}
.ws-caret {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.35);
  transition: transform .18s;
  flex-shrink: 0;
  width: 9px;
}
.ws-caret.open {
  transform: rotate(90deg);
}
.ws-avatar {
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
.ws-head.active .ws-avatar {
  background: linear-gradient(135deg, #42b883, #2d9b6e);
  box-shadow: 0 0 0 1px rgba(66, 184, 131, 0.45);
}
.ws-name {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ws-tag {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 100px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.45);
  flex-shrink: 0;
}
.ws-head.active .ws-tag {
  background: rgba(66, 184, 131, 0.18);
  color: #6dd4a7;
}
.ws-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #42b883;
  box-shadow: 0 0 6px #42b883;
  flex-shrink: 0;
}
.ws-switch-btn {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.55);
  padding: 2px 8px;
  border-radius: 100px;
  font-size: 10px;
  cursor: pointer;
  font-family: inherit;
  display: none;
  flex-shrink: 0;
}
.ws-head:hover .ws-switch-btn { display: inline-block; }
.ws-switch-btn:hover {
  background: rgba(66, 184, 131, 0.18);
  color: #6dd4a7;
  border-color: rgba(66, 184, 131, 0.3);
}

/* ---------- 二级容器 ---------- */
.ws-children {
  margin-left: 8px;
  padding-left: 8px;
  border-left: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  margin-top: 2px;
  margin-bottom: 4px;
}
.ws-child-node {
  display: flex;
  flex-direction: column;
}
.ws-child-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: none;
  border: none;
  color: var(--text-dim);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  text-align: left;
  width: 100%;
  border-radius: 6px;
  transition: all 0.12s;
}
.ws-child-head:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-main);
}
.ws-child-icon {
  font-size: 12px;
  flex-shrink: 0;
}
.ws-child-label {
  font-weight: 500;
  flex-shrink: 0;
}
.ws-child-count {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 100px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.5);
  font-family: 'JetBrains Mono', monospace;
  flex-shrink: 0;
}
.ws-child-spacer {
  flex: 1;
}
.ws-child-action {
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
.ws-child-head:hover .ws-child-action { display: flex; }
.ws-child-action:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-main);
}
.ws-child-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 0 4px 4px 18px;
}
.ws-child-empty {
  padding: 5px 10px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
}
.ws-child-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
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
.ws-child-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.ws-child-dot {
  width: 5px; height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
  opacity: 0.5;
}
.ws-child-icon-mini {
  font-size: 11px;
  flex-shrink: 0;
  opacity: 0.7;
}
.ws-child-item-title {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ws-child-meta {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.3);
  font-family: 'JetBrains Mono', monospace;
  flex-shrink: 0;
}
.ws-child-del {
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
.ws-child-item:hover .ws-child-del { display: flex; }
.ws-child-item:hover .ws-child-meta { display: none; }
.ws-child-del:hover {
  background: rgba(255, 102, 68, 0.2);
  color: #ff8a6f;
}

/* 数据源连接状态指示点 */
.status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-left: 6px; vertical-align: middle; flex-shrink: 0; }
.status-dot.idle { background: #888; }
.status-dot.connected { background: #22dd88; }
.status-dot.error { background: tomato; }
</style>
