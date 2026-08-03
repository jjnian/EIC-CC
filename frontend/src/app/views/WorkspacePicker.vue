<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import {
  Layers, Megaphone, Users, ShieldCheck, Building2, Globe,
  Plus, Trash2, Waypoints, Box, Clock, ArrowRight,
  CheckCircle2, Pencil, LoaderCircle,
} from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';
import { useToastStore } from '../stores/toast';
import { updateWorkspace } from '../../api/workspaces';
import { wsColorForIndex } from '../lib/workspaceTheme';
import { timeAgo } from '../lib/format';
import UiModal from '../components/UiModal.vue';

const router = useRouter();
const ws = useWorkspaceStore();
const toast = useToastStore();

/** 将原型中的 icon 名称映射到 lucide-vue-next 组件 */
const ICON_MAP: Record<string, any> = {
  layers: Layers,
  megaphone: Megaphone,
  users: Users,
  'shield-check': ShieldCheck,
  'building-2': Building2,
  globe: Globe,
};

function wsIcon(iconName: string) {
  return ICON_MAP[iconName] || Layers;
}

// —— 表单弹窗状态 ——
const formOpen = ref(false);
const editingId = ref<string | null>(null); // null = 新建模式
const formName = ref('');
const formDesc = ref('');
const saving = ref(false);

const formTitle = computed(() => (editingId.value ? '编辑工作空间' : '新建工作空间'));
const formHint = computed(() => (editingId.value ? '修改后保存' : '创建后可直接进入'));
const formSubmitLabel = computed(() => (editingId.value ? '保存修改' : '创建并进入'));

// —— 删除确认弹窗 ——
const deletingId = ref<string | null>(null);
const deleteTargetName = computed(() => ws.list.find((w) => w.id === deletingId.value)?.name || '');
const deleteWarning = computed(() => {
  const extra = ws.list.length <= 1 ? ' 这是最后一个工作空间，删除后需创建新空间。' : '';
  return `确定删除「${deleteTargetName.value}」吗？其中的模型、经验引用都会被清理，此操作不可恢复。${extra}`;
});

// —— 动作 ——
function enter(id: string) {
  ws.select(id);
  router.push({ name: 'dashboard' });
}

function openCreate() {
  editingId.value = null;
  formName.value = '';
  formDesc.value = '';
  formOpen.value = true;
}

function openEdit(id: string) {
  const w = ws.list.find((x) => x.id === id);
  if (!w) return;
  editingId.value = id;
  formName.value = w.name;
  formDesc.value = w.description || '';
  formOpen.value = true;
}

function closeForm() {
  formOpen.value = false;
  editingId.value = null;
}

