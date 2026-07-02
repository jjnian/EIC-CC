<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { Workspace } from '../api/workspaces';
import { Button } from '@/components/ui/button';
import BaseInput from './form/BaseInput.vue';
import BaseTextarea from './form/BaseTextarea.vue';

const emit = defineEmits<{
  (e: 'enter', ws: Workspace): void;
}>();

const ws = useWorkspaces();
const showCreate = ref(false);
const creating = ref(false);
const newName = ref('');
const newDesc = ref('');

onMounted(async () => {
  try {
    await ws.reload();
  } catch (e) {
    console.error('load workspaces failed', e);
    toast.warn('加载工作空间列表失败');
  }
});

const enter = (w: Workspace) => {
  ws.setCurrent(w.id);
  emit('enter', w);
};

const openCreate = () => {
  showCreate.value = true;
  newName.value = '';
  newDesc.value = '';
};

const submitCreate = async () => {
  const name = newName.value.trim();
  if (!name) return;
  if (creating.value) return;
  creating.value = true;
  try {
    const w = await ws.create({ name, description: newDesc.value.trim() || undefined });
    showCreate.value = false;
    enter(w);
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : '创建失败';
    toast.warn(msg);
  } finally {
    creating.value = false;
  }
};

const formatTime = (ts?: number) => {
  if (!ts) return '';
  return new Date(ts).toLocaleString();
};
</script>

<template>
  <div class="wp-root">
    <div class="wp-inner">
      <div class="wp-head">
        <h1>选择工作空间</h1>
        <p>不同工作空间之间的本体血缘图、对话与模板互相隔离。</p>
      </div>

      <div class="wp-grid" v-if="!ws.loading.value && ws.workspaces.value.length">
        <div class="wp-card wp-card-new" @click="openCreate">
          <div class="wp-plus">＋</div>
          <div class="wp-card-title">新建工作空间</div>
          <div class="wp-card-desc">创建一个空白的工作空间</div>
        </div>
        <div
          v-for="w in ws.workspaces.value"
          :key="w.id"
          class="wp-card"
          @click="enter(w)"
        >
          <div class="wp-card-head">
            <h3>{{ w.name }}</h3>
            <span v-if="w.isDefault" class="wp-default">默认</span>
          </div>
          <p class="wp-card-desc">{{ w.description || '（无描述）' }}</p>
          <div class="wp-card-foot">
            <span>{{ formatTime(w.updatedAt) }}</span>
          </div>
        </div>
      </div>

      <div class="wp-empty" v-else-if="!ws.loading.value">
        <p>还没有工作空间</p>
        <Button @click="openCreate">新建工作空间</Button>
      </div>

      <div class="wp-loading" v-else>加载中…</div>
    </div>

    <Teleport to="body">
    <div v-if="showCreate" class="wp-mask" @click.self="showCreate = false">
      <div class="wp-dialog">
        <h3>新建工作空间</h3>
        <label>
          名称
          <BaseInput
            v-model="newName"
            placeholder="例如：供应链项目"
            @enter="submitCreate"
          />
        </label>
        <label>
          描述（可选）
          <BaseTextarea
            v-model="newDesc"
            :rows="3"
            placeholder="记录该工作空间的用途…"
          />
        </label>
        <div class="wp-actions">
          <Button variant="secondary" size="sm" @click="showCreate = false">取消</Button>
          <Button
            size="sm"
           
            :disabled="!newName.trim() || creating"
            @click="submitCreate"
          >{{ creating ? '创建中…' : '创建并进入' }}</Button>
        </div>
      </div>
    </div>
    </Teleport>
  </div>
</template>

<style scoped>
.wp-root {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  overflow-y: auto;
}
.wp-inner {
  width: 100%;
  max-width: 1080px;
}
.wp-head {
  text-align: center;
  margin-bottom: 36px;
}
.wp-head h1 {
  font-size: 28px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 8px;
}
.wp-head p {
  color: var(--text-dim);
  font-size: 14px;
}
.wp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}
.wp-card {
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  padding: 22px;
  cursor: pointer;
  transition: all 0.18s;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 150px;
}
.wp-card:hover {
  transform: translateY(-3px);
  border-color: rgba(47, 134, 214, 0.45);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.25);
}
.wp-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wp-card-head h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0;
}
.wp-default {
  font-size: 11px;
  background: rgba(47, 134, 214, 0.18);
  color: #5aa6ee;
  padding: 2px 8px;
  border-radius: 100px;
  border: 1px solid rgba(47, 134, 214, 0.3);
}
.wp-card-desc {
  font-size: 13px;
  color: var(--text-dim);
  line-height: 1.5;
  flex: 1;
}
.wp-card-foot {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  font-family: 'JetBrains Mono', monospace;
}
.wp-card-new {
  align-items: center;
  justify-content: center;
  text-align: center;
  border-style: dashed;
}
.wp-plus {
  font-size: 32px;
  color: rgba(255, 255, 255, 0.55);
  margin-bottom: 4px;
}
.wp-card-title {
  font-size: 15px;
  color: var(--text-main);
  font-weight: 500;
}
.wp-empty,
.wp-loading {
  text-align: center;
  padding: 64px;
  color: var(--text-dim);
}
.wp-empty .wp-btn-primary {
  margin-top: 16px;
}
.wp-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1500;
}
.wp-dialog {
  background: #141e30;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 14px;
  padding: 22px;
  width: 420px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.wp-dialog h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-main);
}
.wp-dialog label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.65);
}
.wp-dialog input,
.wp-dialog textarea {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #e2e8f0;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
  resize: vertical;
}
.wp-dialog input:focus,
.wp-dialog textarea:focus {
  border-color: #2f86d6;
}
.wp-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
.wp-btn-primary {
  background: #2f86d6;
  color: #fff;
  border: none;
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.wp-btn-primary:hover {
  background: #5aa6ee;
}
.wp-btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.wp-btn-cancel {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.65);
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.wp-btn-cancel:hover {
  background: rgba(255, 255, 255, 0.1);
}
</style>
