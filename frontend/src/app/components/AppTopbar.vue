<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronsUpDown, Check, Settings2, Layers } from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';
import { wsColorForIndex } from '../lib/workspaceTheme';
import { timeAgo } from '../lib/format';

const route = useRoute();
const router = useRouter();
const ws = useWorkspaceStore();

const title = computed(() => (route.meta.title as string) || '');
const open = ref(false);

function wsColor(idx: number) {
  return wsColorForIndex(idx);
}

function switchTo(id: string) {
  open.value = false;
  if (id === ws.currentId) return;
  ws.select(id);
  router.push({ name: 'dashboard' }).then(() => window.location.reload());
}

function manage() {
  open.value = false;
  router.push({ name: 'workspaces' });
}
</script>

<template>
  <header class="flex h-14 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-5">
    <h1 class="text-[15px] font-semibold text-slate-900">{{ title }}</h1>

    <!-- 工作空间切换器 -->
    <div class="relative">
      <button
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-left transition hover:bg-slate-50"
        @click="open = !open"
      >
        <div
          class="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-[11px] font-bold text-white"
          :style="{ background: wsColor(ws.list.findIndex(w => w.id === ws.currentId)).bg }"
        >
          {{ ws.current?.name?.charAt(0) || '?' }}
        </div>
        <div class="min-w-0 leading-tight hidden sm:block">
          <div class="truncate text-[13px] font-medium text-slate-800">
            {{ ws.current?.name || '选择工作空间' }}
          </div>
          <div class="text-[11px] text-slate-400" v-if="ws.current">
            工作空间
          </div>
        </div>
        <ChevronsUpDown :size="14" class="shrink-0 text-slate-400" />
      </button>

      <!-- 下拉遮罩 -->
      <div v-if="open" class="fixed inset-0 z-30" @click="open = false" />

      <!-- 下拉菜单 -->
      <div
        v-if="open"
        class="absolute right-0 z-40 mt-1.5 w-64 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-lg"
      >
        <div class="px-3 py-1.5 text-[10.5px] font-medium uppercase tracking-wider text-slate-400">
          切换工作空间
        </div>
        <button
          v-for="(w, i) in ws.list"
          :key="w.id"
          class="flex w-full items-center gap-2 px-3 py-1.5 text-left transition hover:bg-slate-50"
          @click="switchTo(w.id)"
        >
          <div
            class="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-[10px] font-bold text-white"
            :style="{ background: wsColor(i).bg }"
          >
            {{ w.name.charAt(0) }}
          </div>
          <span class="min-w-0 flex-1">
            <span class="truncate text-[12.5px]" :class="w.id === ws.currentId ? 'font-semibold text-indigo-700' : 'text-slate-700'">
              {{ w.name }}
              <span v-if="w.id === ws.currentId" class="text-[11px] text-indigo-500">✓</span>
            </span>
            <span class="block truncate text-[10.5px] text-slate-400">
              {{ w.createdAt ? timeAgo(w.createdAt) : '' }}
            </span>
          </span>
        </button>
        <div class="mt-1 border-t border-slate-100 pt-1">
          <button
            class="flex w-full items-center gap-2 px-3 py-2 text-left text-[12.5px] font-medium text-indigo-600 transition hover:bg-indigo-50"
            @click="manage"
          >
            <Settings2 :size="14" />
            管理 / 新建工作空间
          </button>
        </div>
      </div>
    </div>
  </header>
</template>
