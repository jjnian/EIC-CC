<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import WorkspaceNode from './WorkspaceNode.vue';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';

const props = defineProps<{
  expanded: boolean;
  view: string;
}>();

const emit = defineEmits<{
  (e: 'toggle'): void;
  (e: 'nav', route: string): void;
  (e: 'switch-workspace', id: string): void;
  (e: 'open-conversation', id: string): void;
  (e: 'open-graph', id: string): void;
  (e: 'open-datasource', id: string): void;
  (e: 'open-datasources', workspaceId: string): void;
  (e: 'add-datasource', workspaceId: string): void;
  (e: 'open-experience', id: string): void;
  (e: 'open-experiences', workspaceId: string): void;
  (e: 'add-experience', workspaceId: string): void;
  (e: 'rename-conversation', id: string, title: string): void;
  (e: 'delete-conversation', id: string): void;
  (e: 'rename-graph', id: string, title: string): void;
  (e: 'delete-graph', id: string): void;
}>();

// 左侧顶级功能菜单：新对话 / 数据源 / 经验库
const items: [string, string, string][] = [
  ['✦', '新对话', 'welcome'],
  ['◈', '数据源', 'datasource'],
  ['📚', '经验库', 'experience'],
];

const isActive = (route: string): boolean => {
  switch (route) {
    case 'welcome': return props.view === 'chat';
    case 'datasource': return props.view === 'datasource-list' || props.view === 'datasource';
    case 'experience': return props.view === 'experience-list';
    default: return false;
  }
};

const onPick = (route: string) => emit('nav', route);

// ---------- 工作空间 ----------
const ws = useWorkspaces();
const wsExpanded = ref(true);
const showCreate = ref(false);
const creating = ref(false);
const newName = ref('');

onMounted(async () => {
  if (ws.workspaces.value.length === 0) {
    try { await ws.reload(); } catch { /* noop */ }
  }
});

const sortedWorkspaces = computed(() =>
  [...ws.workspaces.value].sort((x, y) => {
    if ((x.isDefault ? 1 : 0) !== (y.isDefault ? 1 : 0)) return (y.isDefault ? 1 : 0) - (x.isDefault ? 1 : 0);
    return (y.updatedAt || 0) - (x.updatedAt || 0);
  })
);