async function submitForm() {
  const name = formName.value.trim();
  if (!name) {
    toast.error('请输入工作空间名称');
    return;
  }
  saving.value = true;
  try {
    if (editingId.value) {
      await updateWorkspace(editingId.value, { name, description: formDesc.value.trim() || undefined });
      await ws.refresh();
      toast.success(`工作空间「${name}」已更新`);
    } else {
      // 新建模式
      await ws.create(name, formDesc.value.trim() || undefined);
      toast.success(`工作空间「${name}」已创建`);
      router.push({ name: 'dashboard' });
      return;
    }
    closeForm();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

function openDelete(id: string) {
  deletingId.value = id;
}

function closeDelete() {
  deletingId.value = null;
}

async function confirmDelete() {
  if (!deletingId.value) return;
  const id = deletingId.value;
  deletingId.value = null;
  try {
    await ws.remove(id);
    toast.success('已删除');
  } catch (e) {
    toast.error((e as Error).message);
  }
}
</script>

<template>
  <div class="flex min-h-full flex-col items-center px-6 py-16" style="background:var(--bg, #F7F6F3)">
    <!-- 页头 -->
    <div class="mb-10 flex flex-col items-center text-center">
      <div
        class="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl text-white shadow-lg"
        style="background:linear-gradient(135deg, #6366F1, #8B5CF6);box-shadow:0 4px 20px rgba(99,102,241,.3)"
      >
        <Waypoints :size="26" />
      </div>
      <h1 class="text-2xl font-bold text-slate-900">推演平台</h1>
      <p class="mt-1 text-[11px] tracking-widest text-slate-400">EIC-CC · 业务血缘建模</p>
      <p class="mt-3 max-w-md text-[13.5px] leading-relaxed text-slate-500">
        基于本体图谱的 AI 业务血缘建模 —— 选择一个工作空间开始，或新建一个。
      </p>
    </div>

    <!-- 卡片网格 -->
    <div class="grid w-full max-w-4xl gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <!-- 工作空间卡片 -->
      <div
        v-for="(w, i) in ws.list"
        :key="w.id"
        class="ws-card group relative flex cursor-pointer flex-col rounded-2xl border bg-white p-5 text-left transition-shadow hover:border-indigo-300 hover:shadow-md"
        :class="w.id === ws.currentId ? 'border-indigo-300 shadow-sm' : 'border-slate-200 shadow-sm'"
        @click="enter(w.id)"
      >
        <!-- 当前使用标记 -->
        <span
          v-if="w.id === ws.currentId"
          class="absolute -top-2.5 left-4 inline-flex items-center gap-1 rounded-full bg-indigo-50 px-2.5 py-0.5 text-[10.5px] font-medium text-indigo-700 shadow-sm"
        >
          <CheckCircle2 :size="12" />
          当前使用
        </span>

        <!-- 悬浮操作按钮 -->
        <div class="ws-actions absolute right-3 top-3 flex gap-1 opacity-0 transition-opacity group-hover:opacity-100">
          <button
            class="rounded-md p-1.5 text-slate-300 transition hover:bg-slate-100 hover:text-slate-600"
            title="编辑"
            @click.stop="openEdit(w.id)"
          >
            <Pencil :size="14" />
          </button>
          <button
            class="rounded-md p-1.5 text-slate-300 transition hover:bg-rose-50 hover:text-rose-500"
            title="删除"
            @click.stop="openDelete(w.id)"
          >
            <Trash2 :size="14" />
          </button>
        </div>

        <!-- 图标 -->
        <div
          class="mb-3 flex h-10 w-10 items-center justify-center rounded-xl"
          :style="{
            background: wsColorForIndex(i).lightBg,
            color: wsColorForIndex(i).color,
          }"
        >
          <component :is="wsIcon(wsColorForIndex(i).icon)" :size="18" />
        </div>

        <!-- 名称与描述 -->
        <div class="text-[15px] font-semibold text-slate-900">{{ w.name }}</div>
        <div class="mt-1 min-h-[36px] text-[12.5px] leading-relaxed" :class="w.description ? 'text-slate-500' : 'text-slate-300'">
          {{ w.description || '暂无描述' }}
        </div>

        <!-- 元数据 -->
        <div class="mt-4 flex items-center gap-3 text-[11.5px] text-slate-400">
          <span class="inline-flex items-center gap-1">
            <Waypoints :size="12" />
            {{ w.modelCount || 0 }} 个模型
          </span>
          <span class="inline-flex items-center gap-1">
            <Box :size="12" />
            {{ w.nodeCount || 0 }} 节点
          </span>
        </div>

        <!-- 底部：时间 + 进入引导 -->
        <div class="mt-3 flex items-center justify-between border-t border-slate-100 pt-3 text-[11.5px] text-slate-400">
          <span class="inline-flex items-center gap-1">
            <Clock :size="12" />
            {{ w.updatedAt ? timeAgo(w.updatedAt) : (w.createdAt ? timeAgo(w.createdAt) : '—') }}
          </span>
          <span class="ws-enter inline-flex items-center gap-1 font-medium text-indigo-600 opacity-0 transition-opacity group-hover:opacity-100">
            进入 <ArrowRight :size="13" />
          </span>
        </div>
      </div>

      <!-- 新建入口卡片 -->
      <button
        class="ws-new-card flex min-h-[208px] cursor-pointer flex-col items-center justify-center gap-2.5 rounded-2xl border-2 border-dashed border-slate-300 text-slate-400 transition hover:-translate-y-0.5 hover:border-indigo-400 hover:text-indigo-600 hover:shadow-md"
        @click="openCreate"
      >
        <div class="flex h-10 w-10 items-center justify-center rounded-full bg-slate-100">
          <Plus :size="20" />
        </div>
        <span class="text-[13.5px] font-medium">新建工作空间</span>
        <span class="text-[11px] text-slate-300">名称 + 可选描述，创建后直接进入</span>
      </button>
    </div>

    <!-- 底部提示 -->
    <div class="mt-10 flex items-center gap-2 text-[11.5px] text-slate-400">
      <Waypoints :size="14" />
      工作空间用于隔离不同业务域的模型、经验与数据源配置
    </div>

    <!-- ====== 新建/编辑弹窗 ====== -->
    <UiModal :open="formOpen" :title="formTitle" @close="closeForm">
      <div class="space-y-4">
        <div>
          <label class="label">名称 <span class="text-rose-500">*</span></label>
          <input
            v-model="formName"
            class="input"
            placeholder="例如：供应链项目"
            maxlength="30"
            @keyup.enter="submitForm"
          />
        </div>
        <div>
          <label class="label">描述（可选）</label>
          <textarea
            v-model="formDesc"
            class="textarea"
            rows="3"
            placeholder="记录该工作空间的用途…"
          />
        </div>
      </div>
      <template #footer>
        <div class="flex items-center gap-2">
          <span class="flex-1 text-[11px] text-slate-400">{{ formHint }}</span>
          <button class="btn-secondary" @click="closeForm">取消</button>
          <button class="btn-primary" :disabled="!formName.trim() || saving" @click="submitForm">
            <LoaderCircle v-if="saving" :size="15" class="animate-spin" />
            {{ formSubmitLabel }}
          </button>
        </div>
      </template>
    </UiModal>

    <!-- ====== 删除确认弹窗 ====== -->
    <UiModal :open="!!deletingId" title="删除工作空间" width="420px" @close="closeDelete">
      <div class="rounded-lg bg-rose-50 px-4 py-3">
        <p class="text-[13px] leading-relaxed text-rose-700">{{ deleteWarning }}</p>
      </div>
      <template #footer>
        <div class="flex items-center gap-2">
          <span class="flex-1" />
          <button class="btn-secondary" @click="closeDelete">取消</button>
          <button class="btn-danger" @click="confirmDelete">确认删除</button>
        </div>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
/* 卡片悬浮动效 —— 匹配原型 index.html */
.ws-card {
  transition: transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 0.18s, border-color 0.18s;
}
.ws-card:hover {
  transform: translateY(-3px);
}
.ws-new-card {
  transition: transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1), border-color 0.18s, color 0.18s, box-shadow 0.18s;
}
</style>
