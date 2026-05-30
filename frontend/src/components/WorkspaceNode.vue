<script setup lang="ts">
import { ref, watch } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
import { confirm as uiConfirm } from '../composables/useConfirm';
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
}>();

const ws = useWorkspaces();
const tree = useSidebarTree();

const expanded = ref(props.isCurrent);

watch(() => props.isCurrent, (v) => { if (v) expanded.value = true; });

watch(expanded, (v) => {
  if (v) {
    tree.loadConversations(props.workspace.id);
    tree.loadOntologies(props.workspace.id);
    tree.loadDataSources(props.workspace.id);
  }
}, { immediate: true });

const wsInitial = (name: string) => {
  const trimmed = (name || '').trim();
  return trimmed ? trimmed.charAt(0) : '?';
};

const toggle = async (e: Event) => {
  e.stopPropagation();
  if (!props.isCurrent) {
    const ok = await uiConfirm({
      title: '切换工作空间',
      message: `将切换到「${props.workspace.name}」。当前未保存的图谱编辑会立即提交，列表与对话会重新加载。`,
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
    ? '这是最后一个工作空间，删除后将回到工作空间选择页。'
    : props.workspace.isDefault
      ? '这是默认工作空间，删除后将自动把另一个工作空间设为默认。'
      : '';
  const ok = await uiConfirm({
    title: '删除工作空间',
    message: `「${props.workspace.name}」内的本体图、推演分支、对话与数据源将一并清空，且无法恢复。${extraNote}确定继续吗？`,
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
  mysql: 'MySQL', pgsql: 'PgSQL', file_stored: '文件', https_api: 'HTTPS',
};
</script>

<template>
  <div class="ws-wrap">
    <!-- workspace header row -->
    <div :class="['ws-node', { active: isCurrent }]" @click="toggle">
      <span class="ws-caret" :class="{ open: expanded }">▸</span>
      <span class="ws-avatar">{{ wsInitial(workspace.name) }}</span>
      <span class="ws-name">{{ workspace.name }}</span>
      <span v-if="workspace.isDefault" class="ws-tag">默认</span>
      <span v-if="isCurrent" class="ws-dot" title="当前工作空间" />
      <button class="ws-del-btn" :title="`删除 ${workspace.name}`" @click="onClickDelete">×</button>
    </div>

    <!-- expanded children -->
    <div v-if="expanded" class="ws-children">

      <!-- 历史对话 -->
      <div class="ws-section">
        <span class="ws-section-lbl">💬 历史对话</span>
        <span v-if="tree.isLoadingConv(workspace.id)" class="ws-loading">…</span>
        <template v-else-if="tree.getConversations(workspace.id).length">
          <button
            v-for="c in tree.getConversations(workspace.id).slice(0, 10)"
            :key="c.id"
            class="ws-item"
            @click.stop="emit('open-conversation', c.id)"
            :title="c.title"
          >
            <span class="ws-item-label">{{ c.title || '新对话' }}</span>
            <span v-if="c.updatedAt" class="ws-item-time">{{ fmtTime(c.updatedAt) }}</span>
          </button>
        </template>
        <span v-else class="ws-empty">暂无对话</span>
      </div>

      <!-- 血缘图 -->
      <div class="ws-section">
        <span class="ws-section-lbl">◈ 血缘图</span>
        <span v-if="tree.isLoadingOntology(workspace.id)" class="ws-loading">…</span>
        <template v-else-if="tree.getOntologies(workspace.id).length">
          <button
            v-for="m in tree.getOntologies(workspace.id)"
            :key="m.id"
            class="ws-item"
            @click.stop="emit('open-graph', m.id)"
            :title="m.name"
          >
            <span class="ws-item-label">{{ m.name }}</span>
            <span v-if="m.updatedAt" class="ws-item-time">{{ fmtTime(m.updatedAt) }}</span>
          </button>
        </template>
        <span v-else class="ws-empty">暂无图谱</span>
      </div>

      <!-- 数据源 -->
      <div class="ws-section">
        <span class="ws-section-lbl">📄 数据源</span>
        <span v-if="tree.isLoadingDS(workspace.id)" class="ws-loading">…</span>
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
      </div>

    </div>
  </div>
</template>

<style scoped>
.ws-wrap { display: flex; flex-direction: column; }
.ws-node {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; border-radius: 8px; cursor: pointer;
  color: var(--text-dim); font-size: 12.5px; transition: all .12s;
}
.ws-node:hover { background: rgba(255,255,255,.05); color: var(--text-main); }
.ws-node.active { background: rgba(66,184,131,.12); color: #42b883; }
.ws-caret {
  font-size: 9px; color: rgba(255,255,255,.35);
  transition: transform .18s; flex-shrink: 0;
}
.ws-caret.open { transform: rotate(90deg); }
.ws-avatar {
  width: 22px; height: 22px; flex-shrink: 0; border-radius: 6px;
  background: linear-gradient(135deg,#3d9bff,#6366f1); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600; font-family: 'Inter',sans-serif;
}
.ws-node.active .ws-avatar {
  background: linear-gradient(135deg,#42b883,#2d9b6e);
  box-shadow: 0 0 0 1px rgba(66,184,131,.45);
}
.ws-name { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ws-tag {
  font-size: 9px; padding: 1px 6px; border-radius: 100px;
  background: rgba(255,255,255,.06); color: rgba(255,255,255,.45); flex-shrink: 0;
}
.ws-node.active .ws-tag { background: rgba(66,184,131,.18); color: #6dd4a7; }
.ws-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: #42b883; box-shadow: 0 0 6px #42b883; flex-shrink: 0;
}
.ws-del-btn {
  background: transparent; border: none; color: rgba(255,255,255,.35);
  font-size: 14px; line-height: 1; width: 18px; height: 18px;
  border-radius: 50%; cursor: pointer; font-family: inherit;
  display: flex; align-items: center; justify-content: center; padding: 0; flex-shrink: 0;
}
.ws-del-btn:hover { background: rgba(255,102,68,.2); color: #ff8a6f; }
/* children */
.ws-children { display: flex; flex-direction: column; gap: 2px; margin: 2px 0 4px; }
.ws-section { display: flex; flex-direction: column; gap: 0; }
.ws-section-lbl {
  font-size: 10.5px; letter-spacing: .4px;
  color: rgba(255,255,255,.4); padding: 5px 10px 3px 24px;
  font-family:'Inter',sans-serif; font-weight: 500; user-select: none;
}
.ws-loading { font-size: 11px; color: rgba(255,255,255,.3); padding: 2px 10px 2px 28px; }
.ws-empty { font-size: 11px; color: rgba(255,255,255,.22); padding: 2px 10px 4px 28px; font-style: italic; }
.ws-item {
  display: flex; align-items: center; gap: 6px;
  padding: 3px 10px 3px 28px; border-radius: 0; cursor: pointer;
  background: none; border: none; color: rgba(255,255,255,.55);
  font-size: 12px; text-align: left; width: 100%; font-family: inherit;
  transition: background .1s;
}
.ws-item:hover { background: rgba(255,255,255,.05); color: #e8eaed; }
.ws-item-label { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ws-item-time { font-size: 10px; color: rgba(255,255,255,.28); flex-shrink: 0; }
.ws-item-kind {
  font-size: 9px; padding: 1px 5px; border-radius: 100px; flex-shrink: 0;
  background: rgba(255,255,255,.06); color: rgba(255,255,255,.35);
}
</style>
