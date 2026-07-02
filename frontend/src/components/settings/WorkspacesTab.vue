<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { toast } from '../../composables/useToast';
import { ApiError } from '../../api/http';
import type { Workspace } from '../../api/workspaces';
import FormField from '../form/FormField.vue';
import BaseInput from '../form/BaseInput.vue';
import BaseTextarea from '../form/BaseTextarea.vue';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

const emit = defineEmits<{
  (e: 'switch', id: string): void;
}>();

const ws = useWorkspaces();

const showCreate = ref(false);
const creating = ref(false);
const newName = ref('');
const newDesc = ref('');

const editing = ref<Workspace | null>(null);
const editName = ref('');
const editDesc = ref('');

onMounted(async () => {
  try { await ws.reload(); } catch { /* noop */ }
});

const sortedList = computed(() => {
  return [...ws.workspaces.value].sort((a, b) => {
    if ((a.isDefault ? 1 : 0) !== (b.isDefault ? 1 : 0)) return (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0);
    return (b.updatedAt || 0) - (a.updatedAt || 0);
  });
});

const openCreate = () => {
  showCreate.value = true;
  newName.value = '';
  newDesc.value = '';
};

const submitCreate = async () => {
  const name = newName.value.trim();
  if (!name || creating.value) return;
  creating.value = true;
  try {
    await ws.create({ name, description: newDesc.value.trim() || undefined });
    showCreate.value = false;
    toast.success('已创建工作空间');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '创建失败');
  } finally {
    creating.value = false;
  }
};

const openEdit = (w: Workspace) => {
  editing.value = w;
  editName.value = w.name;
  editDesc.value = w.description || '';
};

const submitEdit = async () => {
  if (!editing.value) return;
  const name = editName.value.trim();
  if (!name) return;
  try {
    await ws.update(editing.value.id, { name, description: editDesc.value.trim() });
    editing.value = null;
    toast.success('已保存');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '保存失败');
  }
};

const switchTo = async (w: Workspace) => {
  if (w.id === ws.currentId.value) return;
  const ok = await uiConfirm({
    title: '切换工作空间',
    message: `将切换到「${w.name}」。当前未保存的图谱编辑会立即提交，列表与对话会重新加载。`,
    confirmLabel: '切换',
  });
  if (!ok) return;
  ws.setCurrent(w.id);
  emit('switch', w.id);
};

const remove = async (w: Workspace) => {
  if (w.isDefault) {
    toast.warn('默认工作空间不可删除');
    return;
  }
  const ok = await uiConfirm({
    title: '删除工作空间',
    message: `「${w.name}」内的本体血缘图、对话与模板将一并清空，且无法恢复。确定继续吗？`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    const res = await ws.remove(w.id);
    toast.success('已删除');
    if (res.switchedTo) emit('switch', res.switchedTo);
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
};

const formatTime = (ts?: number) => ts ? new Date(ts).toLocaleString() : '';
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>工作空间</h3>
        <p>每个工作空间相互隔离；删除会一并清空其下的本体血缘图与对话。</p>
      </div>
      <Button size="sm" @click="openCreate">＋新建</Button>
    </div>

    <div class="pref-card">
      <div v-if="sortedList.length === 0" class="empty-state">暂无工作空间</div>
      <div
        v-for="w in sortedList"
        :key="w.id"
        class="pref-row"
      >
        <div class="pref-label">
          <div class="pref-name">
            {{ w.name }}
            <span v-if="w.isDefault" class="anthropic-badge">默认</span>
            <span v-if="w.id === ws.currentId.value" class="ws-current">当前</span>
          </div>
          <div class="pref-desc">
            {{ w.description || '（无描述）' }}
            <span class="ws-meta">· 更新于 {{ formatTime(w.updatedAt) }}</span>
          </div>
        </div>
        <div class="pref-control">
          <Button
            variant="secondary" size="sm"
            :disabled="w.id === ws.currentId.value"
            @click="switchTo(w)"
          >切换</Button>
          <Button variant="secondary" size="sm" @click="openEdit(w)">编辑</Button>
          <Button
            variant="destructive" size="sm"
            :disabled="!!w.isDefault"
            @click="remove(w)"
          >删除</Button>
        </div>
      </div>
    </div>

    <!-- 新建对话框 -->
    <Dialog :open="showCreate" @update:open="(v: boolean) => { if (!v) showCreate = false; }">
      <DialogContent class="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>新建工作空间</DialogTitle>
        </DialogHeader>
        <FormField label="名称" required>
          <BaseInput v-model="newName" placeholder="例如：风控项目" @enter="submitCreate" />
        </FormField>
        <FormField label="描述" hint="可选">
          <BaseTextarea v-model="newDesc" :rows="3" placeholder="记录该工作空间的用途…" />
        </FormField>
        <DialogFooter>
          <Button variant="secondary" size="sm" @click="showCreate = false">取消</Button>
          <Button
            size="sm"
            :disabled="!newName.trim() || creating"
            @click="submitCreate"
          >{{ creating ? '创建中…' : '创建' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 编辑对话框 -->
    <Dialog :open="!!editing" @update:open="(v: boolean) => { if (!v) editing = null; }">
      <DialogContent class="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>编辑工作空间</DialogTitle>
        </DialogHeader>
        <FormField label="名称" required>
          <BaseInput v-model="editName" @enter="submitEdit" />
        </FormField>
        <FormField label="描述">
          <BaseTextarea v-model="editDesc" :rows="3" />
        </FormField>
        <DialogFooter>
          <Button variant="secondary" size="sm" @click="editing = null">取消</Button>
          <Button size="sm" :disabled="!editName.trim()" @click="submitEdit">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </section>
</template>

<style scoped>
.ws-current {
  font-size: 11px;
  background: rgba(47, 134, 214, 0.18);
  color: #5aa6ee;
  padding: 1px 8px;
  border-radius: 100px;
  border: 1px solid rgba(47, 134, 214, 0.35);
  margin-left: 6px;
}
.ws-meta {
  margin-left: 8px;
  color: rgba(255, 255, 255, 0.35);
  font-size: 11px;
}
.action-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