const onSwitchCurrent = (id: string) => {
  if (id === ws.currentId.value) return;
  ws.setCurrent(id);
  emit('switch-workspace', id);
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
</script>

<template>
  <div :class="['sidebar', { exp: expanded }]">
    <div class="sidebar-logo" @click="emit('toggle')" style="cursor:pointer" :title="expanded ? '收起侧栏' : '展开侧栏'">
      <div class="logo-mark">推</div>
      <div class="logo-text">推演平台</div>
    </div>
    <button v-for="(item, i) in items" :key="i"
            :class="['sb-item', { active: isActive(item[2]) }]"
            @click="onPick(item[2])">
      <span class="sb-icon">{{ item[0] }}</span><span class="sb-item-label">{{ item[1] }}</span>
    </button>

    <!-- 工作空间树 -->
    <div v-if="expanded" class="sb-ws">
      <button class="sb-section-head" @click="wsExpanded = !wsExpanded">
        <span class="sb-section-label">工作空间</span>
        <span :class="['sb-caret', { open: wsExpanded }]">▸</span>
      </button>
      <div v-if="wsExpanded" class="sb-ws-list">
        <WorkspaceNode v-for="w in sortedWorkspaces" :key="w.id"
                       :workspace="w"
                       :is-current="w.id === ws.currentId.value"
                       @switch-current="onSwitchCurrent"
                       @open-conversation="emit('open-conversation', $event)"
                       @open-graph="emit('open-graph', $event)"
                       @open-datasource="emit('open-datasource', $event)"
                       @open-datasources="emit('open-datasources', $event)"
                       @add-datasource="emit('add-datasource', $event)"
                       @open-experience="emit('open-experience', $event)"
                       @open-experiences="emit('open-experiences', $event)"
                       @add-experience="emit('add-experience', $event)"
                       @rename-conversation="(id, title) => emit('rename-conversation', id, title)"
                       @delete-conversation="emit('delete-conversation', $event)"
                       @rename-graph="(id, title) => emit('rename-graph', id, title)"
                       @delete-graph="emit('delete-graph', $event)" />
        <button class="sb-ws-new" @click="openCreate" title="新建工作空间">
          <span class="sb-ws-avatar plus">＋</span>
          <span class="sb-ws-name">新建工作空间</span>
        </button>
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
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  position: relative;
}
.sb-ws::before {
  content: '';
  position: absolute;
  inset: 0 12px auto 12px; height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.10), transparent);
  pointer-events: none;
}
.sb-ws::-webkit-scrollbar { width: 4px; }
.sb-ws::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.12); border-radius: 4px; }
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
  transition: opacity 0.15s ease;
}
.sb-section-head:hover { opacity: 0.85; }
.sb-caret {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.40);
  transition: transform .2s cubic-bezier(.34,1.56,.64,1);
}
.sb-caret.open {
  transform: rotate(90deg);
}
.sb-ws-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 6px;
}
.sb-ws-new {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 9px;
  cursor: pointer;
  color: var(--text-dim);
  background: none;
  border: none;
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  width: 100%;
  transition: background 0.15s ease, color 0.15s ease;
  letter-spacing: 0.15px;
}
.sb-ws-new:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.sb-ws-avatar {
  width: 22px; height: 22px;
  flex-shrink: 0;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  font-family: 'Inter', sans-serif;
}
.sb-ws-avatar.plus {
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.55);
  border: 1px dashed rgba(255, 255, 255, 0.20);
  transition: all 0.18s ease;
}
.sb-ws-new:hover .sb-ws-avatar.plus {
  border-color: rgba(66, 184, 131, 0.5);
  color: #5fd4a3;
  background: rgba(66, 184, 131, 0.08);
}
.sb-ws-name {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-section-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1.4px;
  color: rgba(255, 255, 255, 0.42);
  font-family: 'JetBrains Mono', 'Inter', sans-serif;
  font-weight: 600;
}

/* ---------- 新建对话框 ---------- */
.sb-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1500;
}
.sb-dialog {
  background: linear-gradient(180deg, #182338 0%, #101729 100%);
  border: 1px solid rgba(255, 255, 255, 0.10);
  border-radius: 16px;
  padding: 22px;
  width: 360px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: 0 24px 60px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.06);
}
.sb-dialog h3 {
  margin: 0; font-size: 16px; color: var(--text-main);
  font-weight: 600; letter-spacing: 0.3px;
}
.sb-dialog input {
  background: rgba(8, 13, 22, 0.80);
  border: 1px solid rgba(255, 255, 255, 0.10);
  color: #e2e8f0;
  padding: 10px 12px;
  border-radius: 9px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.sb-dialog input:focus {
  border-color: rgba(66, 184, 131, 0.55);
  box-shadow: 0 0 0 3px rgba(66, 184, 131, 0.10);
}
.sb-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.sb-btn-primary {
  background: linear-gradient(135deg, #5fd4a3, #42b883);
  color: #062a1c;
  border: none;
  padding: 9px 20px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
  box-shadow: 0 6px 16px rgba(66, 184, 131, 0.30), inset 0 1px 0 rgba(255,255,255,0.32);
  letter-spacing: 0.2px;
}
.sb-btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(66, 184, 131, 0.40), inset 0 1px 0 rgba(255,255,255,0.36);
}
.sb-btn-primary:disabled { opacity: 0.55; cursor: not-allowed; transform: none; box-shadow: none; }
.sb-btn-cancel {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.70);
  padding: 9px 20px;
  border-radius: 9px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s ease, color 0.15s ease;
}
.sb-btn-cancel:hover { background: rgba(255, 255, 255, 0.10); color: #fff; }
</style>
