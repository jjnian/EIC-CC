<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { useSidebarTree } from '../composables/useSidebarTree';
import WorkspaceNode from './WorkspaceNode.vue';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { OntologyModel } from '../types';

const props = defineProps<{
  expanded: boolean;
  view: string;
  ontologyModels?: OntologyModel[];
}>();

const emit = defineEmits<{
  (e: 'toggle'): void;
  (e: 'nav', route: string): void;
  (e: 'open-conversation', id: string): void;
  (e: 'new-conversation'): void;
  (e: 'switch-workspace', id: string): void;
  (e: 'open-data-source', id: string): void;
  (e: 'open-create-data-source'): void;
  (e: 'open-ontology-model', id: string): void;
  (e: 'delete-ontology-model', id: string): void;
}>();

const a = ref(0);
const items = [
  ['✦', '新对话', 'welcome'],
  ['◈', '数据源', 'datasource']
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

const tree = useSidebarTree();

// 顶级展开状态(按 workspaceId);默认仅当前工作空间展开
const topExpanded = ref<Set<string>>(new Set());

const ensureCurrentExpanded = () => {
  const id = ws.currentId.value;
  if (id) topExpanded.value = new Set([id]);
  else topExpanded.value = new Set();
};

onMounted(async () => {
  if (ws.workspaces.value.length === 0) {
    try { await ws.reload(); } catch { /* noop */ }
  }
  ensureCurrentExpanded();
  // 默认展开的当前工作空间不预拉数据;由 WorkspaceNode 在用户展开二级时按需拉取
});

// 切换当前工作空间:清缓存 + 重置展开
watch(() => ws.currentId.value, (newId, oldId) => {
  if (newId === oldId) return;
  tree.clearCache();
  ensureCurrentExpanded();
});

const sortedWorkspaces = computed(() => {
  return [...ws.workspaces.value].sort((x, y) => {
    if ((x.isDefault ? 1 : 0) !== (y.isDefault ? 1 : 0)) return (y.isDefault ? 1 : 0) - (x.isDefault ? 1 : 0);
    return (y.updatedAt || 0) - (x.updatedAt || 0);
  });
});

const onToggleTop = (id: string) => {
  const set = new Set(topExpanded.value);
  if (set.has(id)) set.delete(id);
  else set.add(id);
  topExpanded.value = set;
};

const onSwitchCurrent = (id: string) => {
  if (id === ws.currentId.value) return;
  ws.setCurrent(id);
  emit('switch-workspace', id);
};

const onOpenConv = (_wsId: string, convId: string) => {
  emit('open-conversation', convId);
};

const onNewConv = (_wsId: string) => {
  emit('new-conversation');
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
            :class="['sb-item', { active: (item[2] === 'welcome' && view === 'welcome') || (item[2] === 'list' && view === 'list') }]"
            @click="onPick(i, item[2] as string)">
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
                       :expanded="topExpanded.has(w.id)"
                       :ontology-models="w.id === ws.currentId.value ? props.ontologyModels : undefined"
                       @toggle-expand="onToggleTop"
                       @switch-current="onSwitchCurrent"
                       @open-conversation="onOpenConv"
                       @new-conversation="onNewConv"
                       @open-data-source="(id: string) => emit('open-data-source', id)"
                       @open-create-data-source="emit('open-create-data-source')"
                       @open-ontology-model="(_wsId: string, modelId: string) => emit('open-ontology-model', modelId)"
                       @delete-ontology-model="(_wsId: string, modelId: string) => emit('delete-ontology-model', modelId)" />
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
}
.sb-ws::-webkit-scrollbar { width: 4px; }
.sb-ws::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 4px; }
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
}
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
.sb-ws-new:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.sb-ws-avatar {
  width: 22px; height: 22px;
  flex-shrink: 0;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  font-family: 'Inter', sans-serif;
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
.sb-section-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1.2px;
  color: rgba(255, 255, 255, 0.35);
  font-family: 'Inter', sans-serif;
  font-weight: 500;
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
