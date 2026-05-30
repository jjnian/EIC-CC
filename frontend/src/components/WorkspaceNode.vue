<script setup lang="ts">
import { useWorkspaces } from '../composables/useWorkspaces';
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
}>();

const ws = useWorkspaces();

const wsInitial = (name: string) => {
  const trimmed = (name || '').trim();
  return trimmed ? trimmed.charAt(0) : '?';
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
    // 删光、切换了当前空间、或删的是默认空间，都整页刷新以重置视图（删光时回到选择页）。
    if (res.switchedTo || wasDefault || ws.workspaces.value.length === 0) window.location.reload();
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
};
</script>

<template>
  <div :class="['ws-node', { active: isCurrent }]" @click="onClickSwitch">
    <span class="ws-avatar">{{ wsInitial(workspace.name) }}</span>
    <span class="ws-name">{{ workspace.name }}</span>
    <span v-if="workspace.isDefault" class="ws-tag">默认</span>
    <span v-if="isCurrent" class="ws-dot" title="当前工作空间" />
    <button v-else class="ws-switch-btn" :title="`切换到 ${workspace.name}`" @click="onClickSwitch">切换</button>
    <button class="ws-del-btn" :title="`删除 ${workspace.name}`" @click="onClickDelete">×</button>
  </div>
</template>

<style scoped>
.ws-node {
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
.ws-node:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.ws-node.active {
  background: rgba(66, 184, 131, 0.12);
  color: #42b883;
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
.ws-node.active .ws-avatar {
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
.ws-node.active .ws-tag {
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
.ws-node:hover .ws-switch-btn { display: inline-block; }
.ws-switch-btn:hover {
  background: rgba(66, 184, 131, 0.18);
  color: #6dd4a7;
  border-color: rgba(66, 184, 131, 0.3);
}
.ws-del-btn {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.35);
  font-size: 14px;
  line-height: 1;
  width: 18px; height: 18px;
  border-radius: 50%;
  cursor: pointer;
  font-family: inherit;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
}
.ws-del-btn:hover {
  background: rgba(255, 102, 68, 0.2);
  color: #ff8a6f;
}
</style>
