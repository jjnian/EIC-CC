<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useWorkspaces } from '../composables/useWorkspaces';
import { toast } from '../composables/useToast';
import { ApiError } from '../api/http';
import type { Workspace } from '../api/workspaces';
import { Button } from '@/components/ui/button';
import BaseInput from './form/BaseInput.vue';
import BaseTextarea from './form/BaseTextarea.vue';
import { Plus } from '@lucide/vue';

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
          <div class="wp-plus"><Plus :size="26" :stroke-width="2" /></div>
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
            <div class="wp-card-head-l">
              <span class="wp-avatar">{{ (w.name || '·').slice(0, 1).toUpperCase() }}</span>
              <h3>{{ w.name }}</h3>
            </div>
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
  background: #ffffff;
  border: 1px solid var(--glass-border);
  border-radius: 12px;
  padding: 22px 24px;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 150px;
  position: relative;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}
.wp-card:hover {
  border-color: var(--glass-border-strong);
  box-shadow: var(--shadow-md);
}
.wp-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.wp-card-head-l {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}
.wp-avatar {
  width: 34px; height: 34px;
  flex-shrink: 0;
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  font-size: 15px; font-weight: 700;
  color: #fff;
  background: var(--accent);
  letter-spacing: 0.4px;
}
.wp-card-head h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wp-default {
  font-size: 11px;
  background: var(--bg-elev);
  color: var(--text-dim);
  padding: 2px 9px;
  border-radius: 100px;
  border: 1px solid var(--hairline);
  flex-shrink: 0;
}
.wp-card-desc {
  font-size: 13px;
  color: var(--text-dim);
  line-height: 1.6;
  flex: 1;
}
.wp-card-foot {
  font-size: 12px;
  color: var(--text-muted);
  font-family: 'JetBrains Mono', monospace;
  padding-top: 14px;
  border-top: 1px dashed var(--hairline);
}
.wp-card-new {
  align-items: center;
  justify-content: center;
  text-align: center;
  border-style: dashed;
  gap: 8px;
}
.wp-card-new:hover .wp-plus {
  color: var(--text-main);
  border-color: rgba(0, 0, 0, 0.40);
  background: rgba(0, 0, 0, 0.04);
}
.wp-plus {
  width: 46px; height: 46px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 13px;
  border: 1px dashed rgba(0, 0, 0, 0.25);
  color: var(--text-muted);
  margin-bottom: 4px;
  transition: color 0.18s ease, border-color 0.18s ease, background 0.18s ease;
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
  background: rgba(0, 0, 0, 0.40);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1500;
}
.wp-dialog {
  background: #ffffff;
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  padding: 22px;
  width: 420px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: var(--shadow-lg);
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
  color: var(--text-dim);
}
.wp-dialog input,
.wp-dialog textarea {
  background: #ffffff;
  border: 1px solid var(--glass-border);
  color: var(--text-main);
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
  resize: vertical;
}
.wp-dialog input:focus,
.wp-dialog textarea:focus {
  border-color: rgba(0, 0, 0, 0.35);
}
.wp-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
.wp-btn-primary {
  background: var(--accent);
  color: #fff;
  border: none;
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.wp-btn-primary:hover {
  background: var(--accent-soft);
}
.wp-btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.wp-btn-cancel {
  background: #ffffff;
  border: 1px solid var(--glass-border);
  color: var(--text-dim);
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.wp-btn-cancel:hover {
  background: var(--bg-elev);
}
</style>
