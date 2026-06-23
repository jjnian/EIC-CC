<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { useSidebarTree, type SidebarConversation } from '../../composables/useSidebarTree';
import { Button } from '@/components/ui/button';

const emit = defineEmits<{
  (e: 'open', id: string): void;
  (e: 'new'): void;
}>();

const ws = useWorkspaces();
const tree = useSidebarTree();

const loading = computed(() => tree.isLoadingConv(ws.currentId.value));
const conversations = computed<SidebarConversation[]>(() =>
  tree.getConversations(ws.currentId.value)
);

const reload = async (force = false) => {
  const id = ws.currentId.value;
  if (!id) return;
  await tree.loadConversations(id, force);
};

onMounted(() => reload());
watch(() => ws.currentId.value, () => reload());

const fmtTime = (t: number) => {
  if (!t) return '';
  const d = new Date(t);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  return sameDay
    ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
};
</script>

<template>
  <div class="conv-list-view">
    <div class="cl-header">
      <div>
        <h2>历史对话</h2>
        <p>选择一条对话还原它的完整历史，或开始一段新对话。</p>
      </div>
      <Button @click="emit('new')">＋ 新对话</Button>
    </div>

    <div v-if="loading" class="cl-state">加载中…</div>
    <div v-else-if="conversations.length === 0" class="cl-empty">
      <div class="cl-empty-icon">💬</div>
      <p>还没有对话。用一句话描述实体与关系，就能生成本体图谱。</p>
      <Button @click="emit('new')">开始新对话</Button>
    </div>
    <div v-else class="cl-grid">
      <button v-for="c in conversations" :key="c.id" class="cl-card" @click="emit('open', c.id)">
        <div class="cl-card-title">{{ c.title || '新对话' }}</div>
        <div class="cl-card-time">{{ fmtTime(c.updatedAt) }}</div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.conv-list-view {
  flex: 1;
  overflow-y: auto;
  padding: 28px 32px;
}
.cl-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}
.cl-header h2 {
  margin: 0 0 6px;
  font-size: 20px;
  color: var(--text-main);
}
.cl-header p {
  margin: 0;
  font-size: 13px;
  color: var(--text-dim);
}
.cl-new {
  flex-shrink: 0;
  background: var(--accent);
  color: #fff;
  border: none;
  padding: 9px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
}
.cl-new:hover { background: var(--accent-soft); }
.cl-state, .cl-empty {
  color: var(--text-dim);
  font-size: 13px;
  padding: 40px 0;
  text-align: center;
}
.cl-empty { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.cl-empty-icon { font-size: 40px; opacity: 0.6; }
.cl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.cl-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.12s;
  min-height: 78px;
}
.cl-card:hover {
  background: rgba(47, 134, 214, 0.08);
  border-color: rgba(47, 134, 214, 0.4);
}
.cl-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cl-card-time {
  font-size: 12px;
  color: var(--text-dim);
}
</style>
