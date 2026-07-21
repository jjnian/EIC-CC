<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronDown, Check, Layers, Plus } from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';

const route = useRoute();
const router = useRouter();
const ws = useWorkspaceStore();

const title = computed(() => (route.meta.title as string) || '');
const open = ref(false);

function switchTo(id: string) {
  open.value = false;
  if (id === ws.currentId) return;
  ws.select(id);
  // 工作空间切换后回到工作台并整页刷新，避免旧空间数据残留
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

    <div class="relative">
      <button class="btn-secondary btn-sm max-w-[260px]" @click="open = !open">
        <Layers :size="14" class="shrink-0 text-indigo-600" />
        <span class="truncate">{{ ws.current?.name || '选择工作空间' }}</span>
        <ChevronDown :size="14" class="shrink-0 text-slate-400" />
      </button>

      <div v-if="open" class="fixed inset-0 z-30" @click="open = false" />
      <div
        v-if="open"
        class="absolute right-0 z-40 mt-1.5 w-64 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-lg"
      >
        <div class="px-3 py-1.5 text-xs font-medium text-slate-400">切换工作空间</div>
        <button
          v-for="w in ws.list"
          :key="w.id"
          class="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-[13px] hover:bg-slate-50"
          @click="switchTo(w.id)"
        >
          <span class="truncate text-slate-700">{{ w.name }}</span>
          <Check v-if="w.id === ws.currentId" :size="14" class="shrink-0 text-indigo-600" />
        </button>
        <div class="mt-1 border-t border-slate-100 pt-1">
          <button
            class="flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] text-indigo-600 hover:bg-indigo-50"
            @click="manage"
          >
            <Plus :size="14" /> 管理 / 新建工作空间
          </button>
        </div>
      </div>
    </div>
  </header>
</template>
