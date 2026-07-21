<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { Layers, Plus, Trash2, Waypoints, ArrowRight, LoaderCircle } from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';
import { useToastStore } from '../stores/toast';
import { formatTime } from '../lib/format';
import UiModal from '../components/UiModal.vue';

const router = useRouter();
const ws = useWorkspaceStore();
const toast = useToastStore();

const creating = ref(false);
const name = ref('');
const description = ref('');
const saving = ref(false);
const removing = ref<string>('');

async function enter(id: string) {
  ws.select(id);
  await router.push({ name: 'dashboard' });
}

async function submitCreate() {
  if (!name.value.trim()) return;
  saving.value = true;
  try {
    await ws.create(name.value.trim(), description.value.trim() || undefined);
    creating.value = false;
    name.value = '';
    description.value = '';
    toast.success('工作空间已创建');
    await router.push({ name: 'dashboard' });
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function remove(id: string) {
  if (!window.confirm('确定删除该工作空间？其中的模型、经验引用都会被清理。')) return;
  removing.value = id;
  try {
    await ws.remove(id);
    toast.success('已删除');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    removing.value = '';
  }
}
</script>

<template>
  <div class="flex min-h-full flex-col items-center bg-slate-100 px-6 py-16">
    <div class="mb-10 flex flex-col items-center text-center">
      <div class="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-indigo-600 text-white shadow-lg shadow-indigo-200">
        <Waypoints :size="26" />
      </div>
      <h1 class="text-2xl font-bold text-slate-900">推演平台</h1>
      <p class="mt-2 max-w-md text-[14px] leading-relaxed text-slate-500">
        基于本体图谱的 AI 业务血缘建模 —— 选择一个工作空间开始，或新建一个。
      </p>
    </div>

    <div class="grid w-full max-w-3xl gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <button
        v-for="w in ws.list"
        :key="w.id"
        class="card group relative flex flex-col p-5 text-left transition hover:border-indigo-300 hover:shadow-md"
        @click="enter(w.id)"
      >
        <div class="mb-3 flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600">
          <Layers :size="17" />
        </div>
        <div class="text-[15px] font-semibold text-slate-900">{{ w.name }}</div>
        <div class="mt-1 line-clamp-2 min-h-[18px] text-[12.5px] text-slate-400">{{ w.description || '暂无描述' }}</div>
        <div class="mt-4 flex items-center justify-between text-xs text-slate-400">
          <span>{{ formatTime(w.createdAt) }}</span>
          <span class="flex items-center gap-1 font-medium text-indigo-600 opacity-0 transition group-hover:opacity-100">
            进入 <ArrowRight :size="13" />
          </span>
        </div>
        <button
          class="absolute right-3 top-3 rounded-md p-1.5 text-slate-300 opacity-0 transition hover:bg-rose-50 hover:text-rose-500 group-hover:opacity-100"
          @click.stop="remove(w.id)"
        >
          <LoaderCircle v-if="removing === w.id" :size="14" class="animate-spin" />
          <Trash2 v-else :size="14" />
        </button>
      </button>

      <button
        class="flex min-h-[160px] flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-300 text-slate-400 transition hover:border-indigo-400 hover:text-indigo-600"
        @click="creating = true"
      >
        <Plus :size="22" />
        <span class="text-[13.5px] font-medium">新建工作空间</span>
      </button>
    </div>

    <UiModal :open="creating" title="新建工作空间" @close="creating = false">
      <div class="space-y-4">
        <div>
          <label class="label">名称 <span class="text-rose-500">*</span></label>
          <input v-model="name" class="input" placeholder="例如：信贷业务血缘" @keyup.enter="submitCreate" />
        </div>
        <div>
          <label class="label">描述</label>
          <textarea v-model="description" class="textarea" rows="3" placeholder="这个工作空间用来梳理什么业务？（可选）" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button class="btn-secondary" @click="creating = false">取消</button>
          <button class="btn-primary" :disabled="!name.trim() || saving" @click="submitCreate">
            <LoaderCircle v-if="saving" :size="15" class="animate-spin" />
            创建并进入
          </button>
        </div>
      </template>
    </UiModal>
  </div>
</template>
