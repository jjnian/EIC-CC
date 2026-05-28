<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue';
import { useSidebarTree } from '../composables/useSidebarTree';
import { useWorkspaces } from '../composables/useWorkspaces';
import { confirm as uiConfirm } from '../composables/useConfirm';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { SseHandle } from '../api/http';
import type { Workspace } from '../api/workspaces';
import { indexDataSource, getIndexStatus, isEmbeddingConfigured, type IndexStatus } from '../api/indexing';
import type { ChatBuildStep } from '../composables/useConversations';
import BuildSteps from './chat/BuildSteps.vue';

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
const ws = useWorkspaces();

// 二级展开状态:对话/数据源/血缘图各自独立,初始收起
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
    indexExpanded.value = false;
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

const onClickDelete = async (e: Event) => {
  e.stopPropagation();
  if (props.workspace.isDefault) {
    toast.warn('默认工作空间不可删除');
    return;
  }
  const ok = await uiConfirm({
    title: '删除工作空间',
    message: `「${props.workspace.name}」内的本体图、推演分支、对话与数据源将一并清空，且无法恢复。确定继续吗？`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    const res = await ws.remove(props.workspace.id);
    toast.success('已删除');
    // 删的是当前 ws 时,后端切到默认 ws,前端整页刷新以重置所有视图
    if (res.switchedTo) window.location.reload();
  } catch (err) {
    toast.warn(err instanceof ApiError ? err.message : '删除失败');
  }
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

// ===== 向量索引 =====
const indexExpanded = ref(false);
const embeddingConfigured = ref<boolean | null>(null);
const indexStatuses = ref<Record<string, IndexStatus>>({});
const indexingSteps = ref<Record<string, { steps: ChatBuildStep[]; done: boolean }>>({});
let indexHandles: Record<string, SseHandle> = {};

const fileStoredSources = computed(() =>
  dataSources.value.filter((d: any) => d.kind === 'file_stored')
);

const totalIndexedChunks = computed(() =>
  Object.values(indexStatuses.value).reduce((sum, s) => sum + (s.chunkCount || 0), 0)
);

const indexStatusLabel = (id: string) => {
  const s = indexStatuses.value[id];
  if (!s || s.status === 'none') return '未索引';
  if (s.status === 'indexing') return '索引中…';
  if (s.status === 'indexed') return `已索引 (${s.chunkCount}块)`;
  if (s.status === 'error') return '索引失败';
  return '';
};

const canIndex = (id: string) => {
  const s = indexStatuses.value[id];
  return !s || s.status !== 'indexing';
};

const onToggleIndex = () => {
  indexExpanded.value = !indexExpanded.value;
  if (indexExpanded.value) {
    if (embeddingConfigured.value === null) {
      isEmbeddingConfigured()
        .then(r => { embeddingConfigured.value = r.configured; })
        .catch(() => { embeddingConfigured.value = false; });
    }
    if (!loadedDS.value) {
      tree.loadDataSources(props.workspace.id).catch(() => {});
    }
    loadIndexStatuses();
  }
};

const loadIndexStatuses = async () => {
  for (const d of fileStoredSources.value) {
    try {
      indexStatuses.value[String(d.id)] = await getIndexStatus(String(d.id));
    } catch { /* ignore */ }
  }
};

const onIndexOne = (id: string) => {
  if (indexHandles[id]) { try { indexHandles[id].abort(); } catch {} }

  indexStatuses.value[id] = { status: 'indexing', chunkCount: 0 };
  indexingSteps.value[id] = { steps: [], done: false };

  indexHandles[id] = indexDataSource(id, {
    onStep: (step) => {
      const state = indexingSteps.value[id];
      if (!state) return;
      for (const s of state.steps) {
        if (s.status === 'running') s.status = 'done';
      }
      state.steps.push({ key: step.key, label: step.label, status: 'running' });
    },
    onError: (msg) => {
      indexStatuses.value[id] = { status: 'error', chunkCount: 0 };
      const state = indexingSteps.value[id];
      if (state) {
        for (const s of state.steps) s.status = 'done';
        state.done = true;
      }
      toast.warn(`索引失败: ${msg}`);
    },
    onClose: () => {
      const state = indexingSteps.value[id];
      if (state) {
        for (const s of state.steps) s.status = 'done';
        state.done = true;
      }
      delete indexHandles[id];
      getIndexStatus(id).then(s => { indexStatuses.value[id] = s; }).catch(() => {});
    },
  });
};

const onIndexAll = () => {
  for (const d of fileStoredSources.value) {
    if (canIndex(String(d.id))) {
      onIndexOne(String(d.id));
    }
  }
};

onBeforeUnmount(() => {
  for (const h of Object.values(indexHandles)) {
    try { h.abort(); } catch {}
  }
});
</script>

<template>
  <div class="ws-node">
    <!-- 顶级行:caret + 头像 + 名称 + 默认徽章 + 切换按钮 + 删除按钮 -->
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
      <button v-if="!workspace.isDefault"
              class="ws-del-btn"
              :title="`删除 ${workspace.name}`"
              @click="onClickDelete">×</button>
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
        <div class="ws-child-head" role="button" tabindex="0"
             @click="onToggleDS"
             @keydown.enter.prevent="onToggleDS"
             @keydown.space.prevent="onToggleDS">
          <span :class="['ws-caret', { open: dsExpanded }]">▸</span>
          <span class="ws-child-icon">📂</span>
          <span class="ws-child-label">数据源</span>
          <span v-if="dataSources.length" class="ws-child-count">{{ dataSources.length }}</span>
          <span class="ws-child-spacer" />
          <button class="ws-child-action" title="添加数据源" @click.stop="emit('open-create-data-source')">＋</button>
        </div>
        <div v-if="dsExpanded" class="ws-child-list">
          <div v-if="loadingDS && !dataSources.length" class="ws-child-empty">加载中…</div>
          <div v-else-if="loadedDS && !dataSources.length" class="ws-child-empty">还没有导入文档</div>
          <div v-else-if="!loadedDS" class="ws-child-empty">展开自动加载</div>
          <div v-for="d in dataSources" :key="d.id"
               class="ws-child-item"
               :title="d.name + (d.size ? ' · ' + formatBytes(d.size) : '')"
               @click="['mysql','pgsql','file_stored','https_api'].includes(String(d.kind)) && emit('open-data-source', String(d.id))">
            <span class="ws-child-icon-mini">{{ ({ mysql:'🗄', pgsql:'🐘', file_stored:'📄', https_api:'🌐', file:'📎', url:'🔗' } as Record<string,string>)[d.kind] || '📁' }}</span>
            <span class="ws-child-item-title">{{ d.name }}</span>
            <span v-if="['mysql','pgsql','file_stored','https_api'].includes(String(d.kind))" :class="['status-dot', String((d as any).status || 'idle')]" />
            <span class="ws-child-meta">{{ formatBytes(d.size) }}</span>
            <button class="ws-child-del" :title="`删除 ${d.name}`"
                    @click="(e) => onDeleteDS(d.id, d.name, e)">×</button>
          </div>
        </div>
      </div>
      <!-- 向量索引 -->
      <div class="ws-child-node">
        <div class="ws-child-head" role="button" tabindex="0"
             @click="onToggleIndex"
             @keydown.enter.prevent="onToggleIndex"
             @keydown.space.prevent="onToggleIndex">
          <span :class="['ws-caret', { open: indexExpanded }]">▸</span>
          <span class="ws-child-icon">🔍</span>
          <span class="ws-child-label">向量索引</span>
          <span v-if="totalIndexedChunks > 0" class="ws-child-count">{{ totalIndexedChunks }}块</span>
          <span class="ws-child-spacer" />
          <button v-if="fileStoredSources.length && embeddingConfigured" class="ws-child-action" title="全部索引" @click.stop="onIndexAll">▶</button>
        </div>
        <div v-if="indexExpanded" class="ws-child-list">
          <div v-if="embeddingConfigured === false" class="ws-child-empty">
            Embedding 未配置，请设置 EMBEDDING_BASE_URL 和 EMBEDDING_API_KEY
          </div>
          <div v-else-if="!fileStoredSources.length" class="ws-child-empty">没有可索引的文件数据源</div>
          <template v-else>
            <div v-for="d in fileStoredSources" :key="d.id" class="ws-index-item">
              <div class="ws-index-row">
                <span :class="['ws-index-dot', indexStatuses[String(d.id)]?.status || 'none']" />
                <span class="ws-index-name">{{ d.name }}</span>
                <span class="ws-index-status">{{ indexStatusLabel(String(d.id)) }}</span>
                <button v-if="canIndex(String(d.id)) && embeddingConfigured" class="ws-index-btn"
                        @click.stop="onIndexOne(String(d.id))">
                  {{ indexStatuses[String(d.id)]?.status === 'indexed' ? '重建' : '索引' }}
                </button>
              </div>
              <BuildSteps v-if="indexingSteps[String(d.id)]?.steps?.length"
                          :steps="indexingSteps[String(d.id)].steps"
                          :done="indexingSteps[String(d.id)].done"
                          class="ws-index-steps" />
            </div>
          </template>
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
.ws-del-btn {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.35);
  font-size: 14px;
  line-height: 1;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  cursor: pointer;
  font-family: inherit;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
}
.ws-head:hover .ws-del-btn { display: flex; }
.ws-del-btn:hover {
  background: rgba(255, 102, 68, 0.2);
  color: #ff8a6f;
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

/* 向量索引 */
.ws-index-item { padding: 2px 0; }
.ws-index-row {
  display: flex; align-items: center; gap: 6px;
  padding: 3px 8px; font-size: 12px; border-radius: 4px; transition: background 0.12s;
}
.ws-index-row:hover { background: rgba(255,255,255,0.04); }
.ws-index-dot {
  width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
}
.ws-index-dot.none { background: rgba(255,255,255,0.2); }
.ws-index-dot.indexed { background: #42b883; box-shadow: 0 0 4px rgba(66,184,131,0.5); }
.ws-index-dot.indexing { background: #fbbf24; animation: idx-pulse 1.2s infinite; }
.ws-index-dot.error { background: #ff6b6b; }
@keyframes idx-pulse { 0%,100% { opacity:1; } 50% { opacity:0.4; } }
.ws-index-name {
  flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-dim);
}
.ws-index-status { font-size: 10px; color: rgba(255,255,255,0.35); white-space: nowrap; }
.ws-index-btn {
  background: rgba(66,184,131,0.12); border: 1px solid rgba(66,184,131,0.25);
  color: #42b883; font-size: 10px; padding: 1px 8px; border-radius: 4px;
  cursor: pointer; font-family: inherit; transition: all 0.12s;
}
.ws-index-btn:hover { background: rgba(66,184,131,0.2); }
.ws-index-steps { margin: 4px 8px 6px; }
</style>
